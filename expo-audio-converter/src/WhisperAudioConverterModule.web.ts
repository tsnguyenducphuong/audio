import { registerWebModule, NativeModule } from 'expo';
// import { WhisperAudioConversionOptions } from './WhisperAudioConverter.types';

class WhisperAudioConverterModule extends NativeModule<{}> {
  async setValueAsync(value: string): Promise<void> {}
  // async convertAndTrimAudio(
  //     inputPath: string,
  //     startOffset: number,
  //     endOffset: number,
  //     options: WhisperAudioConversionOptions
  //   ): Promise<string>{}
}

export default registerWebModule(WhisperAudioConverterModule, 'WhisperAudioConverterModule');
