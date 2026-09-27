package expo.modules.audioconverter

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import expo.modules.kotlin.functions.Coroutine

// ============================================================================
// Configuration
// ============================================================================

class WhisperAudioConversionOptions : Record {
  /**
   * Default configuration is optimized for Whisper:
   *
   * 16 kHz
   * mono
   * signed 16-bit PCM
   *
   * Higher-quality WAV output can be requested by changing these values.
   */
  @Field
  var sampleRate: Double = 16_000.0

  @Field
  var channels: Int = 1

  @Field
  var bitDepth: Int = 16

  /**
   * Number of audio frames buffered before writing to disk.
   */
  @Field
  var bufferSize: Int = 8192

  /**
   * Delete the partially written output when conversion fails.
   */
  @Field
  var cleanupOnFailure: Boolean = true
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

// ============================================================================
// WAV Writer
// ============================================================================

private class WavPcmWriter(
  private val raf: RandomAccessFile,
  private val channels: Int,
  private val bitDepth: Int,
  framesPerBuffer: Int
) {
  private val bytesPerSample = bitDepth / 8
  private val frameBytes = channels * bytesPerSample

  private val scratch = ByteArray(
    max(1, framesPerBuffer) * frameBytes
  )

  private var scratchOffset = 0

  var totalBytesWritten: Long = 0
    private set

  fun writeFrame(samples: FloatArray) {
    require(samples.size == channels)

    for (sample in samples) {
      val value = sanitizeSample(sample)

      when (bitDepth) {
        16 -> writeInt16(value)
        32 -> writeInt32(value)
        else -> {
          throw OutputWriteException(
            "Unsupported bit depth: $bitDepth"
          )
        }
      }

      if (scratchOffset == scratch.size) {
        flushScratch()
      }
    }
  }

  private fun writeInt16(sample: Float) {
    val value = when {
      sample <= -1.0f -> -32768
      sample >= 1.0f -> 32767
      else -> (sample * 32767.0f).roundToInt()
    }

    scratch[scratchOffset] = (value and 0xFF).toByte()
    scratch[scratchOffset + 1] =
      ((value ushr 8) and 0xFF).toByte()

    scratchOffset += 2
  }

  private fun writeInt32(sample: Float) {
    val value = when {
      sample <= -1.0f -> Int.MIN_VALUE
      sample >= 1.0f -> Int.MAX_VALUE
      else -> {
        (sample.toDouble() * 2147483647.0)
          .roundToInt()
      }
    }

    scratch[scratchOffset] =
      (value and 0xFF).toByte()

    scratch[scratchOffset + 1] =
      ((value ushr 8) and 0xFF).toByte()

    scratch[scratchOffset + 2] =
      ((value ushr 16) and 0xFF).toByte()

    scratch[scratchOffset + 3] =
      ((value ushr 24) and 0xFF).toByte()

    scratchOffset += 4
  }

  private fun sanitizeSample(value: Float): Float {
    return when {
      value.isNaN() -> 0.0f
      value == Float.POSITIVE_INFINITY -> 1.0f
      value == Float.NEGATIVE_INFINITY -> -1.0f
      else -> value.coerceIn(-1.0f, 1.0f)
    }
  }

  fun finish() {
    flushScratch()
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
    throw OutputWriteException(
      "WAV data exceeds the 4 GiB RIFF limit."
    )
  }

  if (riffSize > 0xFFFFFFFFL) {
    throw OutputWriteException(
      "WAV file exceeds the 4 GiB RIFF limit."
    )
  }

  if (byteRate > 0xFFFFFFFFL) {
    throw OutputWriteException(
      "WAV byte rate exceeds RIFF limits."
    )
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
// High-quality streaming resampler
//
// Windowed-sinc FIR with a small polyphase table.
//
// This avoids the low-quality linear interpolation used in the original
// implementation while remaining dependency-free and streamable.
// ============================================================================

private class SincResampler(
  private val inputRate: Double,
  private val outputRate: Double,
  private val taps: Int = 16,
  private val phases: Int = 256
) {
  init {
    require(inputRate > 0.0)
    require(outputRate > 0.0)
    require(taps >= 8 && taps % 2 == 0)
    require(phases >= 32)
  }

  private val step = inputRate / outputRate

  private val cutoff =
    min(1.0, outputRate / inputRate) * 0.5

  private val coefficients =
    buildCoefficientTable()

  private var inputBuffer = FloatArray(4096)

  private var bufferStartIndex = 0L
  private var bufferSize = 0

  private var totalInputSamples = 0L
  private var outputSamples = 0L

  private var outputPosition = 0.0

  private var lastSample = 0.0f

  inline fun process(
    sample: Float,
    output: (Float) -> Unit
  ) {
    append(sample)

    totalInputSamples++

    while (canProduce()) {
      output(produceOne())
    }
  }

  /**
   * Flushes enough edge samples to produce exactly the expected number of
   * output samples for the input supplied to this resampler.
   */
  inline fun flush(output: (Float) -> Unit) {
    if (totalInputSamples == 0L) {
      return
    }

    val expectedOutput =
      ceil(
        totalInputSamples.toDouble() *
          outputRate /
          inputRate
      ).toLong()

    while (outputSamples < expectedOutput) {
      append(lastSample)

      while (
        outputSamples < expectedOutput &&
        canProduce()
      ) {
        output(produceOne())
      }
    }
  }

  private fun buildCoefficientTable(): Array<FloatArray> {
    val table = Array(phases) {
      FloatArray(taps)
    }

    val center = taps / 2 - 1

    for (phase in 0 until phases) {
      val fraction = phase.toDouble() / phases

      var sum = 0.0

      for (tap in 0 until taps) {
        val offset =
          tap - center

        val x =
          offset.toDouble() - fraction

        val sincArgument =
          2.0 * cutoff * x

        val sinc =
          if (abs(sincArgument) < 1e-12) {
            1.0
          } else {
            sin(PI * sincArgument) /
              (PI * sincArgument)
          }

        // Hann window.
        val window =
          0.5 *
            (
              1.0 -
                cos(
                  2.0 * PI *
                    tap /
                    (taps - 1)
                )
              )

        val coefficient =
          2.0 * cutoff *
            sinc *
            window

        table[phase][tap] =
          coefficient.toFloat()

        sum += coefficient
      }

      if (abs(sum) > 1e-12) {
        for (tap in 0 until taps) {
          table[phase][tap] =
            (table[phase][tap] / sum)
              .toFloat()
        }
      }
    }

    return table
  }

  private fun append(sample: Float) {
    if (bufferSize == inputBuffer.size) {
      compact()
    }

    if (bufferSize == inputBuffer.size) {
      inputBuffer =
        inputBuffer.copyOf(inputBuffer.size * 2)
    }

    inputBuffer[bufferSize++] = sample
    lastSample = sample
  }

  private fun compact() {
    val center =
      floor(outputPosition).toLong()

    val requiredStart =
      center - taps / 2L - 2L

    if (requiredStart <= bufferStartIndex) {
      return
    }

    val discard =
      min(
        bufferSize,
        (requiredStart - bufferStartIndex)
          .toInt()
      )

    if (discard <= 0) {
      return
    }

    System.arraycopy(
      inputBuffer,
      discard,
      inputBuffer,
      0,
      bufferSize - discard
    )

    bufferSize -= discard
    bufferStartIndex += discard
  }

  private fun canProduce(): Boolean {
    if (bufferSize <= 0) {
      return false
    }

    val center =
      floor(outputPosition).toLong()

    val centerRelative =
      center - bufferStartIndex

    val rightRequired =
      centerRelative + taps / 2

    return rightRequired < bufferSize
  }

  private fun produceOne(): Float {
    val center =
      floor(outputPosition).toLong()

    val fraction =
      outputPosition - center

    val phase =
      min(
        phases - 1,
        max(
          0,
          (fraction * phases)
            .roundToInt()
        )
      )

    val coefficients =
      coefficients[phase]

    val firstIndex =
      center - (taps / 2L - 1L)

    var result = 0.0

    for (tap in 0 until taps) {
      val absoluteIndex =
        firstIndex + tap

      val relative =
        (absoluteIndex - bufferStartIndex)
          .toInt()

      if (relative in 0 until bufferSize) {
        result +=
          inputBuffer[relative] *
            coefficients[tap]
      }
    }

    outputPosition += step
    outputSamples++

    return result
      .coerceIn(-1.0, 1.0)
      .toFloat()
  }
}

// ============================================================================
// Growable float buffer
//
// A reusable, resizable buffer of unboxed floats. Used in place of
// ArrayList<Float> in the multi-channel resampling paths so that draining a
// resampler's pending output doesn't box every Float into an object.
// ============================================================================

private class GrowableFloatBuffer(initialCapacity: Int = 8) {
  var values: FloatArray = FloatArray(initialCapacity)
    private set

  var size: Int = 0
    private set

  fun clear() {
    size = 0
  }

  fun add(value: Float) {
    if (size == values.size) {
      values = values.copyOf(values.size * 2)
    }

    values[size++] = value
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

    // performConversion is a synchronous, potentially long-running (tens
    // of seconds) MediaCodec decode/resample/write loop. Returning it
    // directly from the AsyncFunction body -- the way the rest of this
    // file used to -- would run that entire computation on Expo's shared
    // Android "modules queue" thread, since that's the thread an
    // AsyncFunction body executes on by default. That doesn't block the
    // JS thread, but it does block every *other* AsyncFunction call in
    // the app (from any Expo module, not just this one) for as long as
    // conversion takes, because they queue up behind it on that same
    // shared thread (see https://github.com/expo/expo/issues/49799 for a
    // description of this exact failure mode).
    //
    // Taking an explicit Promise and launching onto Dispatchers.IO frees
    // the modules-queue thread immediately -- the AsyncFunction body
    // itself returns as soon as the coroutine is launched -- and the
    // real work runs on its own thread pool instead. Leaving the
    // synchronous closure means we also lose Expo's automatic
    // "thrown CodedException -> rejected promise" behavior, so that's
    // replicated by hand below.
    AsyncFunction("convertAndTrimAudio") Coroutine {
          inputPath: String,
          startOffset: Double,
          endOffset: Double,
          options: WhisperAudioConversionOptions ->

      performConversion(
        inputPath = inputPath,
        startOffset = startOffset,
        endOffset = endOffset,
        options = options
      )
    }

    AsyncFunction("convertAndTrimAudio_v1") {
        inputPath: String,
        startOffset: Double,
        endOffset: Double,
        options: WhisperAudioConversionOptions,
        promise: Promise ->

      CoroutineScope(Dispatchers.IO).launch {
        try {
          val result =
            performConversion(
              inputPath = inputPath,
              startOffset = startOffset,
              endOffset = endOffset,
              options = options
            )

          promise.resolve(result)
        } catch (e: CodedException) {
          promise.reject(
            e.code,
            e.message ?: "Audio conversion failed.",
            e
          )
        } catch (e: Exception) {
          promise.reject(
            "ERR_CONVERSION",
            e.message ?: e.toString(),
            e
          )
        }
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
  // Call-scoped conversion state
  //
  // Everything that mutates while a single convertAndTrimAudio call is in
  // flight lives here, and here alone. performConversion creates a fresh
  // instance on its own stack for every call and never assigns it (or any
  // piece of it) to a property of WhisperAudioConverter, so two calls
  // running concurrently on the same module instance each get their own
  // codec, extractor, resamplers, and scratch buffers with nothing shared
  // between them.
  //
  // This is an inner class purely so it can reach the private
  // createResamplers/flushResamplers helpers below; it does not add any
  // state to the outer Module instance.
  // --------------------------------------------------------------------------

  private inner class ConversionCallState(
    initialSourceSampleRate: Int,
    initialSourceChannels: Int,
    private val options: WhisperAudioConversionOptions
  ) {
    var sourceSampleRate: Int = initialSourceSampleRate
      private set

    var sourceChannels: Int = initialSourceChannels
      private set

    var pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    var resamplers: Array<SincResampler> =
      createResamplers(sourceSampleRate, options)
      private set

    var sourceFrame: FloatArray = FloatArray(sourceChannels)
      private set

    val targetFrame: FloatArray = FloatArray(options.channels)

    // One reusable growable buffer per output channel, used only on the
    // multi-channel (options.channels > 1) resampling path. Its length is
    // fixed at options.channels for the lifetime of the call, since that
    // count never changes across format-change events — only the source
    // channel count can change.
    val pendingChannelBuffers: Array<GrowableFloatBuffer> =
      Array(options.channels) { GrowableFloatBuffer() }

    /**
     * Applies a MediaCodec.INFO_OUTPUT_FORMAT_CHANGED event.
     *
     * - Flushes whatever is currently buffered inside the old resamplers
     *   before discarding them, instead of silently dropping it. In
     *   practice this event fires before any audio has flowed through
     *   (so there's usually nothing to flush), but if it ever fires
     *   mid-stream this preserves the buffered tail instead of losing it.
     * - Reallocates sourceFrame whenever the decoder's real channel count
     *   differs from the container's originally declared channel count.
     */
    fun onOutputFormatChanged(
      newSourceSampleRate: Int,
      newSourceChannels: Int,
      newPcmEncoding: Int,
      writer: WavPcmWriter
    ) {
      flushResamplers(resamplers, targetFrame, writer)

      sourceSampleRate = newSourceSampleRate
      pcmEncoding = newPcmEncoding

      if (newSourceChannels != sourceChannels) {
        sourceChannels = newSourceChannels
        sourceFrame = FloatArray(sourceChannels)
      }

      resamplers = createResamplers(sourceSampleRate, options)
    }
  }

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

    validateArguments(
      trimmedPath,
      startOffset,
      endOffset,
      options
    )

    val startUs =
      secondsToMicroseconds(startOffset)

    val endUs =
      if (endOffset == 0.0) {
        Long.MAX_VALUE
      } else {
        secondsToMicroseconds(endOffset)
      }

    if (
      endUs != Long.MAX_VALUE &&
      endUs <= startUs
    ) {
      throw InvalidRangeException()
    }

    val inputUri =
      resolveInputUri(trimmedPath)

    val outputFile =
      File(
        context.cacheDir,
        "whisper-${UUID.randomUUID()}.wav"
      )

    var extractor: MediaExtractor? = null
    var codec: MediaCodec? = null
    var raf: RandomAccessFile? = null

    var conversionSucceeded = false

    try {
      extractor = MediaExtractor()

      openDataSource(
        extractor,
        inputUri
      )

      val trackIndex =
        findAudioTrack(extractor)

      if (trackIndex < 0) {
        throw InputFileNotReadableException(
          "No audio track was found."
        )
      }

      extractor.selectTrack(trackIndex)

      var inputFormat =
        extractor.getTrackFormat(trackIndex)

      val mime =
        inputFormat.getString(
          MediaFormat.KEY_MIME
        )
          ?: throw DecoderSetupException(
            "Audio MIME type is missing."
          )

      val sourceSampleRate =
        inputFormat.getInteger(
          MediaFormat.KEY_SAMPLE_RATE
        )

      val sourceChannels =
        inputFormat.getInteger(
          MediaFormat.KEY_CHANNEL_COUNT
        )

      if (
        sourceSampleRate <= 0 ||
        sourceChannels <= 0
      ) {
        throw DecoderSetupException(
          "Invalid source audio format."
        )
      }

      codec =
        try {
          MediaCodec
            .createDecoderByType(mime)
            .also {
              it.configure(
                inputFormat,
                null,
                null,
                0
              )
              it.start()
            }
        } catch (e: Exception) {
          throw DecoderSetupException(
            e.message ?: "Unable to initialize decoder."
          )
        }

      if (startUs > 0) {
        extractor.seekTo(
          startUs,
          MediaExtractor.SEEK_TO_PREVIOUS_SYNC
        )
      }

      raf =
        RandomAccessFile(
          outputFile,
          "rw"
        )

      // Reserve WAV header.
      raf.seek(44)

      val writer =
        WavPcmWriter(
          raf = raf,
          channels = options.channels,
          bitDepth = options.bitDepth,
          framesPerBuffer = options.bufferSize
        )

      val state =
        ConversionCallState(
          initialSourceSampleRate = sourceSampleRate,
          initialSourceChannels = sourceChannels,
          options = options
        )

      val bufferInfo =
        MediaCodec.BufferInfo()

      var inputEos = false
      var outputEos = false

      val maxInputBufferWaitUs = 10_000L
      val maxOutputBufferWaitUs = 10_000L

      // --------------------------------------------------------------
      // Watchdog
      //
      // A handful of OEM hardware decoders (and a few malformed or
      // unusually-muxed containers) never set BUFFER_FLAG_END_OF_STREAM
      // on their last output buffer, and/or MediaExtractor never returns
      // -1 from readSampleData for them. Without a bound, this loop would
      // then spin on INFO_TRY_AGAIN_LATER forever: nothing here ever
      // throws in that case, so the AsyncFunction promise on the JS side
      // never resolves *or* rejects — convertAndTrimAudio just hangs
      // silently with no error, no matter how long the caller awaits it.
      //
      // We track wall-clock time since the last time anything actually
      // moved (an input buffer was queued, an output buffer was
      // dequeued, or the format changed) and bail out with a real,
      // catchable error if too long passes with zero forward progress.
      // This does not cap total conversion time for legitimately long or
      // slow-to-decode files — only the case where the codec has
      // produced literally nothing for maxIdleMs.
      // --------------------------------------------------------------

      var lastProgressElapsedMs = SystemClock.elapsedRealtime()
      val maxIdleMs = 30_000L

      while (!outputEos) {
        var madeProgress = false

        // --------------------------------------------------------------
        // Feed decoder
        // --------------------------------------------------------------

        if (!inputEos) {
          val inputIndex =
            codec.dequeueInputBuffer(
              maxInputBufferWaitUs
            )

          if (inputIndex >= 0) {
            madeProgress = true

            val inputBuffer =
              codec.getInputBuffer(inputIndex)
                ?: throw ConversionFailedException(
                  "Decoder input buffer is unavailable."
                )

            inputBuffer.clear()

            val sampleSize =
              extractor.readSampleData(
                inputBuffer,
                0
              )

            if (sampleSize < 0) {
              codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
              )

              inputEos = true
            } else {
              val presentationTimeUs =
                extractor.sampleTime

              codec.queueInputBuffer(
                inputIndex,
                0,
                sampleSize,
                max(0L, presentationTimeUs),
                0
              )

              extractor.advance()
            }
          }
        }

        // --------------------------------------------------------------
        // Receive decoded PCM
        // --------------------------------------------------------------

        when (
          val outputIndex =
            codec.dequeueOutputBuffer(
              bufferInfo,
              maxOutputBufferWaitUs
            )
        ) {
          MediaCodec.INFO_TRY_AGAIN_LATER -> {
            // Continue feeding/draining the codec.
          }

          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            madeProgress = true

            inputFormat =
              codec.outputFormat

            val newSourceSampleRate =
              inputFormat.getInteger(
                MediaFormat.KEY_SAMPLE_RATE
              )

            val newSourceChannels =
              inputFormat.getInteger(
                MediaFormat.KEY_CHANNEL_COUNT
              )

            val newPcmEncoding =
              if (
                inputFormat.containsKey(
                  MediaFormat.KEY_PCM_ENCODING
                )
              ) {
                inputFormat.getInteger(
                  MediaFormat.KEY_PCM_ENCODING
                )
              } else {
                AudioFormat.ENCODING_PCM_16BIT
              }

            validatePcmEncoding(
              newPcmEncoding
            )

            state.onOutputFormatChanged(
              newSourceSampleRate = newSourceSampleRate,
              newSourceChannels = newSourceChannels,
              newPcmEncoding = newPcmEncoding,
              writer = writer
            )
          }

          else -> {
            if (outputIndex >= 0) {
              madeProgress = true

              try {
                if (bufferInfo.size > 0) {
                  val presentationTimeUs =
                    bufferInfo.presentationTimeUs

                  val outputBuffer =
                    codec.getOutputBuffer(
                      outputIndex
                    )

                  if (outputBuffer != null) {
                    outputBuffer.position(
                      bufferInfo.offset
                    )

                    outputBuffer.limit(
                      bufferInfo.offset +
                        bufferInfo.size
                    )

                    val pcmBuffer =
                      outputBuffer
                        .slice()
                        .order(
                          ByteOrder.LITTLE_ENDIAN
                        )

                    decodePcmBuffer(
                      buffer = pcmBuffer,
                      presentationTimeUs =
                        presentationTimeUs,
                      startUs = startUs,
                      endUs = endUs,
                      state = state,
                      writer = writer
                    )
                  }
                }

                if (
                  (
                    bufferInfo.flags and
                      MediaCodec.BUFFER_FLAG_END_OF_STREAM
                  ) != 0
                ) {
                  outputEos = true
                }

                if (
                  endUs != Long.MAX_VALUE &&
                  bufferInfo.presentationTimeUs >= endUs
                ) {
                  outputEos = true
                }
              } finally {
                codec.releaseOutputBuffer(
                  outputIndex,
                  false
                )
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

      // --------------------------------------------------------------
      // Flush resampler tails
      // --------------------------------------------------------------

      flushResamplers(
        state.resamplers,
        state.targetFrame,
        writer
      )

      writer.finish()

      if (writer.totalBytesWritten <= 0) {
        throw EmptyOutputException()
      }

      writeWavHeader(
        raf = raf,
        dataBytes = writer.totalBytesWritten,
        sampleRate =
          options.sampleRate.roundToInt(),
        channels = options.channels,
        bitsPerSample = options.bitDepth
      )

      raf.fd.sync()

      conversionSucceeded = true

      return Uri
        .fromFile(outputFile)
        .toString()
    } catch (e: ConversionException) {
      throw e
    } catch (e: Exception) {
      throw ConversionFailedException(
        e.message ?: e.toString()
      )
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
        raf?.close()
      } catch (_: Exception) {
      }

      if (
        !conversionSucceeded &&
        options.cleanupOnFailure
      ) {
        try {
          outputFile.delete()
        } catch (_: Exception) {
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // PCM decoding
  // --------------------------------------------------------------------------

  private fun decodePcmBuffer(
    buffer: ByteBuffer,
    presentationTimeUs: Long,
    startUs: Long,
    endUs: Long,
    state: ConversionCallState,
    writer: WavPcmWriter
  ) {
    val pcmEncoding = state.pcmEncoding
    val sourceSampleRate = state.sourceSampleRate
    val sourceChannels = state.sourceChannels
    val sourceFrame = state.sourceFrame
    val targetFrame = state.targetFrame
    val resamplers = state.resamplers

    validatePcmEncoding(pcmEncoding)

    if (sourceSampleRate <= 0) {
      throw ConversionFailedException(
        "Invalid source sample rate."
      )
    }

    if (sourceChannels <= 0) {
      throw ConversionFailedException(
        "Invalid source channel count."
      )
    }

    val bytesPerSample =
      if (
        pcmEncoding ==
        AudioFormat.ENCODING_PCM_FLOAT
      ) {
        4
      } else {
        2
      }

    val bytesPerFrame =
      sourceChannels * bytesPerSample

    val availableFrames =
      buffer.remaining() / bytesPerFrame

    for (frameIndex in 0 until availableFrames) {
      val frameTimeUs =
        presentationTimeUs +
          (
            frameIndex.toLong() *
              1_000_000L /
              sourceSampleRate
            )

      if (frameTimeUs < startUs) {
        skipSourceFrame(
          buffer,
          sourceChannels,
          pcmEncoding
        )
        continue
      }

      if (
        endUs != Long.MAX_VALUE &&
        frameTimeUs >= endUs
      ) {
        break
      }

      // ------------------------------------------------------------
      // Decode source channels
      // ------------------------------------------------------------

      for (channel in 0 until sourceChannels) {
        sourceFrame[channel] =
          readPcmSample(
            buffer,
            pcmEncoding
          )
      }

      // ------------------------------------------------------------
      // Channel conversion
      // ------------------------------------------------------------

      mixChannels(
        source = sourceFrame,
        sourceChannels = sourceChannels,
        target = targetFrame,
        targetChannels = resamplers.size
      )

      // ------------------------------------------------------------
      // Resample each target channel
      // ------------------------------------------------------------

      if (resamplers.size == 1) {
        resamplers[0].process(
          targetFrame[0]
        ) { sample ->
          targetFrame[0] = sample
          writer.writeFrame(targetFrame)
        }
      } else {
        // Reusable per-channel scratch buffers (unboxed floats), owned by
        // the call-scoped state so nothing is allocated per frame here.
        val pendingBuffers =
          state.pendingChannelBuffers

        for (channel in resamplers.indices) {
          pendingBuffers[channel].clear()
        }

        for (channel in resamplers.indices) {
          resamplers[channel].process(
            targetFrame[channel]
          ) { sample ->
            pendingBuffers[channel].add(sample)
          }
        }

        val emitted =
          pendingBuffers[0].size

        for (channel in 1 until pendingBuffers.size) {
          if (pendingBuffers[channel].size != emitted) {
            throw ConversionFailedException(
              "Resampler channel output became misaligned."
            )
          }
        }

        for (i in 0 until emitted) {
          for (channel in resamplers.indices) {
            targetFrame[channel] =
              pendingBuffers[channel].values[i]
          }

          writer.writeFrame(targetFrame)
        }
      }
    }
  }

  /**
   * Skips exactly one source frame.
   *
   * This is only used when the decoded frame timestamp is before startUs.
   */
  private fun skipSourceFrame(
    buffer: ByteBuffer,
    sourceChannels: Int,
    pcmEncoding: Int
  ) {
    val bytesPerSample =
      if (
        pcmEncoding ==
        AudioFormat.ENCODING_PCM_FLOAT
      ) {
        4
      } else {
        2
      }

    val bytes =
      sourceChannels * bytesPerSample

    if (buffer.remaining() >= bytes) {
      buffer.position(
        buffer.position() + bytes
      )
    }
  }

  private fun readPcmSample(
    buffer: ByteBuffer,
    pcmEncoding: Int
  ): Float {
    return when (pcmEncoding) {
      AudioFormat.ENCODING_PCM_16BIT -> {
        buffer
          .short
          .toInt()
          .coerceIn(
            Short.MIN_VALUE.toInt(),
            Short.MAX_VALUE.toInt()
          ) / 32768.0f
      }

      AudioFormat.ENCODING_PCM_FLOAT -> {
        buffer.float.coerceIn(
          -1.0f,
          1.0f
        )
      }

      else -> {
        throw UnsupportedPcmEncodingException()
      }
    }
  }

  // --------------------------------------------------------------------------
  // Channel conversion
  // --------------------------------------------------------------------------

  private fun mixChannels(
    source: FloatArray,
    sourceChannels: Int,
    target: FloatArray,
    targetChannels: Int
  ) {
    when {
      sourceChannels == targetChannels -> {
        System.arraycopy(
          source,
          0,
          target,
          0,
          targetChannels
        )
      }

      targetChannels == 1 -> {
        var sum = 0.0

        for (channel in 0 until sourceChannels) {
          sum += source[channel]
        }

        target[0] =
          (sum / sourceChannels)
            .coerceIn(-1.0, 1.0)
            .toFloat()
      }

      sourceChannels == 1 -> {
        for (channel in 0 until targetChannels) {
          target[channel] = source[0]
        }
      }

      else -> {
        // General-purpose down/up mix.
        //
        // Each target channel receives an averaged group of source
        // channels. This is deterministic and avoids simply selecting
        // one source channel.
        for (targetChannel in 0 until targetChannels) {
          val start =
            targetChannel *
              sourceChannels /
              targetChannels

          val end =
            max(
              start + 1,
              (targetChannel + 1) *
                sourceChannels /
                targetChannels
            )

          var sum = 0.0

          for (sourceChannel in start until end) {
            sum += source[sourceChannel]
          }

          target[targetChannel] =
            (
              sum /
                (end - start)
              )
              .coerceIn(-1.0, 1.0)
              .toFloat()
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Resampler helpers
  // --------------------------------------------------------------------------

  private fun createResamplers(
    sourceRate: Int,
    options: WhisperAudioConversionOptions
  ): Array<SincResampler> {
    if (sourceRate <= 0) {
      throw ConversionFailedException(
        "Invalid source sample rate."
      )
    }

    return Array(options.channels) {
      SincResampler(
        inputRate = sourceRate.toDouble(),
        outputRate = options.sampleRate
      )
    }
  }

  private fun flushResamplers(
    resamplers: Array<SincResampler>,
    frame: FloatArray,
    writer: WavPcmWriter
  ) {
    if (resamplers.size == 1) {
      resamplers[0].flush { sample ->
        frame[0] = sample
        writer.writeFrame(frame)
      }

      return
    }

    val pending =
      Array(resamplers.size) {
        GrowableFloatBuffer()
      }

    for (channel in resamplers.indices) {
      resamplers[channel].flush { sample ->
        pending[channel].add(sample)
      }
    }

    val count =
      pending[0].size

    for (channel in 1 until pending.size) {
      if (pending[channel].size != count) {
        throw ConversionFailedException(
          "Resampler flush produced misaligned channels."
        )
      }
    }

    for (i in 0 until count) {
      for (channel in resamplers.indices) {
        frame[channel] =
          pending[channel].values[i]
      }

      writer.writeFrame(frame)
    }
  }

  // --------------------------------------------------------------------------
  // Input handling
  // --------------------------------------------------------------------------

  private fun findAudioTrack(
    extractor: MediaExtractor
  ): Int {
    for (index in 0 until extractor.trackCount) {
      val format =
        extractor.getTrackFormat(index)

      val mime =
        format.getString(
          MediaFormat.KEY_MIME
        )
          ?: continue

      if (mime.startsWith("audio/")) {
        return index
      }
    }

    return -1
  }

  private fun openDataSource(
    extractor: MediaExtractor,
    uri: Uri
  ) {
    try {
      when (uri.scheme?.lowercase()) {
        "content" -> {
          val descriptor =
            context.contentResolver
              .openFileDescriptor(uri, "r")
              ?: throw InputFileNotFoundException()

          descriptor.use {
            extractor.setDataSource(
              it.fileDescriptor
            )
          }
        }

        "file" -> {
          val path =
            uri.path
              ?: throw InvalidInputPathException()

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
            throw InputFileNotReadableException(
              "Permission denied."
            )
          }

          extractor.setDataSource(
            file.absolutePath
          )
        }

        null,
        "" -> {
          val file =
            File(uri.path ?: uri.toString())

          if (!file.exists()) {
            throw InputFileNotFoundException()
          }

          extractor.setDataSource(
            file.absolutePath
          )
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
      throw InputFileNotReadableException(
        e.message ?: "Unknown error."
      )
    }
  }

  private fun resolveInputUri(
    path: String
  ): Uri {
    return when {
      path.startsWith(
        "content://",
        ignoreCase = true
      ) -> Uri.parse(path)

      path.startsWith(
        "file://",
        ignoreCase = true
      ) -> Uri.parse(path)

      else -> {
        Uri.fromFile(
          File(path)
        )
      }
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

    if (
      !startOffset.isFinite() ||
      startOffset < 0.0
    ) {
      throw InvalidStartOffsetException()
    }

    if (
      !endOffset.isFinite() ||
      endOffset < 0.0
    ) {
      throw InvalidEndOffsetException()
    }

    if (
      options.sampleRate < 8_000.0 ||
      options.sampleRate > 192_000.0
    ) {
      throw UnsupportedSampleRateException()
    }

    if (
      options.channels !in 1..8
    ) {
      throw UnsupportedChannelCountException()
    }

    if (
      options.bitDepth != 16 &&
      options.bitDepth != 32
    ) {
      throw UnsupportedBitDepthException()
    }

    if (
      options.bufferSize < 512 ||
      options.bufferSize > 65_536
    ) {
      throw InvalidBufferSizeException()
    }

    if (
      endOffset > 0.0 &&
      endOffset <= startOffset
    ) {
      throw InvalidRangeException()
    }
  }

  private fun validatePcmEncoding(
    encoding: Int
  ) {
    if (
      encoding != AudioFormat.ENCODING_PCM_16BIT &&
      encoding != AudioFormat.ENCODING_PCM_FLOAT
    ) {
      throw UnsupportedPcmEncodingException()
    }
  }

  private fun secondsToMicroseconds(
    seconds: Double
  ): Long {
    if (!seconds.isFinite() || seconds < 0.0) {
      throw InvalidRangeException()
    }

    val value =
      seconds * 1_000_000.0

    if (
      value > Long.MAX_VALUE.toDouble()
    ) {
      throw InvalidRangeException()
    }

    return value.roundToLong()
  }

  private fun Double.roundToLong(): Long {
    return roundToIntSafe()
  }

  private fun Double.roundToIntSafe(): Long {
    return if (this >= 0.0) {
      floor(this + 0.5).toLong()
    } else {
      ceil(this - 0.5).toLong()
    }
  }
}