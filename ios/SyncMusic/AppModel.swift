import AVFoundation
import Foundation
import Network
import UIKit

enum ItemKind { case none, audio, video, youtube }

/// The iPhone side of Sync Music: joins an Android host and plays in sync (client only).
/// Mirrors the Android client logic in MainActivity.kt (v2.7 sync behaviour). Main thread only.
final class AppModel: ObservableObject {
    @Published var status = "ចុច \"ចូលរួម\" ដើម្បីស្វែងរក Host"
    @Published var connected = false
    @Published var connecting = false
    @Published var title = ""
    @Published var kind: ItemKind = .none
    @Published var showPreload = false
    @Published var rememberedHostName: String? = UserDefaults.standard.string(forKey: "known_host_name")
    /// This phone's sound delay compensation (⏱), also settable from the host's mixer.
    @Published var delayMs: Int = UserDefaults.standard.integer(forKey: "delay_ms") {
        didSet {
            UserDefaults.standard.set(delayMs, forKey: "delay_ms")
            reportMix()
        }
    }

    let browser = HostBrowser()
    let local = LocalPlayer()
    let youtube = YouTubePlayer()

    private var connection: SyncConnection?
    private var active: SyncPlayer?
    private var clientVersion = 0
    private var clientTitle = ""
    private var loaded = false
    private var hostPlaying = false
    private var pendingStart: DispatchWorkItem?
    private var syncErrors: [Int64] = []
    /// YouTube buffers after every jump; aim this far ahead. Learned on this phone.
    private var youtubeSeekLeadMs: Int64 = 400
    private var measuringSeekLead = false
    private var unexpectedPauses: [Int64] = []
    private var volume = 100
    private var muted = false
    private var lastReportedMix: String?
    /// Set while trying a remembered host's saved address; on failure we search for it instead.
    private var reconnectingHostId: String?
    private var autoConnectHostId: String?

    private static let youtubeNudgeMs: Int64 = 60
    private static let softErrorMs: Int64 = 15

    init() {
        youtube.onUnexpectedPause = { [weak self] in self?.youtubePausedUnexpectedly() }
        browser.onResults = { [weak self] found in
            guard let self, let id = self.autoConnectHostId,
                  let host = found.first(where: { $0.hostId == id }) else { return }
            self.autoConnectHostId = nil
            self.connect([host.endpoint])
        }
    }

    // MARK: - Connecting

    func startDiscovery() {
        browser.start()
        status = "កំពុងស្វែងរក Host នៅក្នុង Wi-Fi…"
    }

    func connect(to host: HostBrowser.Found) {
        connect([host.endpoint])
    }

    /// "192.168.1.5:47800" (port optional: the host's usual port).
    func connectManual(_ text: String) {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard let ip = parts.first, !ip.isEmpty else {
            status = "សូមវាយជាទម្រង់ IP:port"
            return
        }
        let port = parts.count > 1 ? UInt16(parts[1]) ?? 47800 : 47800
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
        connect([.hostPort(host: NWEndpoint.Host(String(ip)), port: nwPort)])
    }

    /// One tap on "✔ អនុញ្ញាត": saved addresses first, then search the Wi-Fi for that host.
    func reconnectRemembered() {
        let defaults = UserDefaults.standard
        guard let id = defaults.string(forKey: "known_host_id") else { return }
        let ips = (defaults.string(forKey: "known_host_ips") ?? "").split(separator: ",").map(String.init)
        let port = UInt16(defaults.integer(forKey: "known_host_port"))
        guard !ips.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port == 0 ? 47800 : port) else {
            autoConnectHostId = id
            startDiscovery()
            return
        }
        reconnectingHostId = id
        connect(ips.map { .hostPort(host: NWEndpoint.Host($0), port: nwPort) })
    }

    private func connect(_ endpoints: [NWEndpoint]) {
        guard connection == nil else { return }
        browser.stop()
        connecting = true
        status = "កំពុងភ្ជាប់…"
        let link = SyncConnection()
        link.onLine = { [weak self] line in self?.handle(line) }
        link.onReady = { [weak self] in
            guard let self else { return }
            self.connected = true
            self.connecting = false
            self.status = "✔ បានភ្ជាប់"
            UIApplication.shared.isIdleTimerDisabled = true
        }
        link.onClosed = { [weak self] reason in self?.disconnected(reason) }
        connection = link
        link.connect(to: endpoints)
    }

    func disconnect() {
        connection?.stop()
        reconnectingHostId = nil
        disconnected("បានផ្ដាច់")
    }

    private func disconnected(_ reason: String) {
        connection = nil
        connected = false
        connecting = false
        loaded = false
        hostPlaying = false
        cancelStart()
        active?.pause()
        kind = .none
        title = ""
        showPreload = false
        UIApplication.shared.isIdleTimerDisabled = false
        // The remembered address didn't work (new IP?): look for that same host on the Wi-Fi.
        if let id = reconnectingHostId {
            reconnectingHostId = nil
            autoConnectHostId = id
            startDiscovery()
            status = "កំពុងស្វែងរក \(rememberedHostName ?? "Host")…"
            return
        }
        status = "\(reason)\nសូមព្យាយាមម្ដងទៀត"
    }

    private func send(_ line: String) {
        connection?.send(line)
    }

    // MARK: - Messages from the host

    private func handle(_ line: String) {
        let p = line.split(separator: " ", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
        let offset = connection?.offset ?? 0
        switch p.first {
        case "HELLO":
            let f = line.split(separator: " ", maxSplits: 2).map(String.init)
            if f.count >= 2 { onHello(hostId: f[1], hostName: f.count > 2 ? f[2] : "Host") }
        case "ITEM":
            guard p.count >= 4, let version = Int(p[1]) else { return }
            if p[2] == "local" {
                // ITEM <v> local <port> <fileId> <audio|video> <title>
                let f = line.split(separator: " ", maxSplits: 6, omittingEmptySubsequences: false).map(String.init)
                guard f.count >= 6, let port = Int(f[3]) else { return }
                itemChanging(version, title: f.count > 6 ? f[6] : "")
                fetchSong(version, port: port, fileId: f[4], isVideo: f[5] == "video")
            } else if p[2] == "yt" {
                itemChanging(version, title: p.count > 4 ? p[4] : "")
                youtubeItem(version, videoId: p[3])
            }
        case "PLAY":
            guard p.count >= 4, let v = Int(p[1]), let pos = Int64(p[2]), let start = Int64(p[3]) else { return }
            onPlay(v, posMs: pos, localStart: start - offset)
        case "PAUSE":
            onHostPause()
        case "POS":
            guard p.count >= 4, let v = Int(p[1]), let pos = Int64(p[2]), let t = Int64(p[3]) else { return }
            onPosition(v, posMs: pos, localTime: t - offset)
        case "SETMIX":
            if p.count > 1, let mix = Mix.decode(p[1]) { applyMix(mix) }
        default:
            // SKIP / NEXT: on-screen skip indicator and next-song preload are Android-only.
            break
        }
    }

    private func onHello(hostId: String, hostName: String) {
        reconnectingHostId = nil
        let defaults = UserDefaults.standard
        let address = connection?.hostAddress ?? ""
        let previous = (defaults.string(forKey: "known_host_ips") ?? "").split(separator: ",").map(String.init)
        var ips: [String] = []
        for ip in [address] + previous where !ip.isEmpty && !ips.contains(ip) { ips.append(ip) }
        defaults.set(hostId, forKey: "known_host_id")
        defaults.set(hostName, forKey: "known_host_name")
        defaults.set(ips.prefix(4).joined(separator: ","), forKey: "known_host_ips")
        defaults.set(Int(connection?.hostPort ?? 47800), forKey: "known_host_port")
        rememberedHostName = hostName
        status = "✔ បានភ្ជាប់ទៅ \(hostName)"
        send("IAM \(UIDevice.current.name) (iPhone)")
        lastReportedMix = nil
        reportMix()
    }

    private func itemChanging(_ version: Int, title: String) {
        clientVersion = version
        clientTitle = title
        self.title = title
        loaded = false
        showPreload = false
        hostPlaying = false
        cancelStart()
        resetSync()
        local.pause()
        local.setSpeed(1)
        youtube.stop()
    }

    // MARK: - Loading items

    private var libraryDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("library", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Use the library copy if we have one; otherwise copy it from the host (Wi-Fi) and keep it.
    private func fetchSong(_ version: Int, port: Int, fileId: String, isVideo: Bool) {
        let safeId = String(fileId.map { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" ? $0 : "_" })
        let target = libraryDir.appendingPathComponent(safeId)
        if let size = try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0 {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
            localItem(version, url: target, isVideo: isVideo)
            return
        }
        guard let url = URL(string: "http://\(connection?.hostAddress ?? ""):\(port)/song/\(version)") else { return }
        status = "⏳ កំពុងទាញយកពី Host (លើកដំបូងតែប៉ុណ្ណោះ)…"
        URLSession.shared.downloadTask(with: url) { [weak self] tmp, response, error in
            let ok = (response as? HTTPURLResponse)?.statusCode == 200
            var saved = false
            if let tmp, ok, error == nil {
                try? FileManager.default.removeItem(at: target)
                saved = (try? FileManager.default.moveItem(at: tmp, to: target)) != nil
            }
            DispatchQueue.main.async {
                guard let self else { return }
                guard saved else {
                    if version == self.clientVersion { self.status = "ទាញយកបរាជ័យ" }
                    return
                }
                self.trimLibrary(keep: target)
                if version == self.clientVersion { self.localItem(version, url: target, isVideo: isVideo) }
            }
        }.resume()
    }

    /// Keep the library under 2 GB, dropping the least recently played copies first.
    private func trimLibrary(keep: URL) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: libraryDir, includingPropertiesForKeys: keys) else { return }
        let info = files.map { url -> (URL, Int, Date) in
            let values = try? url.resourceValues(forKeys: Set(keys))
            return (url, values?.fileSize ?? 0, values?.contentModificationDate ?? .distantPast)
        }
        var total = info.reduce(0) { $0 + $1.1 }
        for (url, size, _) in info.sorted(by: { $0.2 < $1.2 }) where total > 2_000_000_000 && url != keep {
            try? FileManager.default.removeItem(at: url)
            total -= size
        }
    }

    private func localItem(_ version: Int, url: URL, isVideo: Bool) {
        guard version == clientVersion else { return }
        kind = isVideo ? .video : .audio
        local.load(url)
        local.setVolume(Float(volume) / 100, muted: muted)
        active = local
        markLoaded(version)
    }

    private func youtubeItem(_ version: Int, videoId: String) {
        kind = .youtube
        active = youtube
        showPreload = true
        status = "⏳ កំពុងទាញយក…"
        var lastSent = -1
        youtube.load(videoId, ready: { [weak self] in
            guard let self, version == self.clientVersion else { return }
            self.markLoaded(version)
        }, progress: { [weak self] percent in
            guard let self, version == self.clientVersion, percent != lastSent else { return }
            lastSent = percent
            self.send("PROGRESS \(version) \(percent)")
        })
        youtube.setVolume(Float(volume) / 100, muted: muted)
        // The host may have announced the item before it knew the title.
        if clientTitle == videoId { fetchTitle(videoId, version: version) }
    }

    private func fetchTitle(_ videoId: String, version: Int) {
        var components = URLComponents(string: "https://www.youtube.com/oembed")
        components?.queryItems = [
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "url", value: "https://www.youtube.com/watch?v=\(videoId)"),
        ]
        guard let url = components?.url else { return }
        URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let title = json["title"] as? String else { return }
            DispatchQueue.main.async {
                guard let self, version == self.clientVersion else { return }
                self.title = title
            }
        }.resume()
    }

    private func markLoaded(_ version: Int) {
        loaded = true
        showPreload = false
        send("READY \(version)")
        // If the host is already playing, the next POS message starts us in sync.
        status = "✔ រួចរាល់ — រង់ចាំទូរស័ព្ទផ្សេងទៀត…"
    }

    // MARK: - Playing in sync

    private func onPlay(_ version: Int, posMs: Int64, localStart: Int64) {
        guard version == clientVersion else { return }
        hostPlaying = true
        guard let p = active, loaded else { return }
        cancelStart()
        p.setSpeed(1)
        let delay = localStart - now()
        let calibration = Int64(delayMs)
        if delay > 0 {
            p.cueAt(posMs + calibration)
            scheduleStart(delay) { p.startFrom(posMs + calibration) }
        } else {
            // Joining late: YouTube will buffer after this jump, so aim a bit ahead.
            p.startFrom(posMs - delay + calibration + (p === youtube ? youtubeSeekLeadMs : 0))
        }
        resetSync()
        status = "កំពុងចាក់ (sync) — RTT \(connection?.bestRtt ?? 0) ms"
    }

    private func onHostPause() {
        hostPlaying = false
        cancelStart()
        resetSync()
        active?.pause()
        active?.setSpeed(1)
        status = "⏸ បានផ្អាក"
    }

    private func onPosition(_ version: Int, posMs: Int64, localTime: Int64) {
        guard version == clientVersion, loaded, let p = active else { return }
        hostPlaying = true
        if pendingStart != nil { return }
        let isYouTube = p === youtube
        let expected = posMs + (now() - localTime) + Int64(delayMs)
        if !p.isActive {
            p.startFrom(expected + (isYouTube ? youtubeSeekLeadMs : 0))
            resetSync()
            return
        }
        // Buffering, or just resumed: the position isn't trustworthy yet, so don't react.
        if !p.isPlaying || p.steadyMs < (isYouTube ? 1500 : 300) {
            status = "កំពុង buffer…"
            return
        }
        syncErrors.append(p.position - expected)
        if syncErrors.count > 3 { syncErrors.removeFirst() }
        if syncErrors.count < (isYouTube ? 3 : 2) { return }
        let error = syncErrors.sorted()[syncErrors.count / 2]
        // For the host's track view: our gap (+ = ahead) and round-trip time.
        send("STAT \(error) \(connection?.bestRtt ?? 0)")

        // First steady reading after a jump shows how far buffering left us behind; learn it.
        if measuringSeekLead && isYouTube {
            youtubeSeekLeadMs = min(max(youtubeSeekLeadMs - error * 7 / 10, 0), 3000)
            measuringSeekLead = false
        }
        if abs(error) > p.hardSeekThresholdMs {
            p.setSpeed(1)
            p.seekTo(expected + (isYouTube ? youtubeSeekLeadMs : 0))
            resetSync()
            measuringSeekLead = isYouTube
        } else if p.supportsSpeed && abs(error) > Self.softErrorMs {
            // Small error: play slightly faster/slower (pitch is kept) until aligned.
            p.setSpeed(Float(min(max(1 - Double(error) / 2000, 0.97), 1.03)))
        } else if isYouTube && abs(error) > Self.youtubeNudgeMs {
            youtube.nudge(error)
            resetSync()
        } else {
            p.setSpeed(1)
        }
        let synced = abs(error) <= (isYouTube ? Self.youtubeNudgeMs : Self.softErrorMs * 2)
        status = (synced ? "✅ កំពុង sync" : "⏳ កំពុងតម្រូវ…") + "\nគម្លាត: \(error) ms · RTT \(connection?.bestRtt ?? 0) ms"
    }

    /// YouTube paused without the app asking (another app took the audio, …): keep going,
    /// unless it keeps happening (a phone call): then stop fighting it.
    private func youtubePausedUnexpectedly() {
        let t = now()
        unexpectedPauses.append(t)
        unexpectedPauses.removeAll { t - $0 > 15_000 }
        if unexpectedPauses.count >= 3 {
            unexpectedPauses.removeAll()
            return
        }
        guard hostPlaying else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.youtube.resume() }
    }

    private func scheduleStart(_ delayMs: Int64, _ action: @escaping () -> Void) {
        cancelStart()
        let work = DispatchWorkItem { [weak self] in
            self?.pendingStart = nil
            action()
        }
        pendingStart = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(max(delayMs, 0))), execute: work)
    }

    private func cancelStart() {
        pendingStart?.cancel()
        pendingStart = nil
    }

    private func resetSync() {
        syncErrors.removeAll()
        measuringSeekLead = false
    }

    // MARK: - Mixer (from the host)

    /// Volume here is the app's own (iOS doesn't let apps set the phone volume), up to 100%.
    private func applyMix(_ mix: Mix) {
        volume = min(mix.volume, 100)
        muted = mix.muted
        local.setVolume(Float(volume) / 100, muted: muted)
        youtube.setVolume(Float(volume) / 100, muted: muted)
        if mix.delayMs != delayMs { delayMs = mix.delayMs } else { reportMix() }
    }

    private var bluetoothOutput: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains {
            [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP].contains($0.portType)
        }
    }

    /// Tell the host our settings (only when they changed).
    private func reportMix() {
        guard connection != nil else { return }
        var mix = Mix()
        mix.volume = volume
        mix.muted = muted
        mix.delayMs = delayMs
        mix.bluetooth = bluetoothOutput
        let text = mix.encode()
        guard text != lastReportedMix else { return }
        lastReportedMix = text
        send("MIX \(text)")
    }
}
