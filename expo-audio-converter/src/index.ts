// Reexport the native module. On web, it will be resolved to WhisperAudioConverterModule.web.ts
// and on native platforms to WhisperAudioConverterModule.ts
export { default } from './WhisperAudioConverterModule';
export * from './WhisperAudioConverter.types';
