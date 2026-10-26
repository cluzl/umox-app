import OMOCastCore
import ReplayKit
import SwiftUI

@main
struct OMOCastApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @State private var ip = OMOCastSettings.clusterIP
    @State private var log = OMOCastSettings.log
    private let refresh = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            Form {
                Section("1 · Join the speedometer Wi-Fi") {
                    Text("Settings → Wi-Fi → DIRECT-OMO-… (password from the official OMOWAY app). Force-quit the OMOWAY app first.")
                    Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
                Section("2 · Speedometer address") {
                    TextField("192.168.2.1", text: $ip)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .onChange(of: ip) { OMOCastSettings.clusterIP = $0.trimmingCharacters(in: .whitespaces) }
                }
                Section("3 · Start casting") {
                    BroadcastButton()
                        .frame(height: 56)
                        .accessibilityLabel("Start or stop casting to the OMO X speedometer")
                    Text("Pick \u{201C}OMO X\u{201D}, tap Start Broadcast, then open Maps and turn the phone sideways.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Status") {
                    if log.isEmpty { Text("Not casting").foregroundStyle(.secondary) }
                    ForEach(log.reversed(), id: \.self) { Text($0).font(.caption.monospaced()) }
                }
            }
            .navigationTitle("OMO Cast")
        }
        .onReceive(refresh) { _ in log = OMOCastSettings.log }
    }
}

/// System broadcast picker, pre-selected to our extension so one tap starts casting.
struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView()
        picker.preferredExtension = Bundle.main.bundleIdentifier.map { $0 + ".broadcast" }
        picker.showsMicrophoneButton = false
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
