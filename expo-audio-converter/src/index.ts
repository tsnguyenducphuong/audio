// Native module (low-level, raw Expo module — throws on failure)
export { default as WhisperAudioConverterModule } from './WhisperAudioConverterModule';
 
// Types
export type {
  WhisperAudioConversionOptions,
  WhisperAudioConverterEvents,
  WhisperAudioConverterNativeModuleType,
} from './WhisperAudioConverter.types';
export { WhisperAudioConversionError } from './WhisperAudioConverter.types';
 
// High-level wrapper (recommended — never throws, returns typed result)
export {
  trimAndConvertAudio,
} from './WhisperAudioConverter.utils';
export type { WhisperAudioConversionResult } from './WhisperAudioConverter.utils';
 