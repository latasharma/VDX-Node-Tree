// UNVERIFIED: written without Xcode. The Kotlin names below follow
// Kotlin/Native's Swift export rules; adjust if the generated header differs.
import SwiftUI
import AVFoundation
import NodeTreeShared

@main
struct NodeTreeApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// Holds the conductor for the app's lifetime. It must not live in the View
/// struct: SwiftUI recreates views freely, which would drop a pending yes/no.
@MainActor
final class AppModel: ObservableObject {
    @Published var reply: ReplyView?

    private let bridge = CallbackConductorKt.createPhoneCallbackConductor(
        platform: .ios, contacts: IOSContacts(), dialer: IOSDialer(), driverId: "ios-voice"
    )
    private let speaker = AVSpeechSynthesizer()

    func send(_ text: String) {
        bridge.send(text: text) { view in
            self.reply = view
            self.speaker.speak(AVSpeechUtterance(string: view.text))
        }
    }
}

/// Talk (on-device speech) or type, hear the reply, and read the run tree as
/// an accessible outline. Real contacts and dialer; calendar is still demo data.
struct ContentView: View {
    @StateObject private var model = AppModel()
    @StateObject private var speech = SpeechInput()
    @State private var typed = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                speech.toggle { model.send($0) }
            } label: {
                Text(speech.isListening ? "Done" : "Talk")
                    .font(.largeTitle.bold())
                    .frame(maxWidth: .infinity, minHeight: 96)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityHint(speech.isListening ? "Stops listening and sends what you said" : "Starts listening")

            if speech.isListening, !speech.partial.isEmpty {
                Text(speech.partial).font(.title3).foregroundStyle(.secondary)
            }
            if let problem = speech.problem {
                Text(problem).font(.callout).foregroundStyle(.red)
            }

            HStack {
                TextField("Or type a request", text: $typed)
                    .textFieldStyle(.roundedBorder)
                Button("Send") { model.send(typed); typed = "" }
            }

            if let reply = model.reply {
                Text(reply.text).font(.title2)
                if reply.isConfirmation {
                    HStack(spacing: 12) {
                        Button("Yes") { model.send("yes") }
                            .buttonStyle(.borderedProminent).font(.title)
                        Button("No") { model.send("no") }
                            .buttonStyle(.bordered).font(.title)
                    }
                }
                List {
                    ForEach(Array(zip(reply.outline, reply.accessibilityLabels).enumerated()), id: \.offset) { item in
                        Text(item.element.0)
                            .accessibilityLabel(item.element.1)
                    }
                }
            }
            Spacer()
        }
        .padding()
    }
}
