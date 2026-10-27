import AVFoundation
import WebKit

/// Common surface over the local-file player and the YouTube player (main thread only).
protocol SyncPlayer: AnyObject {
    /// Current playback position in ms.
    var position: Int64 { get }
    /// True once playback was requested (even while still buffering).
    var isActive: Bool { get }
    /// True only while audio is actually advancing.
    var isPlaying: Bool { get }
    /// How long (ms) it has been playing without interruption; 0 when not playing.
    var steadyMs: Int64 { get }
    var supportsSpeed: Bool { get }
    /// Error (ms) above which the player jumps instead of nudging.
    var hardSeekThresholdMs: Int64 { get }

    func cueAt(_ ms: Int64)
    func startFrom(_ ms: Int64)
    func seekTo(_ ms: Int64)
    func pause()
    func setSpeed(_ speed: Float)
    func setVolume(_ volume: Float, muted: Bool)
}

/// Songs and videos copied from the host.
final class LocalPlayer: SyncPlayer {
    let player = AVPlayer()
    private var requested = false
    private var playingSince: Int64 = 0
    private var speed: Float = 1
    private var statusObservation: NSKeyValueObservation?

    init() {
        player.automaticallyWaitsToMinimizeStalling = false
        statusObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let playing = player.timeControlStatus == .playing
            DispatchQueue.main.async { self?.playingSince = playing ? now() : 0 }
        }
    }

    func load(_ url: URL) {
        requested = false
        speed = 1
        player.pause()
        let item = AVPlayerItem(url: url)
        // Keep the pitch when the speed is nudged to stay in sync.
        item.audioTimePitchAlgorithm = .spectral
        player.replaceCurrentItem(with: item)
    }

    var position: Int64 {
        let time = player.currentTime()
        return time.isNumeric ? Int64(time.seconds * 1000) : 0
    }
    var isActive: Bool { requested }
    var isPlaying: Bool { player.timeControlStatus == .playing }
    var steadyMs: Int64 { isPlaying && playingSince > 0 ? now() - playingSince : 0 }
    let supportsSpeed = true
    let hardSeekThresholdMs: Int64 = 100

    func cueAt(_ ms: Int64) { seek(ms) }

    func startFrom(_ ms: Int64) {
        requested = true
        if abs(position - ms) > 20 { seek(ms) }
        player.playImmediately(atRate: speed)
    }

    func seekTo(_ ms: Int64) {
        playingSince = now()
        seek(ms)
    }

    private func seek(_ ms: Int64) {
        player.seek(to: CMTime(value: max(ms, 0), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func pause() {
        requested = false
        player.pause()
    }

    func setSpeed(_ speed: Float) {
        self.speed = speed
        if requested && player.timeControlStatus != .paused { player.rate = speed }
    }

    func setVolume(_ volume: Float, muted: Bool) {
        player.volume = volume
        player.isMuted = muted
    }
}

/// Forwards script messages without the content controller keeping the player alive.
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}

/// YouTube via the official IFrame player in a WKWebView; every phone streams the video itself
/// and only positions are aligned (as on Android).
final class YouTubePlayer: NSObject, SyncPlayer, WKScriptMessageHandler {
    static let preloadFraction = 0.2

    let webView: WKWebView
    /// Paused by something other than the app (another app took the audio, …).
    var onUnexpectedPause: (() -> Void)?

    private var ready = false
    private var pendingVideo: String?
    private var onPrepared: (() -> Void)?
    private var onProgress: ((Int) -> Void)?
    private var preloading = false
    private var preloadToken = 0
    private var preloadPlayingAt: Int64 = 0
    private var requested = false
    private var playing = false
    private var playingSince: Int64 = 0
    private var lastSecond: Double = 0
    /// (receivedAt, receivedAt - mediaMs) while playing; the smallest second value is the best estimate.
    private var anchors: [(at: Int64, offset: Int64)] = []
    private var ignoreReportsUntil: Int64 = 0
    private var nudgeToken = 0
    private(set) var isNudging = false
    private var volume: Float = 1
    private var muted = false

    override init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let controller = WKUserContentController()
        config.userContentController = controller
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        controller.add(WeakScriptHandler(self), name: "yt")
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.isScrollEnabled = false
        webView.loadHTMLString(Self.html, baseURL: URL(string: "https://com.example.syncmusic"))
    }

    private static let html = """
    <!DOCTYPE html><html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}#p{width:100%;height:100%}</style>
    </head><body><div id="p"></div>
    <script>
    var player;
    function post(m) { window.webkit.messageHandlers.yt.postMessage(m); }
    function onYouTubeIframeAPIReady() {
      player = new YT.Player('p', {
        width: '100%', height: '100%',
        playerVars: { controls: 0, playsinline: 1, rel: 0, origin: 'https://com.example.syncmusic' },
        events: {
          onReady: function () {
            post({ e: 'ready' });
            setInterval(function () {
              if (player && player.getCurrentTime) {
                post({ e: 'time', t: player.getCurrentTime(), f: player.getVideoLoadedFraction() });
              }
            }, 100);
          },
          onStateChange: function (ev) { post({ e: 'state', s: ev.data }); }
        }
      });
    }
    </script>
    <script src="https://www.youtube.com/iframe_api"></script>
    </body></html>
    """

    private func js(_ script: String) {
        webView.evaluateJavaScript(script, completionHandler: nil)
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let event = body["e"] as? String else { return }
        switch event {
        case "ready":
            ready = true
            if let video = pendingVideo { preload(video) }
        case "state":
            let state = (body["s"] as? NSNumber)?.intValue ?? -1
            let nowPlaying = state == 1
            if nowPlaying != playing {
                anchors.removeAll()
                playingSince = nowPlaying ? now() : 0
            }
            playing = nowPlaying
            if preloading && nowPlaying && preloadPlayingAt == 0 { preloadPlayingAt = now() }
            if state == 2 && requested && !preloading { onUnexpectedPause?() }
        case "time":
            let seconds = (body["t"] as? NSNumber)?.doubleValue ?? 0
            let fraction = (body["f"] as? NSNumber)?.doubleValue ?? 0
            let t = now()
            if t >= ignoreReportsUntil {
                lastSecond = seconds
                if playing {
                    anchors.append((t, t - Int64(seconds * 1000)))
                    anchors.removeAll { t - $0.at > 2000 }
                }
            }
            // Fractions right after loading can still describe the previous video: wait until it plays.
            if preloading && preloadPlayingAt > 0 && t - preloadPlayingAt >= 500 {
                onProgress?(Int(fraction * 100))
                if fraction >= Self.preloadFraction { finishPreload() }
            }
        default:
            break
        }
    }

    /// Load [videoId] and buffer 20% of it (muted), then wait paused at the start; [ready] runs then.
    func load(_ videoId: String, ready onReady: @escaping () -> Void, progress: @escaping (Int) -> Void) {
        requested = false
        playing = false
        anchors.removeAll()
        lastSecond = 0
        onPrepared = onReady
        onProgress = progress
        if ready { preload(videoId) } else { pendingVideo = videoId }
    }

    private func preload(_ videoId: String) {
        pendingVideo = nil
        let safeId = videoId.filter { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }
        preloading = true
        preloadPlayingAt = 0
        preloadToken += 1
        let token = preloadToken
        js("player.mute(); player.loadVideoById('\(safeId)', 0);")
        // A slow phone mustn't block everyone.
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in
            guard let self, self.preloading, token == self.preloadToken else { return }
            self.finishPreload()
        }
    }

    private func finishPreload() {
        preloading = false
        js("player.pauseVideo(); player.seekTo(0, true);")
        applyVolume()
        lastSecond = 0
        anchors.removeAll()
        ignoreReportsUntil = now() + 300
        onProgress = nil
        let callback = onPrepared
        onPrepared = nil
        callback?()
    }

    var position: Int64 {
        guard playing, let best = anchors.map({ $0.offset }).min() else { return Int64(lastSecond * 1000) }
        return now() - best
    }
    var isActive: Bool { requested }
    var isPlaying: Bool { playing }
    var steadyMs: Int64 { playing && playingSince > 0 && !isNudging ? now() - playingSince : 0 }
    let supportsSpeed = false
    let hardSeekThresholdMs: Int64 = 1000

    // The video is already buffered and paused at the start (see load()).
    func cueAt(_ ms: Int64) {}

    func startFrom(_ ms: Int64) {
        requested = true
        seekTo(ms)
        js("player.playVideo();")
    }

    func seekTo(_ ms: Int64) {
        endNudge()
        lastSecond = Double(ms) / 1000
        anchors.removeAll()
        ignoreReportsUntil = now() + 300
        if playing { playingSince = now() }
        js("player.seekTo(\(Double(max(ms, 0)) / 1000), true);")
    }

    func pause() {
        requested = false
        endNudge()
        js("player.pauseVideo();")
    }

    func resume() {
        if requested { js("player.playVideo();") }
    }

    func stop() {
        requested = false
        endNudge()
        preloading = false
        onPrepared = nil
        onProgress = nil
        js("player.pauseVideo();")
        applyVolume()
    }

    func setSpeed(_ speed: Float) {}

    /// Close a small gap without jumping: 1.25x (behind) or 0.75x (ahead) for |error| / 0.25 ms.
    func nudge(_ errorMs: Int64) {
        guard !isNudging, playing, errorMs != 0 else { return }
        isNudging = true
        nudgeToken += 1
        let token = nudgeToken
        js("player.setPlaybackRate(\(errorMs < 0 ? 1.25 : 0.75));")
        anchors.removeAll()
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(abs(errorMs) * 4))) { [weak self] in
            guard let self, token == self.nudgeToken else { return }
            self.endNudge()
        }
    }

    private func endNudge() {
        guard isNudging else { return }
        nudgeToken += 1
        isNudging = false
        js("player.setPlaybackRate(1);")
        anchors.removeAll()
        ignoreReportsUntil = now() + 200
        if playing { playingSince = now() }
    }

    func setVolume(_ volume: Float, muted: Bool) {
        self.volume = volume
        self.muted = muted
        if !preloading { applyVolume() }
    }

    private func applyVolume() {
        js("player.setVolume(\(Int(volume * 100))); " + (muted ? "player.mute();" : "player.unMute();"))
    }
}
