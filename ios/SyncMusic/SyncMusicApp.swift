import AVFoundation
import SwiftUI

@main
struct SyncMusicApp: App {
    @StateObject private var model = AppModel()

    init() {
        // Play like a music app: ignores the silent switch, keeps going with the screen locked.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
        }
    }
}
