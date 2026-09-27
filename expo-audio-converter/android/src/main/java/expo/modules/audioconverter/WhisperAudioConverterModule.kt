package expo.modules.audioconverter

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class WhisperAudioConverterModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("WhisperAudioConverter")

    AsyncFunction("setValueAsync") { value: String ->
    }
  }
}
