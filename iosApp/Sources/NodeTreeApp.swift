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

/// Skeleton screen: type a request (on-device speech arrives in slice 1),
/// hear the reply, and read the run tree as an accessible outline.
/// Uses the demo conductor (fake contacts, fake dialer) until slice 1.
struct ContentView: View {
    private let bridge = CallbackConductorKt.createDemoCallbackConductor(platform: .ios, driverId: "ios-voice")
    private let speaker = AVSpeechSynthesizer()

    @State private var typed = ""
    @State private var reply: ReplyView?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                TextField("Say or type a request", text: $typed)
                    .textFieldStyle(.roundedBorder)
                Button("Send") { send(typed); typed = "" }
            }

            if let reply {
                Text(reply.text).font(.title2)
                if reply.isConfirmation {
                    HStack(spacing: 12) {
                        Button("Yes") { send("yes") }.buttonStyle(.borderedProminent).font(.title)
                        Button("No") { send("no") }.buttonStyle(.bordered).font(.title)
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

    private func send(_ text: String) {
        bridge.send(text: text) { view in
            reply = view
            speaker.speak(AVSpeechUtterance(string: view.text))
        }
    }
}
