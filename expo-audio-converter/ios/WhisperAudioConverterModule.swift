import Foundation
import AVFoundation
import ExpoModulesCore

// ============================================================================
// This file mirrors expo-modules-core's Android implementation
// (WhisperAudioConverter.kt) as closely as the two platforms' native audio
// APIs allow. Where Android has to hand-roll a decode loop, a windowed-sinc
// resampler, and a manual WAV writer (because MediaCodec/AudioFormat give you
// none of that for free), iOS gets equivalent behavior "for free" from
// AVAudioFile + AVAudioConverter (Accelerate-backed, high quality resampling
// via `.max` quality + the mastering algorithm). What *was* missing here,
// and has been ported over from the Kotlin side, is the defensive plumbing
// around that: stricter input validation, sample sanitization before integer
// PCM conversion, an explicit WAV size ceiling, detailed error reasons, and
// autorelease-pool scoping for long streaming conversions.
// ============================================================================

// MARK: - Configuration

/// Defaults are chosen to match what Whisper (and whisper.cpp) expect out of the box:
/// 16 kHz, mono, 16-bit PCM WAV. Increase sampleRate/channels/bitDepth if you need a
/// higher-fidelity intermediate file for some other purpose.
public struct WhisperAudioConversionOptions: Record {
  @Field public var sampleRate: Double = 16_000
  @Field public var channels: Int = 1
  @Field public var bitDepth: Int = 16          // 16 = Int16 PCM, 32 = Int32 PCM (not Float32)
  @Field public var bufferSize: Int = 8192
  @Field public var cleanupOnFailure: Bool = true

  public init() {}
}

// MARK: - Errors

/// Conforms to `CodedError` (in addition to `LocalizedError`) so the `code`
/// below actually reaches the JS side as `err.code`, matching the behavior
/// of `CodedException` on the Kotlin side. Without this conformance the
/// `code` property here would be inert.
private enum WhisperAudioConversionError: CodedError, LocalizedError {
  case invalidInputPath
  case inputFileNotFound
  case inputFileNotReadable(String)
  case invalidStartOffset
  case invalidEndOffset
  case invalidRange
  case unsupportedSampleRate
  case unsupportedChannelCount
  case unsupportedBitDepth
  case invalidBufferSize
  case outputFormatCreationFailed
  case converterCreationFailed
  case inputBufferCreationFailed
  case outputBufferCreationFailed
  case inputReadFailed
  case converterFailed(String)
  case outputWriteFailed
  case outputTooLarge
  case emptyOutput

  var errorDescription: String? {
    switch self {
    case .invalidInputPath: return "The input audio path is invalid or empty."
    case .inputFileNotFound: return "The input audio file does not exist."
    case .inputFileNotReadable(let detail): return "The input audio file cannot be read: \(detail)"
    case .invalidStartOffset: return "startOffset must be finite and >= 0."
    case .invalidEndOffset: return "endOffset must be finite, >= 0, and either 0 (meaning \"to end of file\") or > startOffset."
    case .invalidRange: return "The requested audio range is empty or could not be read from the file."
    case .unsupportedSampleRate: return "Requested sample rate must be between 8,000 Hz and 192,000 Hz."
    case .unsupportedChannelCount: return "Requested channel count must be between 1 and 8."
    case .unsupportedBitDepth: return "Only 16-bit and 32-bit integer PCM output are supported."
    case .invalidBufferSize: return "Buffer size must be between 512 and 65,536 frames."
    case .outputFormatCreationFailed: return "Failed to create requested output audio format."
    case .converterCreationFailed: return "Failed to create audio converter."
    case .inputBufferCreationFailed: return "Failed to allocate input audio buffer."
    case .outputBufferCreationFailed: return "Failed to allocate output audio buffer."
    case .inputReadFailed: return "Failed to read input audio file."
    case .converterFailed(let msg): return "Audio conversion failed: \(msg)"
    case .outputWriteFailed: return "Failed to write output WAV file."
    case .outputTooLarge: return "Conversion aborted: output would exceed the 4 GiB WAV/RIFF data size limit."
    case .emptyOutput: return "Conversion produced an empty audio file (check startOffset/endOffset against the actual file duration)."
    }
  }

  var code: String {
    switch self {
    case .invalidInputPath: return "ERR_INVALID_INPUT_PATH"
    case .inputFileNotFound: return "ERR_INPUT_NOT_FOUND"
    case .inputFileNotReadable: return "ERR_INPUT_NOT_READABLE"
    case .invalidStartOffset: return "ERR_INVALID_START_OFFSET"
    case .invalidEndOffset: return "ERR_INVALID_END_OFFSET"
    case .invalidRange: return "ERR_INVALID_RANGE"
    case .unsupportedSampleRate: return "ERR_UNSUPPORTED_SAMPLE_RATE"
    case .unsupportedChannelCount: return "ERR_UNSUPPORTED_CHANNELS"
    case .unsupportedBitDepth: return "ERR_UNSUPPORTED_BIT_DEPTH"
    case .invalidBufferSize: return "ERR_INVALID_BUFFER_SIZE"
    case .outputFormatCreationFailed: return "ERR_OUTPUT_FORMAT"
    case .converterCreationFailed: return "ERR_CONVERTER"
    case .inputBufferCreationFailed: return "ERR_INPUT_BUFFER"
    case .outputBufferCreationFailed: return "ERR_OUTPUT_BUFFER"
    case .inputReadFailed: return "ERR_INPUT_READ"
    case .converterFailed: return "ERR_CONVERSION"
    case .outputWriteFailed: return "ERR_OUTPUT_WRITE"
    case .outputTooLarge: return "ERR_OUTPUT_TOO_LARGE"
    case .emptyOutput: return "ERR_EMPTY_OUTPUT"
    }
  }
}

// MARK: - Converter Module

public final class WhisperAudioConverterModule: Module {

  public func definition() -> ModuleDefinition {
    Name("WhisperAudioConverter")

    AsyncFunction("setValueAsync") { (value: String) in
    }

    // performConversion is a synchronous, potentially long-running (tens
    // of seconds) AVAudioConverter read/convert/write loop. Returning it
    // directly from a synchronous AsyncFunction closure would run that
    // entire computation on whatever thread Expo dispatches AsyncFunction
    // bodies to by default -- which, while off the JS thread, can still
    // be a queue shared with every other AsyncFunction call in the app,
    // stalling unrelated native calls for as long as conversion takes.
    //
    // Mirrors the pattern used elsewhere in this app (see
    // removeBackgroundAsync): declare the closure `async throws`, and
    // explicitly hop onto our own DispatchQueue.global(qos: .userInitiated)
    // via a checked continuation, so the heavy work runs on a dedicated
    // background thread rather than Expo's own dispatch queue.
    AsyncFunction("convertAndTrimAudio") {
      (
        inputPath: String,
        startOffset: Double,
        endOffset: Double,
        options: WhisperAudioConversionOptions
      ) async throws -> String in

      return try await withCheckedThrowingContinuation { continuation in
        DispatchQueue.global(qos: .userInitiated).async {
          do {
            let result = try self.performConversion(
              inputPath: inputPath,
              startOffset: startOffset,
              endOffset: endOffset,
              options: options
            )
            continuation.resume(returning: result)
          } catch {
            continuation.resume(throwing: error)
          }
        }
      }
    }
  }

  // MARK: Main Conversion Logic

  private func performConversion(
    inputPath: String,
    startOffset: Double,
    endOffset: Double,
    options: WhisperAudioConversionOptions
  ) throws -> String {

    // 1. Validate options
    let trimmedPath = inputPath.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmedPath.isEmpty else {
      throw WhisperAudioConversionError.invalidInputPath
    }
    guard startOffset.isFinite, startOffset >= 0 else {
      throw WhisperAudioConversionError.invalidStartOffset
    }
    guard endOffset.isFinite, endOffset >= 0 else {
      throw WhisperAudioConversionError.invalidEndOffset
    }
    guard options.sampleRate >= 8_000, options.sampleRate <= 192_000 else {
      throw WhisperAudioConversionError.unsupportedSampleRate
    }
    guard options.channels >= 1, options.channels <= 8 else {
      throw WhisperAudioConversionError.unsupportedChannelCount
    }
    guard options.bitDepth == 16 || options.bitDepth == 32 else {
      throw WhisperAudioConversionError.unsupportedBitDepth
    }
    guard options.bufferSize >= 512, options.bufferSize <= 65_536 else {
      throw WhisperAudioConversionError.invalidBufferSize
    }
    // endOffset == 0 means "to end of file"; any other endOffset must sit
    // strictly after startOffset. Checked up front (mirrors the Kotlin
    // validateArguments range check) so we fail fast before touching disk.
    if endOffset > 0, endOffset <= startOffset {
      throw WhisperAudioConversionError.invalidRange
    }

    // 2. Resolve input URL robustly
    let inputURL: URL
    if trimmedPath.hasPrefix("file://") {
      let cleanPath = String(trimmedPath.dropFirst(7)).removingPercentEncoding ?? String(trimmedPath.dropFirst(7))
      guard !cleanPath.isEmpty else {
        throw WhisperAudioConversionError.invalidInputPath
      }
      inputURL = URL(fileURLWithPath: cleanPath)
    } else {
      inputURL = URL(fileURLWithPath: trimmedPath)
    }

    // Some file/document pickers (notably iCloud-backed or "open in place"
    // providers that were *not* copied into this app's own sandbox, e.g.
    // copyToCacheDirectory: false) hand back a security-scoped URL that
    // requires an explicit access grant before it can be opened. This is a
    // no-op (returns false) for a normal sandbox-local file such as the
    // cache-directory copy Expo's document picker produces by default, so
    // it's safe to call unconditionally rather than only when needed.
    let didStartAccessingSecurityScopedResource = inputURL.startAccessingSecurityScopedResource()
    defer {
      if didStartAccessingSecurityScopedResource {
        inputURL.stopAccessingSecurityScopedResource()
      }
    }

    // Check existence, directory-ness, and read permission separately so the
    // thrown error actually explains what's wrong (mirrors the Kotlin
    // openDataSource file-scheme branch, which distinguishes "not found",
    // "not a regular file", and "permission denied").
    var isDirectory: ObjCBool = false
    guard FileManager.default.fileExists(atPath: inputURL.path, isDirectory: &isDirectory) else {
      throw WhisperAudioConversionError.inputFileNotFound
    }
    guard !isDirectory.boolValue else {
      throw WhisperAudioConversionError.inputFileNotReadable("The input path is a directory, not a file.")
    }
    guard FileManager.default.isReadableFile(atPath: inputURL.path) else {
      throw WhisperAudioConversionError.inputFileNotReadable("Permission denied.")
    }

    // 3. Open source file
    let inputFile: AVAudioFile
    do {
      inputFile = try AVAudioFile(forReading: inputURL)
    } catch {
      throw WhisperAudioConversionError.inputFileNotReadable(error.localizedDescription)
    }

    let inputProcessingFormat = inputFile.processingFormat
    let inputSampleRate = inputProcessingFormat.sampleRate
    guard inputSampleRate > 0, inputProcessingFormat.channelCount > 0 else {
      throw WhisperAudioConversionError.inputFileNotReadable("Invalid or unreadable source audio format.")
    }

    // 4. Calculate frame offsets.
    //
    // IMPORTANT: `inputFile.length` (used to be `totalFrames` here) is derived from
    // ExtAudioFile's frame-count estimate. For VBR-encoded MP3 in particular, this
    // estimate is frequently wrong (sometimes wildly) because it's often derived from
    // bitrate/header heuristics rather than an exact count. Previously this value was
    // used to clamp both startFrame and endFrame, which meant a file with an
    // underestimated length could either (a) throw a spurious "invalid range" error on
    // a perfectly valid trim request, or (b) silently truncate real audio before it was
    // ever read. We now treat the physical end-of-file (a read that returns 0 frames)
    // as the only authoritative stopping condition, and only use `inputFile.length` as
    // a coarse hint — never as a hard bound.
    let startFrame = max(0, AVAudioFramePosition((startOffset * inputSampleRate).rounded(.down)))

    let endFrame: AVAudioFramePosition
    if endOffset == 0 {
      // "Convert to end of file" — don't cap by the (possibly wrong) reported length;
      // let the read loop discover the real end via physical EOF.
      endFrame = AVAudioFramePosition.max
    } else {
      let requestedEnd = AVAudioFramePosition((endOffset * inputSampleRate).rounded(.down))
      guard requestedEnd > startFrame else {
        throw WhisperAudioConversionError.invalidRange
      }
      endFrame = requestedEnd
    }

    guard endFrame > startFrame else {
      throw WhisperAudioConversionError.invalidRange
    }

    var framesRemaining = endFrame - startFrame
    inputFile.framePosition = startFrame

    // 5. Setup Intermediate Processing Format (Float32 for resilient resampling)
    guard let intermediateFormat = AVAudioFormat(
      commonFormat: .pcmFormatFloat32,
      sampleRate: options.sampleRate,
      channels: AVAudioChannelCount(options.channels),
      interleaved: false
    ) else {
      throw WhisperAudioConversionError.outputFormatCreationFailed
    }

    // 6. Setup Converter
    guard let converter = AVAudioConverter(from: inputProcessingFormat, to: intermediateFormat) else {
      throw WhisperAudioConversionError.converterCreationFailed
    }
    // This is an offline, one-shot conversion, so bias toward maximum quality resampling
    // rather than the default (which favors lower latency / realtime use). This is the
    // Accelerate-backed equivalent of the windowed-sinc resampler the Android side has
    // to implement by hand.
    converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue
    converter.sampleRateConverterAlgorithm = AVSampleRateConverterAlgorithm_Mastering

    // 7. Prepare Output WAV File Settings
    let outputURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("whisper-\(UUID().uuidString).wav")

    let fileSettings: [String: Any] = [
      AVFormatIDKey: kAudioFormatLinearPCM,
      AVSampleRateKey: options.sampleRate,
      AVNumberOfChannelsKey: options.channels,
      AVLinearPCMBitDepthKey: options.bitDepth,
      AVLinearPCMIsFloatKey: false,
      AVLinearPCMIsBigEndianKey: false,
      AVLinearPCMIsNonInterleaved: false // WAV files must be interleaved on disk
    ]

    let outputFile: AVAudioFile
    do {
      try? FileManager.default.removeItem(at: outputURL)
      outputFile = try AVAudioFile(
        forWriting: outputURL,
        settings: fileSettings,
        commonFormat: .pcmFormatFloat32,
        interleaved: false
      )
    } catch {
      throw WhisperAudioConversionError.outputFormatCreationFailed
    }

    // Clean up the temp output file on ANY failure past this point, not just when it's
    // still zero-length. Previously this only fired when `outputFile.length == 0`, so a
    // failure that occurred after some chunks had already been written (e.g. the
    // converter throwing mid-stream) would leak a partially-written file in the temp
    // directory forever.
    var conversionSucceeded = false
    defer {
      if !conversionSucceeded, options.cleanupOnFailure {
        try? FileManager.default.removeItem(at: outputURL)
      }
    }

    // 8. Allocate Buffers
    let inputCapacity = AVAudioFrameCount(options.bufferSize)
    guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: inputProcessingFormat, frameCapacity: inputCapacity) else {
      throw WhisperAudioConversionError.inputBufferCreationFailed
    }

    let ratio = options.sampleRate / inputSampleRate
    let outputCapacity = max(1024, AVAudioFrameCount(ceil(Double(options.bufferSize) * ratio) + 1024))
    guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: intermediateFormat, frameCapacity: outputCapacity) else {
      throw WhisperAudioConversionError.outputBufferCreationFailed
    }

    // Classic WAV (RIFF) chunk sizes are 32-bit, capping data at 4 GiB. Rather than
    // silently emitting a corrupt/truncated header past that point (as a naive writer
    // would), track bytes written and fail loudly and early. Mirrors the Kotlin
    // WavPcmWriter/writeWavHeader 0xFFFFFFFF guards.
    let bytesPerSample = options.bitDepth / 8
    let frameByteSize = options.channels * bytesPerSample
    let maxWavDataBytes: UInt64 = 0xFFFF_FFFF

    func ensureOutputSizeWithinLimits() throws {
      let totalBytes = UInt64(outputFile.length) * UInt64(frameByteSize)
      if totalBytes > maxWavDataBytes {
        throw WhisperAudioConversionError.outputTooLarge
      }
    }

    // 9. Processing Loop
    // Note framesRemaining may be astronomically large (endOffset == 0 case) — that's
    // fine, the loop's real terminal condition is `readFrames > 0` (physical EOF), not
    // framesRemaining reaching zero.
    while framesRemaining > 0 {
      // Scope each outer iteration's autorelease pool explicitly. On a long
      // conversion, AVFoundation/Accelerate can create short-lived
      // Objective-C temporaries (e.g. inside `read(into:)` / `convert`)
      // that would otherwise only get released when the thread's own
      // top-level pool eventually drains, letting memory grow with the
      // length of the file being converted. Draining per-iteration keeps
      // peak memory roughly constant regardless of input duration.
      try autoreleasepool {
        let framesToRead = min(framesRemaining, AVAudioFramePosition(inputCapacity))
        inputBuffer.frameLength = 0

        do {
          try inputFile.read(into: inputBuffer, frameCount: AVAudioFrameCount(framesToRead))
        } catch {
          throw WhisperAudioConversionError.inputReadFailed
        }

        let readFrames = AVAudioFramePosition(inputBuffer.frameLength)
        guard readFrames > 0 else {
          framesRemaining = 0 // physical end-of-file reached
          return
        }
        framesRemaining -= readFrames

        var supplied = false
        let inputBlock: AVAudioConverterInputBlock = { _, outStatus in
          if supplied {
            outStatus.pointee = .noDataNow
            return nil
          }
          supplied = true
          outStatus.pointee = .haveData
          return inputBuffer
        }

        while true {
          outputBuffer.frameLength = 0
          var conversionError: NSError?
          let status = converter.convert(to: outputBuffer, error: &conversionError, withInputFrom: inputBlock)

          if let conversionError {
            throw WhisperAudioConversionError.converterFailed(conversionError.localizedDescription)
          }

          if outputBuffer.frameLength > 0 {
            // Defensively clamp/sanitize before handing Float32 samples to
            // AVAudioFile for its internal conversion down to 16/32-bit
            // integer PCM. AVAudioConverter's own output should already be
            // well-formed, but this guards against NaN/Infinity reaching an
            // otherwise-undefined float-to-int conversion if an unusual or
            // corrupt source ever produces out-of-range samples — mirrors
            // WavPcmWriter.sanitizeSample on the Android side.
            sanitize(outputBuffer)
            do {
              try outputFile.write(from: outputBuffer)
            } catch {
              throw WhisperAudioConversionError.outputWriteFailed
            }
            try ensureOutputSizeWithinLimits()
          }

          if status == .inputRanDry || status == .endOfStream || outputBuffer.frameLength == 0 {
            break
          }
        }
      }
    }

    // 10. Drain Converter (Flush remaining resampler filter state)
    while true {
      outputBuffer.frameLength = 0
      var flushError: NSError?
      let status = converter.convert(to: outputBuffer, error: &flushError) { _, outStatus in
        outStatus.pointee = .endOfStream
        return nil
      }

      if let flushError {
        throw WhisperAudioConversionError.converterFailed(flushError.localizedDescription)
      }

      if outputBuffer.frameLength > 0 {
        sanitize(outputBuffer)
        do {
          try outputFile.write(from: outputBuffer)
        } catch {
          throw WhisperAudioConversionError.outputWriteFailed
        }
        try ensureOutputSizeWithinLimits()
      }

      if status == .endOfStream || outputBuffer.frameLength == 0 {
        break
      }
    }

    guard outputFile.length > 0 else {
      throw WhisperAudioConversionError.emptyOutput
    }

    conversionSucceeded = true
    return outputURL.absoluteString
  }

  /// Clamps every sample in `buffer` into [-1, 1] and replaces NaN with
  /// silence in place, operating only over the buffer's current
  /// `frameLength` (not its full capacity). Cheap relative to resampling
  /// and disk I/O, so it's applied unconditionally rather than only when a
  /// problem is detected.
  private func sanitize(_ buffer: AVAudioPCMBuffer) {
    guard let channelData = buffer.floatChannelData else { return }
    let frameLength = Int(buffer.frameLength)
    guard frameLength > 0 else { return }
    let channelCount = Int(buffer.format.channelCount)

    for channel in 0..<channelCount {
      let samples = channelData[channel]
      for frame in 0..<frameLength {
        let value = samples[frame]
        if value.isNaN {
          samples[frame] = 0
        } else if value > 1.0 {
          samples[frame] = 1.0
        } else if value < -1.0 {
          samples[frame] = -1.0
        }
      }
    }
  }
}
