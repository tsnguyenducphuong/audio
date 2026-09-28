package expo.modules.audioconverter

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

// ============================================================================
// WhisperAudioConverter
//
// Decodes any audio file Android can read (MP3, AAC/M4A, OGG, FLAC, WAV, ...),
// optionally trims it, and writes a WAV file that Whisper can transcribe.
//
// Processing chain (every stage is streaming, memory use is constant):
//
//   MediaCodec decode
//     -> trim (sample accurate, early stop at the end offset)
//     -> phase-safe channel downmix
//     -> anti-aliased polyphase resampling (Kaiser-windowed sinc, ~96 dB)
//     -> 4th-order Butterworth high-pass (removes DC offset and rumble)
//     -> float32 spool file + gated loudness analysis
//     -> gain to a speech-friendly level + soft limiter + TPDF dither
//     -> 16-bit (or 32-bit) PCM WAV
//
// Defaults are tuned for Whisper: 16 kHz, mono, signed 16-bit PCM.
// ============================================================================

private const val TAG = "WhisperAudioConverter"

/** Decode a little before the requested start so codec state is warmed up. */
private const val SEEK_PREROLL_US = 250_000L

private const val SPOOL_CHUNK_SAMPLES = 8192

// ============================================================================
// Configuration
// ============================================================================

class WhisperAudioConversionOptions : Record {
  @Field
  var sampleRate: Double = 16_000.0

  @Field
  var channels: Int = 1

  @Field
  var bitDepth: Int = 16

  /**
   * Number of audio frames processed per block.
   */
  @Field
  var bufferSize: Int = 8192

  /**
   * Delete the partially written output when conversion fails.
   */
  @Field
  var cleanupOnFailure: Boolean = true

  // --------------------------------------------------------------------------
  // Speech enhancement (all optional, all on by default)
  // --------------------------------------------------------------------------

  /**
   * High-pass cutoff in Hz. Removes DC offset and low-frequency rumble.
   * Set to 0 to disable.
   */
  @Field
  var highPassHz: Double = 80.0

  /**
   * Bring the speech level to [targetLevelDb] (gated RMS, dBFS) before
   * writing. Quiet recordings are a very common reason for Whisper returning
   * nothing or hallucinating.
   */
  @Field
  var normalize: Boolean = true

  /** Target gated RMS level in dBFS. -20 is a good level for speech. */
  @Field
  var targetLevelDb: Double = -20.0

  /** Never boost by more than this many dB (avoids amplifying pure noise). */
  @Field
  var maxGainDb: Double = 30.0

  /** Never attenuate by more than this many dB. */
  @Field
  var maxAttenuationDb: Double = 20.0

  /** Soft-limiter ceiling in dBFS. Peaks never exceed this level. */
  @Field
  var peakCeilingDb: Double = -1.0

  /**
   * For stereo -> mono: detect out-of-phase stereo (which cancels to near
   * silence when averaged) and fall back to the stronger channel.
   */
  @Field
  var smartDownmix: Boolean = true

  /** Apply TPDF dither when quantizing to 16-bit PCM. */
  @Field
  var dither: Boolean = true
}

// ============================================================================
// Errors
// ============================================================================

private open class ConversionException(
  code: String,
  message: String
) : CodedException(code, message, null)

private class InvalidInputPathException :
  ConversionException(
    "ERR_INVALID_INPUT_PATH",
    "The input audio path is invalid or empty."
  )

private class InputFileNotFoundException :
  ConversionException(
    "ERR_INPUT_NOT_FOUND",
    "The input audio file does not exist."
  )

private class InputFileNotReadableException(detail: String) :
  ConversionException(
    "ERR_INPUT_NOT_READABLE",
    "The input audio file cannot be read: $detail"
  )

private class InvalidStartOffsetException :
  ConversionException(
    "ERR_INVALID_START_OFFSET",
    "startOffset must be finite and >= 0."
  )

private class InvalidEndOffsetException :
  ConversionException(
    "ERR_INVALID_END_OFFSET",
    "endOffset must be finite and >= 0."
  )

private class InvalidRangeException :
  ConversionException(
    "ERR_INVALID_RANGE",
    "The requested audio range is empty."
  )

private class UnsupportedSampleRateException :
  ConversionException(
    "ERR_UNSUPPORTED_SAMPLE_RATE",
    "Sample rate must be between 8,000 Hz and 192,000 Hz."
  )

private class UnsupportedChannelCountException :
  ConversionException(
    "ERR_UNSUPPORTED_CHANNELS",
    "Channel count must be between 1 and 8."
  )

private class UnsupportedBitDepthException :
  ConversionException(
    "ERR_UNSUPPORTED_BIT_DEPTH",
    "Only 16-bit and 32-bit PCM output is supported."
  )

private class InvalidBufferSizeException :
  ConversionException(
    "ERR_INVALID_BUFFER_SIZE",
    "Buffer size must be between 512 and 65,536 frames."
  )

private class InvalidEnhancementOptionException(detail: String) :
  ConversionException(
    "ERR_INVALID_ENHANCEMENT_OPTION",
    "Invalid enhancement option: $detail"
  )

private class UnsupportedPcmEncodingException :
  ConversionException(
    "ERR_UNSUPPORTED_PCM_ENCODING",
    "The Android decoder returned an unsupported PCM encoding."
  )

private class DecoderSetupException(detail: String) :
  ConversionException(
    "ERR_DECODER_SETUP",
    "Failed to set up audio decoder: $detail"
  )

private class ConversionFailedException(detail: String) :
  ConversionException(
    "ERR_CONVERSION",
    "Audio conversion failed: $detail"
  )

private class ConversionTimeoutException :
  ConversionException(
    "ERR_CONVERSION_TIMEOUT",
    "The audio decoder produced no progress for too long and appears to be stuck. " +
      "This usually means the codec never signaled end-of-stream for this file."
  )

private class OutputWriteException(detail: String) :
  ConversionException(
    "ERR_OUTPUT_WRITE",
    "Failed to write output WAV file: $detail"
  )

private class EmptyOutputException :
  ConversionException(
    "ERR_EMPTY_OUTPUT",
    "Conversion produced an empty audio file."
  )

// ==== DSP BEGIN =============================================================
// Pure Kotlin, no Android dependencies (unit-testable on a plain JVM).
// ============================================================================

/** Modified Bessel function of the first kind, order 0 (power series). */
private fun besselI0(x: Double): Double {
  var sum = 1.0
  var term = 1.0
  val quarterSquare = x * x / 4.0
  var k = 1

  while (k < 500) {
    term *= quarterSquare / (k.toDouble() * k.toDouble())
    sum += term

    if (term < sum * 1e-15) {
      break
    }

    k++
  }

  return sum
}

/** Resizable buffer of unboxed floats. */
private class GrowableFloatBuffer(initialCapacity: Int = 1024) {
  var values: FloatArray = FloatArray(max(16, initialCapacity))
    private set

  var size: Int = 0
    private set

  fun clear() {
    size = 0
  }

  fun add(value: Float) {
    ensureCapacity(size + 1)
    values[size++] = value
  }

  fun addAll(source: FloatArray, count: Int) {
    ensureCapacity(size + count)
    System.arraycopy(source, 0, values, size, count)
    size += count
  }

  private fun ensureCapacity(required: Int) {
    if (required > values.size) {
      values = values.copyOf(max(required, values.size * 2))
    }
  }
}

/**
 * Streaming band-limited resampler.
 *
 * Kaiser-windowed sinc FIR evaluated through a 1024-phase table.
 *
 * The filter length scales with the decimation ratio so the anti-alias
 * filter is always properly sharp: for 44.1 kHz -> 16 kHz it has ~96 taps
 * and rejects everything above ~9 kHz by more than 90 dB. The previous
 * fixed 16-tap Hann filter let content at 10 kHz through at only -14 dB,
 * which folded back into the 2-6 kHz band that carries consonants.
 *
 * Output sample n sits exactly at input time n * inputRate / outputRate
 * (zero group delay), so trimming stays sample accurate.
 */
private class PolyphaseResampler(
  inputRate: Double,
  outputRate: Double,
  zeroCrossings: Int = 16,
  private val kaiserBeta: Double = 9.0,
  passbandRatio: Double = 0.93,
  private val phases: Int = 1024
) {
  init {
    require(inputRate > 0.0)
    require(outputRate > 0.0)
    require(zeroCrossings >= 4)
    require(phases >= 32)
  }

  /** Input samples advanced per output sample. */
  private val step = inputRate / outputRate

  /** -6 dB cutoff, in cycles per input sample. */
  private val cutoff = 0.5 * min(1.0, outputRate / inputRate) * passbandRatio

  private val halfTaps = ceil(zeroCrossings / (2.0 * cutoff)).toInt()
  private val taps = halfTaps * 2

  /** (phases + 1) rows of [taps] coefficients, each row sums to 1. */
  private val table: FloatArray = buildTable()

  private var buffer = FloatArray(max(8192, taps * 4))

  /** Absolute input index of buffer[0]. Starts before 0 for edge history. */
  private var bufferStart = -halfTaps.toLong()
  private var bufferLength = halfTaps

  private var totalInput = 0L
  private var outputCount = 0L

  private var lastSample = 0.0f

  fun process(source: FloatArray, count: Int, output: GrowableFloatBuffer) {
    if (count <= 0) {
      return
    }

    if (totalInput == 0L) {
      // Hold the first sample as pre-history instead of zeros: when trimming
      // starts mid-speech this avoids a step discontinuity at the edge.
      java.util.Arrays.fill(buffer, 0, halfTaps, source[0])
    }

    append(source, count)
    totalInput += count
    lastSample = source[count - 1]

    produce(output, Long.MAX_VALUE)
    compact()
  }

  /**
   * Emits the remaining outputs whose centre lies inside the input, using a
   * held last sample as post-history.
   */
  fun flush(output: GrowableFloatBuffer) {
    if (totalInput == 0L) {
      return
    }

    val expectedOutputs = ceil(totalInput.toDouble() / step).toLong()

    val padding = FloatArray(halfTaps + 1)
    java.util.Arrays.fill(padding, lastSample)
    append(padding, padding.size)

    produce(output, expectedOutputs)
    compact()
  }

  private fun produce(output: GrowableFloatBuffer, maxOutputs: Long) {
    val endExclusive = bufferStart + bufferLength

    while (outputCount < maxOutputs) {
      val position = outputCount.toDouble() * step
      val center = floor(position).toLong()

      if (center + halfTaps >= endExclusive) {
        break
      }

      val fraction = position - center
      val phase = (fraction * phases + 0.5).toInt()

      val base = (center - halfTaps + 1 - bufferStart).toInt()
      val row = phase * taps

      var accumulator = 0.0f

      for (k in 0 until taps) {
        accumulator += buffer[base + k] * table[row + k]
      }

      output.add(accumulator)
      outputCount++
    }
  }

  private fun append(source: FloatArray, count: Int) {
    val required = bufferLength + count

    if (required > buffer.size) {
      buffer = buffer.copyOf(max(required, buffer.size * 2))
    }

    System.arraycopy(source, 0, buffer, bufferLength, count)
    bufferLength += count
  }

  private fun compact() {
    val nextCenter = floor(outputCount.toDouble() * step).toLong()
    val neededStart = nextCenter - halfTaps + 1

    val discard = min(
      (neededStart - bufferStart).coerceAtLeast(0L),
      bufferLength.toLong()
    ).toInt()

    if (discard <= 0) {
      return
    }

    System.arraycopy(buffer, discard, buffer, 0, bufferLength - discard)

    bufferLength -= discard
    bufferStart += discard
  }

  private fun buildTable(): FloatArray {
    val result = FloatArray((phases + 1) * taps)
    val row = DoubleArray(taps)
    val besselBeta = besselI0(kaiserBeta)

    for (phase in 0..phases) {
      val fraction = phase.toDouble() / phases

      var sum = 0.0

      for (k in 0 until taps) {
        val x = (k - halfTaps + 1).toDouble() - fraction
        val u = x / halfTaps

        val window =
          if (abs(u) >= 1.0) {
            0.0
          } else {
            besselI0(kaiserBeta * sqrt(1.0 - u * u)) / besselBeta
          }

        val argument = 2.0 * cutoff * x

        val sinc =
          if (abs(argument) < 1e-12) {
            1.0
          } else {
            sin(PI * argument) / (PI * argument)
          }

        val coefficient = 2.0 * cutoff * sinc * window

        row[k] = coefficient
        sum += coefficient
      }

      for (k in 0 until taps) {
        result[phase * taps + k] = (row[k] / sum).toFloat()
      }
    }

    return result
  }
}

/**
 * 4th-order Butterworth high-pass (two cascaded biquads).
 *
 * Removes DC offset and sub-bass rumble, both of which are common in phone
 * and USB-mic recordings and waste Whisper's dynamic range. The filter state
 * is primed with the first sample so cutting mid-recording does not produce
 * a startup thump.
 */
private class HighPassFilter(sampleRate: Double, cutoffHz: Double) {
  private class Stage(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
  ) {
    var z1 = 0.0
    var z2 = 0.0
  }

  private val stages: Array<Stage>
  private var primed = false

  init {
    require(cutoffHz > 0.0 && cutoffHz < sampleRate / 2.0)

    val w0 = 2.0 * PI * cutoffHz / sampleRate
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)

    // Butterworth pole Q values for a 4th-order filter.
    val qValues = doubleArrayOf(
      1.0 / (2.0 * cos(PI / 8.0)),
      1.0 / (2.0 * cos(3.0 * PI / 8.0))
    )

    stages = Array(2) { index ->
      val alpha = sinW0 / (2.0 * qValues[index])
      val a0 = 1.0 + alpha

      Stage(
        b0 = ((1.0 + cosW0) / 2.0) / a0,
        b1 = (-(1.0 + cosW0)) / a0,
        b2 = ((1.0 + cosW0) / 2.0) / a0,
        a1 = (-2.0 * cosW0) / a0,
        a2 = (1.0 - alpha) / a0
      )
    }
  }

  fun process(buffer: FloatArray, count: Int) {
    if (count <= 0) {
      return
    }

    if (!primed) {
      // Steady-state for a constant input x0: output 0, so
      // z1 = -b0 * x0 and z2 = b2 * x0 (valid because b0 + b1 + b2 = 0).
      val first = stages[0]
      val x0 = buffer[0].toDouble()

      first.z1 = -first.b0 * x0
      first.z2 = first.b2 * x0

      primed = true
    }

    for (i in 0 until count) {
      var x = buffer[i].toDouble()

      for (stage in stages) {
        val y = stage.b0 * x + stage.z1

        stage.z1 = stage.b1 * x - stage.a1 * y + stage.z2
        stage.z2 = stage.b2 * x - stage.a2 * y

        x = y
      }

      buffer[i] = x.toFloat()
    }
  }
}

/**
 * Converts interleaved source frames to de-interleaved target channels.
 *
 * Plain (L + R) / 2 is only safe when the channels are in phase. Some MP3s
 * (badly encoded joint stereo, some field recorders, certain voice-changer
 * output) carry speech with opposite polarity between channels; averaging
 * those cancels the voice to near silence, and Whisper then has nothing to
 * transcribe. In smart mode the mixer watches the energy of the mid signal
 * against the channels and, when cancellation is detected, crossfades to the
 * stronger channel instead.
 */
private class ChannelMixer(
  private val targetChannels: Int,
  private val smartStereo: Boolean
) {
  private var sourceChannels = 1
  private var blockFrames = 441
  private var smoothing = 0.01

  private var powerLeft = 0.0
  private var powerRight = 0.0
  private var powerMid = 0.0
  private var statsInitialized = false

  private var weightLeft = 0.5
  private var weightRight = 0.5

  fun configure(sourceChannels: Int, sampleRate: Int) {
    this.sourceChannels = sourceChannels
    this.blockFrames = max(64, sampleRate / 100)
    this.smoothing = 1.0 - exp(-1.0 / (0.005 * sampleRate))

    statsInitialized = false
    powerLeft = 0.0
    powerRight = 0.0
    powerMid = 0.0
    weightLeft = 0.5
    weightRight = 0.5
  }

  fun mix(source: FloatArray, frames: Int, output: Array<FloatArray>) {
    when {
      sourceChannels == targetChannels -> {
        for (frame in 0 until frames) {
          val base = frame * sourceChannels

          for (channel in 0 until targetChannels) {
            output[channel][frame] = source[base + channel]
          }
        }
      }

      targetChannels == 1 && sourceChannels == 2 && smartStereo -> {
        mixStereoAdaptive(source, frames, output[0])
      }

      targetChannels == 1 -> {
        val scale = 1.0f / sourceChannels

        for (frame in 0 until frames) {
          val base = frame * sourceChannels
          var sum = 0.0f

          for (channel in 0 until sourceChannels) {
            sum += source[base + channel]
          }

          output[0][frame] = sum * scale
        }
      }

      sourceChannels == 1 -> {
        for (frame in 0 until frames) {
          val value = source[frame]

          for (channel in 0 until targetChannels) {
            output[channel][frame] = value
          }
        }
      }

      else -> {
        // Each target channel receives an averaged group of source channels.
        for (frame in 0 until frames) {
          val base = frame * sourceChannels

          for (target in 0 until targetChannels) {
            val start = target * sourceChannels / targetChannels

            val end = max(
              start + 1,
              (target + 1) * sourceChannels / targetChannels
            )

            var sum = 0.0f

            for (channel in start until end) {
              sum += source[base + channel]
            }

            output[target][frame] = sum / (end - start)
          }
        }
      }
    }
  }

  private fun mixStereoAdaptive(
    source: FloatArray,
    frames: Int,
    output: FloatArray
  ) {
    var frame = 0

    while (frame < frames) {
      val count = min(blockFrames, frames - frame)

      var sumLeft = 0.0
      var sumRight = 0.0
      var sumMid = 0.0

      for (i in 0 until count) {
        val left = source[(frame + i) * 2].toDouble()
        val right = source[(frame + i) * 2 + 1].toDouble()
        val mid = 0.5 * (left + right)

        sumLeft += left * left
        sumRight += right * right
        sumMid += mid * mid
      }

      val blockLeft = sumLeft / count
      val blockRight = sumRight / count
      val blockMid = sumMid / count

      if (!statsInitialized) {
        powerLeft = blockLeft
        powerRight = blockRight
        powerMid = blockMid
        statsInitialized = true
      } else {
        // ~100 ms energy averaging.
        powerLeft = powerLeft * 0.9 + blockLeft * 0.1
        powerRight = powerRight * 0.9 + blockRight * 0.1
        powerMid = powerMid * 0.9 + blockMid * 0.1
      }

      var targetLeft = weightLeft
      var targetRight = weightRight

      val averagePower = 0.5 * (powerLeft + powerRight)

      // -90 dBFS: below this the block carries no usable information.
      if (averagePower > 1e-9) {
        if (powerMid / averagePower < 0.25) {
          // Mid is >6 dB below the channel average: strong anti-correlation.
          if (powerLeft >= powerRight) {
            targetLeft = 1.0
            targetRight = 0.0
          } else {
            targetLeft = 0.0
            targetRight = 1.0
          }
        } else {
          targetLeft = 0.5
          targetRight = 0.5
        }
      }

      for (i in 0 until count) {
        weightLeft += (targetLeft - weightLeft) * smoothing
        weightRight += (targetRight - weightRight) * smoothing

        val left = source[(frame + i) * 2]
        val right = source[(frame + i) * 2 + 1]

        output[frame + i] =
          (weightLeft * left + weightRight * right).toFloat()
      }

      frame += count
    }
  }
}

private class LoudnessStats(
  val peak: Float,
  val gatedRmsDb: Double,
  val hasSignal: Boolean
)

/**
 * Gated loudness measurement over 100 ms blocks (a simplified BS.1770 gate).
 *
 * Silent or near-silent blocks are excluded, so pauses between sentences do
 * not drag the estimate down and cause over-amplification of speech.
 */
private class LoudnessAnalyzer(sampleRate: Double, channels: Int) {
  private val samplesPerBlock =
    max(1, (sampleRate * 0.1).roundToInt()) * channels

  private var accumulator = 0.0
  private var accumulated = 0

  private var blockPowers = DoubleArray(1024)
  private var blockCount = 0

  private var peak = 0.0f

  fun add(samples: FloatArray, count: Int) {
    for (i in 0 until count) {
      val x = samples[i]
      val magnitude = abs(x)

      if (magnitude > peak) {
        peak = magnitude
      }

      accumulator += x.toDouble() * x.toDouble()
      accumulated++

      if (accumulated == samplesPerBlock) {
        pushBlock(accumulator / accumulated)
        accumulator = 0.0
        accumulated = 0
      }
    }
  }

  fun finish(): LoudnessStats {
    if (accumulated >= samplesPerBlock / 2) {
      pushBlock(accumulator / accumulated)
      accumulator = 0.0
      accumulated = 0
    }

    // Absolute gate: -70 dBFS (power 1e-7).
    val absoluteGate = 1e-7

    var sum = 0.0
    var count = 0

    for (i in 0 until blockCount) {
      if (blockPowers[i] > absoluteGate) {
        sum += blockPowers[i]
        count++
      }
    }

    if (count == 0) {
      return LoudnessStats(peak, -120.0, false)
    }

    // Relative gate: 10 dB below the average of the absolute-gated blocks.
    val relativeGate = (sum / count) * 0.1

    var gatedSum = 0.0
    var gatedCount = 0

    for (i in 0 until blockCount) {
      val power = blockPowers[i]

      if (power > absoluteGate && power >= relativeGate) {
        gatedSum += power
        gatedCount++
      }
    }

    return LoudnessStats(
      peak,
      10.0 * log10(gatedSum / gatedCount),
      true
    )
  }

  private fun pushBlock(power: Double) {
    if (blockCount == blockPowers.size) {
      blockPowers = blockPowers.copyOf(blockPowers.size * 2)
    }

    blockPowers[blockCount++] = power
  }
}

/**
 * Transparent below [knee]; smoothly saturates towards [ceiling] above it.
 * Never exceeds [ceiling], never hard-clips.
 */
private fun softLimit(x: Float, knee: Float, ceiling: Float): Float {
  val magnitude = abs(x)

  if (magnitude <= knee) {
    return x
  }

  val range = ceiling - knee
  val limited = knee + range * tanh((magnitude - knee) / range)

  return if (x < 0.0f) -limited else limited
}

// ==== DSP END ===============================================================

// ============================================================================
// Float spool (intermediate high-precision storage between the two passes)
// ============================================================================

private class FloatSpoolWriter(file: File) : Closeable {
  private val stream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
  private val bytes = ByteArray(SPOOL_CHUNK_SAMPLES * 4)

  private val byteBuffer =
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  var totalSamples: Long = 0
    private set

  fun write(samples: FloatArray, count: Int) {
    try {
      var offset = 0

      while (offset < count) {
        val n = min(SPOOL_CHUNK_SAMPLES, count - offset)

        byteBuffer.clear()

        for (i in 0 until n) {
          val value = samples[offset + i]
          byteBuffer.putFloat(if (value.isNaN()) 0.0f else value)
        }

        stream.write(bytes, 0, n * 4)
        offset += n
      }

      totalSamples += count
    } catch (e: IOException) {
      throw OutputWriteException(e.message ?: "Spool write error.")
    }
  }

  override fun close() {
    stream.close()
  }
}

private class FloatSpoolReader(file: File) : Closeable {
  private val stream = BufferedInputStream(FileInputStream(file), 1 shl 16)
  private val bytes = ByteArray(SPOOL_CHUNK_SAMPLES * 4)

  private val byteBuffer =
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  /** Returns the number of samples read, 0 at end of file. */
  fun read(destination: FloatArray): Int {
    val wanted = min(SPOOL_CHUNK_SAMPLES, destination.size) * 4

    try {
      var received = 0

      while (received < wanted) {
        val n = stream.read(bytes, received, wanted - received)

        if (n < 0) {
          break
        }

        received += n
      }

      val samples = received / 4

      byteBuffer.clear()

      for (i in 0 until samples) {
        destination[i] = byteBuffer.getFloat()
      }

      return samples
    } catch (e: IOException) {
      throw OutputWriteException(e.message ?: "Spool read error.")
    }
  }

  override fun close() {
    stream.close()
  }
}

// ============================================================================
// WAV Writer
// ============================================================================

private class WavPcmWriter(
  private val raf: RandomAccessFile,
  channels: Int,
  private val bitDepth: Int,
  private val dither: Boolean,
  framesPerBuffer: Int
) {
  private val bytesPerSample = bitDepth / 8

  private val scratch = ByteArray(
    max(1, framesPerBuffer) * channels * bytesPerSample
  )

  private var scratchOffset = 0

  // xorshift32 state for TPDF dither (deterministic output).
  private var randomState = 0x2545F491

  var totalBytesWritten: Long = 0
    private set

  /** Writes interleaved samples in the range [-1, 1]. */
  fun writeSamples(samples: FloatArray, count: Int) {
    for (i in 0 until count) {
      val value = sanitizeSample(samples[i])

      when (bitDepth) {
        16 -> writeInt16(value)
        32 -> writeInt32(value)
        else -> throw OutputWriteException(
          "Unsupported bit depth: $bitDepth"
        )
      }

      if (scratchOffset == scratch.size) {
        flushScratch()
      }
    }
  }

  fun finish() {
    flushScratch()
  }

  private fun writeInt16(sample: Float) {
    var scaled = sample * 32767.0f

    if (dither) {
      // Triangular PDF, +/- 1 LSB. Decorrelates quantization error and
      // prevents exact digital silence.
      scaled += nextRandom() - nextRandom()
    }

    val value = scaled.roundToInt().coerceIn(-32768, 32767)

    scratch[scratchOffset] = (value and 0xFF).toByte()
    scratch[scratchOffset + 1] = ((value ushr 8) and 0xFF).toByte()

    scratchOffset += 2
  }

  private fun writeInt32(sample: Float) {
    val value =
      (sample.toDouble() * 2147483647.0)
        .roundToLong()
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()

    scratch[scratchOffset] = (value and 0xFF).toByte()
    scratch[scratchOffset + 1] = ((value ushr 8) and 0xFF).toByte()
    scratch[scratchOffset + 2] = ((value ushr 16) and 0xFF).toByte()
    scratch[scratchOffset + 3] = ((value ushr 24) and 0xFF).toByte()

    scratchOffset += 4
  }

  private fun nextRandom(): Float {
    var x = randomState
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    randomState = x

    return (x ushr 8) * (1.0f / 16777216.0f)
  }

  private fun sanitizeSample(value: Float): Float {
    return when {
      value.isNaN() -> 0.0f
      else -> value.coerceIn(-1.0f, 1.0f)
    }
  }

  private fun flushScratch() {
    if (scratchOffset == 0) {
      return
    }

    try {
      raf.write(scratch, 0, scratchOffset)
      totalBytesWritten += scratchOffset.toLong()
      scratchOffset = 0
    } catch (e: Exception) {
      throw OutputWriteException(
        e.message ?: "Unknown write error."
      )
    }
  }
}

// ============================================================================
// WAV Header
// ============================================================================

private fun writeWavHeader(
  raf: RandomAccessFile,
  dataBytes: Long,
  sampleRate: Int,
  channels: Int,
  bitsPerSample: Int
) {
  require(sampleRate > 0)
  require(channels in 1..8)
  require(bitsPerSample == 16 || bitsPerSample == 32)

  val bytesPerSample = bitsPerSample / 8
  val blockAlign = channels * bytesPerSample
  val byteRate = sampleRate.toLong() * blockAlign
  val riffSize = 36L + dataBytes

  if (dataBytes > 0xFFFFFFFFL) {
    throw OutputWriteException("WAV data exceeds the 4 GiB RIFF limit.")
  }

  if (riffSize > 0xFFFFFFFFL) {
    throw OutputWriteException("WAV file exceeds the 4 GiB RIFF limit.")
  }

  if (byteRate > 0xFFFFFFFFL) {
    throw OutputWriteException("WAV byte rate exceeds RIFF limits.")
  }

  val header = ByteBuffer
    .allocate(44)
    .order(ByteOrder.LITTLE_ENDIAN)

  header.put("RIFF".toByteArray(Charsets.US_ASCII))
  header.putInt(riffSize.toInt())

  header.put("WAVE".toByteArray(Charsets.US_ASCII))

  header.put("fmt ".toByteArray(Charsets.US_ASCII))
  header.putInt(16) // PCM fmt chunk size
  header.putShort(1) // WAVE_FORMAT_PCM
  header.putShort(channels.toShort())
  header.putInt(sampleRate)
  header.putInt(byteRate.toInt())
  header.putShort(blockAlign.toShort())
  header.putShort(bitsPerSample.toShort())

  header.put("data".toByteArray(Charsets.US_ASCII))
  header.putInt(dataBytes.toInt())

  raf.seek(0)
  raf.write(header.array())
}

// ============================================================================
// Per-call DSP pipeline
//
// One instance is created per convertAndTrimAudio call and never shared, so
// concurrent conversions on the same module instance stay fully isolated.
// ============================================================================

private class ConversionPipeline(
  private val options: WhisperAudioConversionOptions,
  initialSampleRate: Int,
  initialChannels: Int,
  private val spool: FloatSpoolWriter
) {
  var sourceSampleRate: Int = initialSampleRate
    private set

  var sourceChannels: Int = initialChannels
    private set

  var pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

  val chunkFrames: Int = options.bufferSize

  /** Interleaved source-rate samples, filled by the decoder stage. */
  var sourceChunk: FloatArray = FloatArray(chunkFrames * initialChannels)
    private set

  private val outputChannels = options.channels
  private val outputRate = options.sampleRate

  private val mixer = ChannelMixer(outputChannels, options.smartDownmix)

  private var resamplers: Array<PolyphaseResampler>? =
    createResamplers(initialSampleRate)

  private val highPass: Array<HighPassFilter>? =
    if (options.highPassHz > 0.0) {
      Array(outputChannels) {
        HighPassFilter(outputRate, options.highPassHz)
      }
    } else {
      null
    }

  val analyzer = LoudnessAnalyzer(outputRate, outputChannels)

  private val mixed = Array(outputChannels) { FloatArray(chunkFrames) }
  private val resampled = Array(outputChannels) { GrowableFloatBuffer() }
  private var interleaved = FloatArray(0)

  init {
    mixer.configure(initialChannels, initialSampleRate)
  }

  /**
   * Applies MediaCodec.INFO_OUTPUT_FORMAT_CHANGED. Anything still buffered
   * in the old resamplers is flushed before they are replaced.
   */
  fun onOutputFormatChanged(
    newSampleRate: Int,
    newChannels: Int,
    newPcmEncoding: Int
  ) {
    finish()

    sourceSampleRate = newSampleRate
    pcmEncoding = newPcmEncoding

    if (newChannels != sourceChannels) {
      sourceChannels = newChannels
      sourceChunk = FloatArray(chunkFrames * sourceChannels)
    }

    mixer.configure(sourceChannels, sourceSampleRate)
    resamplers = createResamplers(sourceSampleRate)
  }

  /** Processes [frames] interleaved frames already stored in [sourceChunk]. */
  fun processSourceChunk(frames: Int) {
    mixer.mix(sourceChunk, frames, mixed)

    val active = resamplers

    for (channel in 0 until outputChannels) {
      if (active == null) {
        resampled[channel].addAll(mixed[channel], frames)
      } else {
        active[channel].process(mixed[channel], frames, resampled[channel])
      }
    }

    emit()
  }

  /** Flushes resampler tails. Safe to call more than once. */
  fun finish() {
    resamplers?.let { active ->
      for (channel in 0 until outputChannels) {
        active[channel].flush(resampled[channel])
      }
    }

    emit()
  }

  private fun emit() {
    val frames = resampled[0].size

    for (channel in 1 until outputChannels) {
      if (resampled[channel].size != frames) {
        throw ConversionFailedException(
          "Resampler channel output became misaligned."
        )
      }
    }

    if (frames == 0) {
      return
    }

    val total = frames * outputChannels

    if (interleaved.size < total) {
      interleaved = FloatArray(total)
    }

    for (channel in 0 until outputChannels) {
      val values = resampled[channel].values

      highPass?.get(channel)?.process(values, frames)

      for (frame in 0 until frames) {
        interleaved[frame * outputChannels + channel] = values[frame]
      }

      resampled[channel].clear()
    }

    analyzer.add(interleaved, total)
    spool.write(interleaved, total)
  }

  private fun createResamplers(
    sourceRate: Int
  ): Array<PolyphaseResampler>? {
    if (sourceRate <= 0) {
      throw ConversionFailedException("Invalid source sample rate.")
    }

    // Same rate: no resampling, so no filtering artefacts either.
    if (abs(sourceRate - outputRate) < 0.5) {
      return null
    }

    return Array(outputChannels) {
      PolyphaseResampler(
        inputRate = sourceRate.toDouble(),
        outputRate = outputRate
      )
    }
  }
}

// ============================================================================
// Module
// ============================================================================

class WhisperAudioConverterModule : Module() {

  override fun definition() = ModuleDefinition {
    Name("WhisperAudioConverter")

    AsyncFunction("setValueAsync") { value: String ->
    }

    // The decode/resample/write loop is long-running blocking work, so it
    // is moved to Dispatchers.IO instead of occupying an Expo module
    // dispatcher thread.
    AsyncFunction("convertAndTrimAudio") Coroutine {
          inputPath: String,
          startOffset: Double,
          endOffset: Double,
          options: WhisperAudioConversionOptions ->

      withContext(Dispatchers.IO) {
        performConversion(
          inputPath = inputPath,
          startOffset = startOffset,
          endOffset = endOffset,
          options = options
        )
      }
    }
  }

  private val context: Context
    get() =
      appContext.reactContext
        ?: throw ConversionFailedException(
          "React context is unavailable."
        )

  // --------------------------------------------------------------------------
  // Conversion
  // --------------------------------------------------------------------------

  private fun performConversion(
    inputPath: String,
    startOffset: Double,
    endOffset: Double,
    options: WhisperAudioConversionOptions
  ): String {
    val trimmedPath = inputPath.trim()

    validateArguments(trimmedPath, startOffset, endOffset, options)

    val startUs = secondsToMicroseconds(startOffset)

    val endUs =
      if (endOffset == 0.0) {
        Long.MAX_VALUE
      } else {
        secondsToMicroseconds(endOffset)
      }

    if (endUs != Long.MAX_VALUE && endUs <= startUs) {
      throw InvalidRangeException()
    }

    val inputUri = resolveInputUri(trimmedPath)

    val token = UUID.randomUUID()
    val outputFile = File(context.cacheDir, "whisper-$token.wav")
    val spoolFile = File(context.cacheDir, "whisper-$token.f32.tmp")

    var extractor: MediaExtractor? = null
    var codec: MediaCodec? = null
    var raf: RandomAccessFile? = null
    var spoolWriter: FloatSpoolWriter? = null

    var conversionSucceeded = false

    try {
      val source = MediaExtractor()
      extractor = source

      openDataSource(source, inputUri)

      val trackIndex = findAudioTrack(source)

      if (trackIndex < 0) {
        throw InputFileNotReadableException("No audio track was found.")
      }

      source.selectTrack(trackIndex)

      val trackFormat = source.getTrackFormat(trackIndex)

      val mime =
        trackFormat.getString(MediaFormat.KEY_MIME)
          ?: throw DecoderSetupException("Audio MIME type is missing.")

      val sourceSampleRate =
        requireIntKey(trackFormat, MediaFormat.KEY_SAMPLE_RATE)

      val sourceChannels =
        requireIntKey(trackFormat, MediaFormat.KEY_CHANNEL_COUNT)

      if (sourceSampleRate <= 0 || sourceChannels <= 0) {
        throw DecoderSetupException("Invalid source audio format.")
      }

      val decoder =
        try {
          MediaCodec
            .createDecoderByType(mime)
            .also {
              it.configure(trackFormat, null, null, 0)
              it.start()
            }
        } catch (e: Exception) {
          throw DecoderSetupException(
            e.message ?: "Unable to initialize decoder."
          )
        }

      codec = decoder

      if (startUs > 0) {
        source.seekTo(
          max(0L, startUs - SEEK_PREROLL_US),
          MediaExtractor.SEEK_TO_PREVIOUS_SYNC
        )
      }

      val spool = FloatSpoolWriter(spoolFile)
      spoolWriter = spool

      val pipeline =
        ConversionPipeline(
          options = options,
          initialSampleRate = sourceSampleRate,
          initialChannels = sourceChannels,
          spool = spool
        )

      val bufferInfo = MediaCodec.BufferInfo()

      var inputEos = false
      var outputEos = false

      val maxInputBufferWaitUs = 10_000L
      val maxOutputBufferWaitUs = 10_000L

      // Watchdog: some OEM decoders never signal end-of-stream. Bail out
      // with a catchable error instead of hanging the JS promise forever.
      var lastProgressElapsedMs = SystemClock.elapsedRealtime()
      val maxIdleMs = 30_000L

      while (!outputEos) {
        var madeProgress = false

        // ------------------------------------------------------------
        // Feed decoder
        // ------------------------------------------------------------

        if (!inputEos) {
          val inputIndex = decoder.dequeueInputBuffer(maxInputBufferWaitUs)

          if (inputIndex >= 0) {
            madeProgress = true

            val inputBuffer =
              decoder.getInputBuffer(inputIndex)
                ?: throw ConversionFailedException(
                  "Decoder input buffer is unavailable."
                )

            inputBuffer.clear()

            val sampleSize = source.readSampleData(inputBuffer, 0)

            if (sampleSize < 0) {
              decoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
              )

              inputEos = true
            } else {
              decoder.queueInputBuffer(
                inputIndex,
                0,
                sampleSize,
                max(0L, source.sampleTime),
                0
              )

              source.advance()
            }
          }
        }

        // ------------------------------------------------------------
        // Receive decoded PCM
        // ------------------------------------------------------------

        when (
          val outputIndex =
            decoder.dequeueOutputBuffer(bufferInfo, maxOutputBufferWaitUs)
        ) {
          MediaCodec.INFO_TRY_AGAIN_LATER -> {
            // Continue feeding/draining the codec.
          }

          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            madeProgress = true

            val outputFormat = decoder.outputFormat

            val newPcmEncoding =
              if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
              } else {
                AudioFormat.ENCODING_PCM_16BIT
              }

            validatePcmEncoding(newPcmEncoding)

            pipeline.onOutputFormatChanged(
              newSampleRate =
                requireIntKey(outputFormat, MediaFormat.KEY_SAMPLE_RATE),
              newChannels =
                requireIntKey(outputFormat, MediaFormat.KEY_CHANNEL_COUNT),
              newPcmEncoding = newPcmEncoding
            )
          }

          else -> {
            if (outputIndex >= 0) {
              madeProgress = true

              try {
                if (bufferInfo.size > 0) {
                  val outputBuffer = decoder.getOutputBuffer(outputIndex)

                  if (outputBuffer != null) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val pcmBuffer =
                      outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)

                    val reachedEnd =
                      decodeIntoPipeline(
                        buffer = pcmBuffer,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        startUs = startUs,
                        endUs = endUs,
                        pipeline = pipeline
                      )

                    // Stop decoding as soon as the end offset is passed
                    // instead of decoding the rest of the file.
                    if (reachedEnd) {
                      outputEos = true
                    }
                  }
                }

                if (
                  (bufferInfo.flags and
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                ) {
                  outputEos = true
                }
              } finally {
                decoder.releaseOutputBuffer(outputIndex, false)
              }
            }
          }
        }

        if (madeProgress) {
          lastProgressElapsedMs = SystemClock.elapsedRealtime()
        } else if (
          !outputEos &&
          SystemClock.elapsedRealtime() - lastProgressElapsedMs >= maxIdleMs
        ) {
          throw ConversionTimeoutException()
        }
      }

      pipeline.finish()

      spool.close()
      spoolWriter = null

      if (spool.totalSamples <= 0) {
        throw EmptyOutputException()
      }

      // ----------------------------------------------------------------
      // Pass 2: gain + soft limit + dither -> final PCM WAV
      // ----------------------------------------------------------------

      val stats = pipeline.analyzer.finish()
      val gain = computeGain(stats, options)

      Log.i(
        TAG,
        "mime=$mime src=${sourceSampleRate}Hz/${sourceChannels}ch " +
          "-> ${options.sampleRate.roundToInt()}Hz/${options.channels}ch " +
          "peak=${formatDb(stats.peak.toDouble())}dBFS " +
          "gatedRms=${"%.1f".format(stats.gatedRmsDb)}dBFS " +
          "gain=${"%.1f".format(20.0 * log10(gain.toDouble()))}dB " +
          "samples=${spool.totalSamples}"
      )

      val output = RandomAccessFile(outputFile, "rw")
      raf = output

      // Reserve WAV header.
      output.seek(44)

      val writer =
        WavPcmWriter(
          raf = output,
          channels = options.channels,
          bitDepth = options.bitDepth,
          dither = options.dither && options.bitDepth == 16,
          framesPerBuffer = options.bufferSize
        )

      val ceiling = 10.0.pow(options.peakCeilingDb / 20.0).toFloat()
      val knee = ceiling * 0.75f
      val limiterActive = options.normalize

      FloatSpoolReader(spoolFile).use { reader ->
        val chunk = FloatArray(SPOOL_CHUNK_SAMPLES)

        while (true) {
          val count = reader.read(chunk)

          if (count <= 0) {
            break
          }

          if (gain != 1.0f || limiterActive) {
            for (i in 0 until count) {
              val amplified = chunk[i] * gain

              chunk[i] =
                if (limiterActive) {
                  softLimit(amplified, knee, ceiling)
                } else {
                  amplified
                }
            }
          }

          writer.writeSamples(chunk, count)
        }
      }

      writer.finish()

      if (writer.totalBytesWritten <= 0) {
        throw EmptyOutputException()
      }

      writeWavHeader(
        raf = output,
        dataBytes = writer.totalBytesWritten,
        sampleRate = options.sampleRate.roundToInt(),
        channels = options.channels,
        bitsPerSample = options.bitDepth
      )

      output.fd.sync()

      conversionSucceeded = true

      return Uri.fromFile(outputFile).toString()
    } catch (e: ConversionException) {
      throw e
    } catch (e: Exception) {
      throw ConversionFailedException(e.message ?: e.toString())
    } finally {
      try {
        codec?.stop()
      } catch (_: Exception) {
      }

      try {
        codec?.release()
      } catch (_: Exception) {
      }

      try {
        extractor?.release()
      } catch (_: Exception) {
      }

      try {
        spoolWriter?.close()
      } catch (_: Exception) {
      }

      try {
        raf?.close()
      } catch (_: Exception) {
      }

      try {
        spoolFile.delete()
      } catch (_: Exception) {
      }

      if (!conversionSucceeded && options.cleanupOnFailure) {
        try {
          outputFile.delete()
        } catch (_: Exception) {
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Gain
  // --------------------------------------------------------------------------

  private fun computeGain(
    stats: LoudnessStats,
    options: WhisperAudioConversionOptions
  ): Float {
    if (!options.normalize || !stats.hasSignal) {
      return 1.0f
    }

    val gainDb =
      (options.targetLevelDb - stats.gatedRmsDb)
        .coerceIn(-options.maxAttenuationDb, options.maxGainDb)

    var gain = 10.0.pow(gainDb / 20.0)

    // Allow the soft limiter to absorb at most ~10 dB of overshoot, so a
    // single loud transient cannot cause heavy limiting of the whole file.
    if (stats.peak > 0.0f) {
      val ceiling = 10.0.pow(options.peakCeilingDb / 20.0)
      gain = min(gain, 3.0 * ceiling / stats.peak.toDouble())
    }

    return gain.toFloat()
  }

  private fun formatDb(linear: Double): String {
    if (linear <= 0.0) {
      return "-inf"
    }

    return "%.1f".format(20.0 * log10(linear))
  }

  // --------------------------------------------------------------------------
  // PCM decoding + trimming
  // --------------------------------------------------------------------------

  /**
   * Converts one decoder output buffer to float, keeps only the frames inside
   * [startUs, endUs) and feeds them to the pipeline.
   *
   * @return true once the end offset has been reached.
   */
  private fun decodeIntoPipeline(
    buffer: ByteBuffer,
    presentationTimeUs: Long,
    startUs: Long,
    endUs: Long,
    pipeline: ConversionPipeline
  ): Boolean {
    val pcmEncoding = pipeline.pcmEncoding
    val sampleRate = pipeline.sourceSampleRate
    val channels = pipeline.sourceChannels

    validatePcmEncoding(pcmEncoding)

    if (sampleRate <= 0) {
      throw ConversionFailedException("Invalid source sample rate.")
    }

    if (channels <= 0) {
      throw ConversionFailedException("Invalid source channel count.")
    }

    val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
    val bytesPerSample = if (isFloat) 4 else 2
    val bytesPerFrame = channels * bytesPerSample

    val totalFrames = buffer.remaining() / bytesPerFrame

    if (totalFrames <= 0) {
      return false
    }

    // First frame whose timestamp is >= startUs.
    val firstKeep =
      if (presentationTimeUs >= startUs) {
        0
      } else {
        val skip =
          ceil(
            (startUs - presentationTimeUs).toDouble() *
              sampleRate / 1_000_000.0
          )

        min(skip, totalFrames.toDouble()).toInt()
      }

    // First frame whose timestamp is >= endUs.
    var endKeep = totalFrames
    var reachedEnd = false

    if (endUs != Long.MAX_VALUE) {
      val remainingUs = endUs - presentationTimeUs

      if (remainingUs <= 0) {
        endKeep = 0
        reachedEnd = true
      } else {
        val framesUntilEnd =
          ceil(remainingUs.toDouble() * sampleRate / 1_000_000.0)

        if (framesUntilEnd < totalFrames) {
          endKeep = framesUntilEnd.toInt()
          reachedEnd = true
        }
      }
    }

    if (firstKeep >= endKeep) {
      return reachedEnd
    }

    val basePosition = buffer.position()

    var frame = firstKeep

    while (frame < endKeep) {
      val frames = min(pipeline.chunkFrames, endKeep - frame)
      val samples = frames * channels
      val chunk = pipeline.sourceChunk

      var offset = basePosition + frame * bytesPerFrame

      if (isFloat) {
        for (i in 0 until samples) {
          chunk[i] = buffer.getFloat(offset).coerceIn(-1.0f, 1.0f)
          offset += 4
        }
      } else {
        for (i in 0 until samples) {
          chunk[i] = buffer.getShort(offset) / 32768.0f
          offset += 2
        }
      }

      pipeline.processSourceChunk(frames)

      frame += frames
    }

    return reachedEnd
  }

  // --------------------------------------------------------------------------
  // Input handling
  // --------------------------------------------------------------------------

  private fun requireIntKey(format: MediaFormat, key: String): Int {
    if (!format.containsKey(key)) {
      throw DecoderSetupException("Audio format is missing '$key'.")
    }

    return format.getInteger(key)
  }

  private fun findAudioTrack(extractor: MediaExtractor): Int {
    for (index in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(index)

      val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

      if (mime.startsWith("audio/")) {
        return index
      }
    }

    return -1
  }

  private fun openDataSource(extractor: MediaExtractor, uri: Uri) {
    try {
      when (uri.scheme?.lowercase()) {
        "content" -> {
          val descriptor =
            context.contentResolver.openFileDescriptor(uri, "r")
              ?: throw InputFileNotFoundException()

          descriptor.use {
            extractor.setDataSource(it.fileDescriptor)
          }
        }

        "file" -> {
          val path = uri.path ?: throw InvalidInputPathException()

          val file = File(path)

          if (!file.exists()) {
            throw InputFileNotFoundException()
          }

          if (!file.isFile) {
            throw InputFileNotReadableException(
              "The input path is not a regular file."
            )
          }

          if (!file.canRead()) {
            throw InputFileNotReadableException("Permission denied.")
          }

          extractor.setDataSource(file.absolutePath)
        }

        null,
        "" -> {
          val file = File(uri.path ?: uri.toString())

          if (!file.exists()) {
            throw InputFileNotFoundException()
          }

          extractor.setDataSource(file.absolutePath)
        }

        else -> {
          throw InputFileNotReadableException(
            "Unsupported URI scheme: ${uri.scheme}"
          )
        }
      }
    } catch (e: ConversionException) {
      throw e
    } catch (e: Exception) {
      throw InputFileNotReadableException(e.message ?: "Unknown error.")
    }
  }

  private fun resolveInputUri(path: String): Uri {
    return when {
      path.startsWith("content://", ignoreCase = true) -> Uri.parse(path)
      path.startsWith("file://", ignoreCase = true) -> Uri.parse(path)
      else -> Uri.fromFile(File(path))
    }
  }

  // --------------------------------------------------------------------------
  // Validation
  // --------------------------------------------------------------------------

  private fun validateArguments(
    inputPath: String,
    startOffset: Double,
    endOffset: Double,
    options: WhisperAudioConversionOptions
  ) {
    if (inputPath.isEmpty()) {
      throw InvalidInputPathException()
    }

    if (!startOffset.isFinite() || startOffset < 0.0) {
      throw InvalidStartOffsetException()
    }

    if (!endOffset.isFinite() || endOffset < 0.0) {
      throw InvalidEndOffsetException()
    }

    if (options.sampleRate < 8_000.0 || options.sampleRate > 192_000.0) {
      throw UnsupportedSampleRateException()
    }

    if (options.channels !in 1..8) {
      throw UnsupportedChannelCountException()
    }

    if (options.bitDepth != 16 && options.bitDepth != 32) {
      throw UnsupportedBitDepthException()
    }

    if (options.bufferSize < 512 || options.bufferSize > 65_536) {
      throw InvalidBufferSizeException()
    }

    if (endOffset > 0.0 && endOffset <= startOffset) {
      throw InvalidRangeException()
    }

    if (
      options.highPassHz != 0.0 &&
      (
        !options.highPassHz.isFinite() ||
          options.highPassHz < 20.0 ||
          options.highPassHz > min(400.0, options.sampleRate * 0.2)
        )
    ) {
      throw InvalidEnhancementOptionException(
        "highPassHz must be 0 (disabled) or between 20 and " +
          "${min(400.0, options.sampleRate * 0.2).roundToInt()} Hz."
      )
    }

    if (
      !options.targetLevelDb.isFinite() ||
      options.targetLevelDb < -40.0 ||
      options.targetLevelDb > -6.0
    ) {
      throw InvalidEnhancementOptionException(
        "targetLevelDb must be between -40 and -6 dBFS."
      )
    }

    if (
      !options.maxGainDb.isFinite() ||
      options.maxGainDb < 0.0 ||
      options.maxGainDb > 60.0
    ) {
      throw InvalidEnhancementOptionException(
        "maxGainDb must be between 0 and 60 dB."
      )
    }

    if (
      !options.maxAttenuationDb.isFinite() ||
      options.maxAttenuationDb < 0.0 ||
      options.maxAttenuationDb > 60.0
    ) {
      throw InvalidEnhancementOptionException(
        "maxAttenuationDb must be between 0 and 60 dB."
      )
    }

    if (
      !options.peakCeilingDb.isFinite() ||
      options.peakCeilingDb < -12.0 ||
      options.peakCeilingDb > 0.0
    ) {
      throw InvalidEnhancementOptionException(
        "peakCeilingDb must be between -12 and 0 dBFS."
      )
    }
  }

  private fun validatePcmEncoding(encoding: Int) {
    if (
      encoding != AudioFormat.ENCODING_PCM_16BIT &&
      encoding != AudioFormat.ENCODING_PCM_FLOAT
    ) {
      throw UnsupportedPcmEncodingException()
    }
  }

  private fun secondsToMicroseconds(seconds: Double): Long {
    if (!seconds.isFinite() || seconds < 0.0) {
      throw InvalidRangeException()
    }

    val value = seconds * 1_000_000.0

    if (value > Long.MAX_VALUE.toDouble()) {
      throw InvalidRangeException()
    }

    return value.roundToLong()
  }
}
