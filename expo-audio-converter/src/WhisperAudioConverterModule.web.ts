import { registerWebModule, NativeModule } from 'expo';

class WhisperAudioConverterModule extends NativeModule<{}> {
  async setValueAsync(value: string): Promise<void> {}
}

export default registerWebModule(WhisperAudioConverterModule, 'WhisperAudioConverterModule');
