// Define your exported module types here.

import { NativeModule } from 'expo';

export type WhisperAudioConversionOptions = {
  sampleRate: number;
  channels: number;
  bitDepth: number;
  bufferSize: number;
  cleanupOnFailure: boolean;
};

export enum WhisperAudioConversionError {
  invalidInputPath = 'invalidInputPath',
  inputFileNotFound = 'inputFileNotFound',
  inputFileNotReadable = 'inputFileNotReadable',
  invalidStartOffset = 'invalidStartOffset',
  invalidEndOffset = 'invalidEndOffset',
  invalidRange = 'invalidRange',
  unsupportedSampleRate = 'unsupportedSampleRate',
  unsupportedChannelCount = 'unsupportedChannelCount',
  unsupportedBitDepth = 'unsupportedBitDepth',
  invalidBufferSize = 'invalidBufferSize',
  outputFormatCreationFailed = 'outputFormatCreationFailed',
  converterCreationFailed = 'converterCreationFailed',
  inputBufferCreationFailed = 'inputBufferCreationFailed',
  outputBufferCreationFailed = 'outputBufferCreationFailed',
  inputReadFailed = 'inputReadFailed',
  converterFailed = 'converterFailed',
  outputWriteFailed = 'outputWriteFailed',
  outputTooLarge = 'outputTooLarge',
  emptyOutput = 'emptyOutput'
}

// These events may have arguments that weren't resolved!
export type WhisperAudioConverterEvents = {};

export declare class WhisperAudioConverterNativeModuleType extends NativeModule<WhisperAudioConverterEvents> {
  convertAndTrimAudio(
    inputPath: string,
    startOffset: number,
    endOffset: number,
    options: WhisperAudioConversionOptions
  ): Promise<string>;

  setValueAsync(value: string): Promise<void>;
}

// declare class WhisperAudioConverterModule extends NativeModule<{}> {
//   setValueAsync(value: string): Promise<void>;
// }
