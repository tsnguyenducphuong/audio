import WhisperAudioConverterModule from './WhisperAudioConverterModule';
import {
  WhisperAudioConversionOptions,
  WhisperAudioConversionError,
} from './WhisperAudioConverter.types';

/**
 * Human-readable messages for each native error code.
 * Keep this in sync with WhisperAudioConversionError.
 */
const ERROR_MESSAGES: Record<WhisperAudioConversionError, string> = {
  [WhisperAudioConversionError.invalidInputPath]:
    'The input path is invalid or malformed.',
  [WhisperAudioConversionError.inputFileNotFound]:
    'The input audio file could not be found.',
  [WhisperAudioConversionError.inputFileNotReadable]:
    'The input audio file exists but could not be read (check permissions).',
  [WhisperAudioConversionError.invalidStartOffset]:
    'The start offset is invalid (it may be negative or beyond the file duration).',
  [WhisperAudioConversionError.invalidEndOffset]:
    'The end offset is invalid (it may be negative or beyond the file duration).',
  [WhisperAudioConversionError.invalidRange]:
    'The start offset must be less than the end offset.',
  [WhisperAudioConversionError.unsupportedSampleRate]:
    'The requested sample rate is not supported.',
  [WhisperAudioConversionError.unsupportedChannelCount]:
    'The requested channel count is not supported.',
  [WhisperAudioConversionError.unsupportedBitDepth]:
    'The requested bit depth is not supported.',
  [WhisperAudioConversionError.invalidBufferSize]:
    'The requested buffer size is invalid.',
  [WhisperAudioConversionError.outputFormatCreationFailed]:
    'Failed to create the output audio format internally.',
  [WhisperAudioConversionError.converterCreationFailed]:
    'Failed to initialize the internal audio converter.',
  [WhisperAudioConversionError.inputBufferCreationFailed]:
    'Failed to allocate an input audio buffer.',
  [WhisperAudioConversionError.outputBufferCreationFailed]:
    'Failed to allocate an output audio buffer.',
  [WhisperAudioConversionError.inputReadFailed]:
    'Failed while reading from the input audio file.',
  [WhisperAudioConversionError.converterFailed]:
    'The internal audio conversion process failed.',
  [WhisperAudioConversionError.outputWriteFailed]:
    'Failed while writing the converted output file.',
  [WhisperAudioConversionError.outputTooLarge]:
    'The converted output file exceeds the allowed size limit.',
  [WhisperAudioConversionError.emptyOutput]:
    'The conversion produced an empty output file.',
};

/** Errors caused by bad caller input — worth surfacing distinctly in UI. */
const VALIDATION_ERRORS = new Set<WhisperAudioConversionError>([
  WhisperAudioConversionError.invalidInputPath,
  WhisperAudioConversionError.inputFileNotFound,
  WhisperAudioConversionError.inputFileNotReadable,
  WhisperAudioConversionError.invalidStartOffset,
  WhisperAudioConversionError.invalidEndOffset,
  WhisperAudioConversionError.invalidRange,
  WhisperAudioConversionError.unsupportedSampleRate,
  WhisperAudioConversionError.unsupportedChannelCount,
  WhisperAudioConversionError.unsupportedBitDepth,
  WhisperAudioConversionError.invalidBufferSize,
]);

export type WhisperAudioConversionResult =
  | { success: true; outputPath: string }
  | {
      success: false;
      code: WhisperAudioConversionError | 'unknown';
      message: string;
      isValidationError: boolean;
      originalError: unknown;
    };

/**
 * Attempts to extract a WhisperAudioConversionError code from whatever
 * shape the native module throws. Expo modules commonly surface native
 * errors as `{ code, message }`, but we defensively check a few shapes.
 */
function extractErrorCode(error: unknown): WhisperAudioConversionError | 'unknown' {
  if (error && typeof error === 'object') {
    const maybeCode = (error as any).code;
    if (
      typeof maybeCode === 'string' &&
      (Object.values(WhisperAudioConversionError) as string[]).includes(maybeCode)
    ) {
      return maybeCode as WhisperAudioConversionError;
    }

    // Fallback: some native layers embed the code in the message string.
    const message = (error as any).message;
    if (typeof message === 'string') {
      const match = (Object.values(WhisperAudioConversionError) as string[]).find(
        (code) => message.includes(code)
      );
      if (match) return match as WhisperAudioConversionError;
    }
  }
  return 'unknown';
}

/**
 * Trims and converts an audio file for Whisper-compatible input.
 *
 * Always resolves (never throws) — inspect `result.success` to branch.
 * This makes it safe to call directly from UI code without try/catch.
 */
export async function trimAndConvertAudio(
  inputUri: string,
  startOffset: number,
  endOffset: number,
  options?: Partial<WhisperAudioConversionOptions>
): Promise<WhisperAudioConversionResult> {
  const resolvedOptions: WhisperAudioConversionOptions = {
    sampleRate: 16000,
    channels: 1,
    bitDepth: 16,
    bufferSize: 4096,
    cleanupOnFailure: true,
    ...options,
  };

  // Basic pre-flight validation on the JS side, so we can fail fast with
  // a clear message before ever calling into native code.
  if (!inputUri || typeof inputUri !== 'string') {
    return {
      success: false,
      code: WhisperAudioConversionError.invalidInputPath,
      message: ERROR_MESSAGES[WhisperAudioConversionError.invalidInputPath],
      isValidationError: true,
      originalError: null,
    };
  }

  if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
    return {
      success: false,
      code: WhisperAudioConversionError.invalidRange,
      message: ERROR_MESSAGES[WhisperAudioConversionError.invalidRange],
      isValidationError: true,
      originalError: null,
    };
  }

  try {
    const inputPath = inputUri.replace('file://', '');

    const outputPath = await WhisperAudioConverterModule.convertAndTrimAudio(
      inputPath,
      startOffset,
      endOffset,
      resolvedOptions
    );

    if (!outputPath) {
      return {
        success: false,
        code: WhisperAudioConversionError.emptyOutput,
        message: ERROR_MESSAGES[WhisperAudioConversionError.emptyOutput],
        isValidationError: false,
        originalError: null,
      };
    }

    return { success: true, outputPath };
  } catch (error) {
    const code = extractErrorCode(error);
    const message =
      code !== 'unknown'
        ? ERROR_MESSAGES[code]
        : error instanceof Error
        ? error.message
        : 'An unknown error occurred during audio conversion.';

    return {
      success: false,
      code,
      message,
      isValidationError: code !== 'unknown' && VALIDATION_ERRORS.has(code),
      originalError: error,
    };
  }
}
