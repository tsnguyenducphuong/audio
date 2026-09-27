import { NativeModule, requireNativeModule } from 'expo';

import {
  WhisperAudioConversionOptions,
  WhisperAudioConverterNativeModuleType
} from './WhisperAudioConverter.types';


declare class WhisperAudioConverterModule extends NativeModule<{}> {
  setValueAsync(value: string): Promise<void>;
  convertAndTrimAudio(
      inputPath: string,
      startOffset: number,
      endOffset: number,
      options: WhisperAudioConversionOptions
    ): Promise<string>;
}

export default requireNativeModule<WhisperAudioConverterNativeModuleType>('WhisperAudioConverter');
