import { NativeModule, requireNativeModule } from 'expo';

declare class WhisperAudioConverterModule extends NativeModule<{}> {
  setValueAsync(value: string): Promise<void>;
}

export default requireNativeModule<WhisperAudioConverterModule>('WhisperAudioConverter');
