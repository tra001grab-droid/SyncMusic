import Foundation

/// A phone's sound settings as the Android host's mixer sends them:
/// "volume,muted,b1:b2:b3:b4:b5,boostUnlocked,delayMs,bluetooth". The iPhone uses volume (in-app,
/// up to 100%), mute and delay; EQ and boost are Android-only.
struct Mix: Equatable {
    var volume = 100
    var muted = false
    var bands = [0, 0, 0, 0, 0]
    var boostUnlocked = false
    var delayMs = 0
    var bluetooth = false

    func encode() -> String {
        "\(volume),\(muted ? 1 : 0),\(bands.map(String.init).joined(separator: ":")),\(boostUnlocked ? 1 : 0),\(delayMs),\(bluetooth ? 1 : 0)"
    }

    static func decode(_ text: String) -> Mix? {
        let p = text.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        guard p.count >= 3, let volume = Int(p[0]) else { return nil }
        var mix = Mix()
        mix.volume = min(max(volume, 0), 200)
        mix.muted = p[1] == "1"
        let bands = p[2].split(separator: ":").compactMap { Int($0) }
        mix.bands = Array((bands + [0, 0, 0, 0, 0]).prefix(5))
        mix.boostUnlocked = p.count > 3 && p[3] == "1"
        if p.count > 4, let delay = Int(p[4]) { mix.delayMs = min(max(delay, -500), 500) }
        mix.bluetooth = p.count > 5 && p[5] == "1"
        return mix
    }
}
