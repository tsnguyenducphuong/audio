import ExpoModulesCore

public class WhisperAudioConverterModule: Module {
  public func definition() -> ModuleDefinition {
    Name("WhisperAudioConverter")

    AsyncFunction("setValueAsync") { (value: String) in
    }
  }
}
