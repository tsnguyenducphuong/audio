// Reexport the native module. On web, it will be resolved to WhisperAudioConverterModule.web.ts
// and on native platforms to WhisperAudioConverterModule.ts

import WhisperAudioConverterModule from './WhisperAudioConverterModule';
import {
  WhisperAudioConversionOptions,
  WhisperAudioConversionError,
} from './WhisperAudioConverter.types';

export async function trimAndConvertAudio4Whisper(
  inputUri: string,
  startOffset: number,
  endOffset: number
): Promise<string | null> {
  const options: WhisperAudioConversionOptions = {
    sampleRate: 16000,   // Whisper expects 16kHz
    channels: 1,         // mono
    bitDepth: 16,
    bufferSize: 4096,
    cleanupOnFailure: true,
  };

  try {
    // Strip the file:// prefix if your native module expects a plain path
    const inputPath = inputUri.replace('file://', '');

    const outputPath = await WhisperAudioConverterModule.convertAndTrimAudio(
      inputPath,
      startOffset,
      endOffset,
      options
    );

    console.log('Converted audio saved at:', outputPath);
    return outputPath;
  } catch (error: any) {
    // error.code should map to one of WhisperAudioConversionError's values
    const code = error?.code as WhisperAudioConversionError | undefined;

    switch (code) {
      case WhisperAudioConversionError.inputFileNotFound:
        console.error('Input file not found:', inputUri);
        break;
      case WhisperAudioConversionError.invalidRange:
        console.error('Invalid start/end offset range');
        break;
      case WhisperAudioConversionError.unsupportedSampleRate:
      case WhisperAudioConversionError.unsupportedChannelCount:
      case WhisperAudioConversionError.unsupportedBitDepth:
        console.error('Unsupported audio format option:', code);
        break;
      default:
        console.error('Audio conversion failed:', error);
    }

    return null;
  }
}

export {
  trimAndConvertAudio,
} from './WhisperAudioConverter.utils';
export type { WhisperAudioConversionResult } from './WhisperAudioConverter.utils';
 


export { default } from './WhisperAudioConverterModule';
export * from './WhisperAudioConverter.types';
