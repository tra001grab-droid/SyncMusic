import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var manualAddress = ""
    @State private var askReconnect = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Sync Music").font(.largeTitle.bold())
                Text(model.status).font(.subheadline).foregroundColor(.secondary)
                if model.connected {
                    PlayingSection()
                } else {
                    ConnectSection(browser: model.browser, manualAddress: $manualAddress)
                }
            }
            .padding()
        }
        .onAppear {
            if model.rememberedHostName != nil && !model.connected { askReconnect = true }
        }
        .alert(isPresented: $askReconnect) {
            Alert(
                title: Text("Sync Music"),
                message: Text("ភ្ជាប់ទៅ \(model.rememberedHostName ?? "Host") ដែលធ្លាប់ភ្ជាប់?"),
                primaryButton: .default(Text("✔ អនុញ្ញាត")) { model.reconnectRemembered() },
                secondaryButton: .cancel(Text("ទេ"))
            )
        }
    }
}

/// Not connected: find the host on the Wi-Fi, or type its address.
private struct ConnectSection: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var browser: HostBrowser
    @Binding var manualAddress: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                model.startDiscovery()
            } label: {
                Label("ចូលរួម (ស្វែងរក Host)", systemImage: "antenna.radiowaves.left.and.right")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.connecting)

            ForEach(browser.hosts) { host in
                Button {
                    model.connect(to: host)
                } label: {
                    Label(host.name, systemImage: "iphone.radiowaves.left.and.right")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
            }

            HStack {
                TextField("ឬ IP:port របស់ Host", text: $manualAddress)
                    .keyboardType(.numbersAndPunctuation)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                Button("ភ្ជាប់") { model.connectManual(manualAddress) }
                    .buttonStyle(.bordered)
            }

            if let name = model.rememberedHostName {
                Button {
                    model.reconnectRemembered()
                } label: {
                    Label("ភ្ជាប់ទៅ \(name) ម្ដងទៀត", systemImage: "arrow.clockwise")
                }
            }

            Text("ទូរស័ព្ទទាំងអស់ត្រូវភ្ជាប់ Wi-Fi ឬ Hotspot តែមួយ។ iPhone ធ្វើជាទូរស័ព្ទចូលរួម ហើយ Host ជាទូរស័ព្ទ Android។")
                .font(.footnote)
                .foregroundColor(.secondary)
        }
    }
}

/// Connected: what's playing, the video, and this phone's ⏱ delay.
private struct PlayingSection: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if !model.title.isEmpty {
                Text((model.kind == .youtube ? "▶ YouTube: " : model.kind == .video ? "🎬 " : "🎵 ") + model.title)
                    .font(.headline)
            }

            // Both players stay in the view tree (a hidden web view may stop buffering).
            ZStack {
                Color.black
                YouTubeView(webView: model.youtube.webView).opacity(model.kind == .youtube ? 1 : 0)
                VideoView(player: model.local.player).opacity(model.kind == .video ? 1 : 0)
                if model.kind == .audio {
                    Image(systemName: "music.note").font(.system(size: 60)).foregroundColor(.gray)
                }
                // Taps would pause YouTube behind the app's back: the host controls playback.
                Color.clear.contentShape(Rectangle()).onTapGesture {}
                if model.showPreload {
                    HStack(spacing: 10) {
                        ProgressView().tint(.white)
                        Text("កំពុងទាញយក").bold()
                    }
                    .padding(12)
                    .background(Color.black.opacity(0.8))
                    .cornerRadius(8)
                }
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .cornerRadius(8)

            DelaySection()

            Button(role: .destructive) {
                model.disconnect()
            } label: {
                Label("ផ្ដាច់", systemImage: "xmark.circle").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }
}

private struct DelaySection: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("⏱ Delay: \(model.delayMs > 0 ? "+" : "")\(model.delayMs) ms").font(.headline)
            HStack {
                Button("− 10") { model.delayMs = max(model.delayMs - 10, -500) }.buttonStyle(.bordered)
                Slider(
                    value: Binding(
                        get: { Double(model.delayMs) },
                        set: { model.delayMs = Int(($0 / 10).rounded()) * 10 }
                    ),
                    in: -500...500,
                    step: 10
                )
                Button("+ 10") { model.delayMs = min(model.delayMs + 10, 500) }.buttonStyle(.bordered)
            }
            Text("iPhone នេះលឺយឺតជាងគេ (ឧ. Bluetooth) → ＋ · លឺលឿនជាងគេ → −")
                .font(.footnote)
                .foregroundColor(.secondary)
        }
    }
}
