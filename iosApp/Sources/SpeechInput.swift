// UNVERIFIED: written without Xcode. Build and test on a device before relying on it.
import AVFoundation
import Speech

/// Tap-to-talk speech input. Tap once to start, tap again to finish; the final
/// transcript goes to `onFinal`. Recognition stays on the device whenever the
/// recognizer supports it for the current language.
@MainActor
final class SpeechInput: ObservableObject {
    @Published private(set) var isListening = false
    @Published private(set) var partial = ""
    @Published private(set) var problem: String?

    private let recognizer = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var onFinal: ((String) -> Void)?

    func toggle(onFinal: @escaping (String) -> Void) {
        if isListening { stop() } else { start(onFinal) }
    }

    private func start(_ onFinal: @escaping (String) -> Void) {
        problem = nil
        SFSpeechRecognizer.requestAuthorization { status in
            AVAudioSession.sharedInstance().requestRecordPermission { micGranted in
                Task { @MainActor in
                    guard status == .authorized, micGranted else {
                        self.problem = "Speech or microphone access is off. You can turn it on in Settings, or type instead."
                        return
                    }
                    self.begin(onFinal)
                }
            }
        }
    }

    private func begin(_ onFinal: @escaping (String) -> Void) {
        guard let recognizer, recognizer.isAvailable else {
            problem = "Speech recognition isn't available right now. You can type instead."
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            if recognizer.supportsOnDeviceRecognition {
                request.requiresOnDeviceRecognition = true
            }

            let input = engine.inputNode
            input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
                request.append(buffer)
            }
            engine.prepare()
            try engine.start()

            self.request = request
            self.onFinal = onFinal
            partial = ""
            isListening = true

            task = recognizer.recognitionTask(with: request) { result, error in
                Task { @MainActor in
                    if let result {
                        self.partial = result.bestTranscription.formattedString
                        if result.isFinal { self.finish(self.partial) }
                    }
                    if error != nil { self.finish(self.partial) }
                }
            }
        } catch {
            problem = "I couldn't start listening. You can type instead."
            cleanUp()
        }
    }

    /// Ends the audio; the recognizer then delivers the final transcript.
    private func stop() {
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
    }

    private func finish(_ text: String) {
        guard let deliver = onFinal else { return }
        onFinal = nil
        cleanUp()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { deliver(trimmed) }
    }

    private func cleanUp() {
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        task?.cancel()
        task = nil
        request = nil
        isListening = false
    }
}
