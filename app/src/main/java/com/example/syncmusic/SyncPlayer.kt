package com.example.syncmusic

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.io.File

/** Common surface over ExoPlayer (local files) and the YouTube IFrame player. Main thread only. */
interface SyncPlayer {
    /** Current playback position in ms. */
    val position: Long
    /** Length of the current song/video in ms (0 while unknown). */
    val durationMs: Long
    /** True once playback was requested (even while still buffering). */
    val isActive: Boolean
    /** True only while audio is actually advancing (not buffering, not paused). */
    val isPlaying: Boolean
    /** How long (ms) the player has been playing without interruption; 0 when not playing. */
    val steadyMs: Long
    /** Whether fine speed changes (e.g. 0.98x) are available for gentle drift correction. */
    val supportsSpeed: Boolean
    /** Error (ms) above which the player jumps instead of nudging. */
    val hardSeekThresholdMs: Long

    /** Get ready to start at [ms] without starting playback. */
    fun cueAt(ms: Long)
    /** Start (or keep) playing from [ms] right now. */
    fun startFrom(ms: Long)
    fun seekTo(ms: Long)
    fun pause()
    fun setSpeed(speed: Float)
}

/** [audioSessionId]: the mixer's session, so its EQ applies to this player. */
class LocalPlayer(context: Context, audioSessionId: Int, onEnded: () -> Unit) : SyncPlayer {
    private var playingSince = 0L
    private val exo = ExoPlayer.Builder(context).build().apply {
        setAudioSessionId(audioSessionId)
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingSince = if (isPlaying) now() else 0L
            }
        })
    }

    override val position get() = exo.currentPosition
    override val durationMs get() = exo.duration.coerceAtLeast(0L)
    override val isActive get() = exo.playWhenReady
    override val isPlaying get() = exo.isPlaying
    override val steadyMs get() = if (exo.isPlaying && playingSince > 0) now() - playingSince else 0L
    override val supportsSpeed = true
    override val hardSeekThresholdMs = 100L

    /** Show video (mp4…) in [view]; audio-only files simply show nothing there. */
    fun attach(view: PlayerView) {
        view.player = exo
    }

    fun load(file: File) {
        exo.playWhenReady = false
        exo.setPlaybackSpeed(1f)
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
    }

    override fun cueAt(ms: Long) = exo.seekTo(ms)

    override fun startFrom(ms: Long) {
        if (kotlin.math.abs(exo.currentPosition - ms) > 20) exo.seekTo(ms)
        exo.play()
    }

    override fun seekTo(ms: Long) {
        playingSince = now()
        exo.seekTo(ms)
    }

    override fun pause() = exo.pause()
    override fun setSpeed(speed: Float) = exo.setPlaybackSpeed(speed)
    fun release() = exo.release()
}

/**
 * YouTube via the official IFrame player (android-youtube-player). Audio is never extracted;
 * every phone streams the video itself and we only align positions, so precision is lower.
 */
class YouTubeSyncPlayer(
    val view: YouTubePlayerView,
    onEnded: () -> Unit,
    /** Paused by something other than the app (a tap on the video, another app taking audio…). */
    onUnexpectedPause: () -> Unit,
    /** The playing video is fully downloaded (or nearly over), so it's a good time to preload the next one. */
    onFullyLoaded: () -> Unit,
) : SyncPlayer {
    companion object {
        /** Buffer this much of the video before reporting ready, so all phones start smoothly. */
        const val PRELOAD_FRACTION = 0.2f
        const val PRELOAD_TIMEOUT_MS = 60_000L

        /**
         * Settings switch "download 20% first". Off: a video is only cued and reported ready at
         * once (starts sooner, may stutter at first), and the next song isn't preloaded.
         */
        @Volatile var preloadEnabled = true
    }

    private var player: YouTubePlayer? = null
    private var pendingVideo: String? = null
    private var onCued: (() -> Unit)? = null
    private var onProgress: ((percent: Int) -> Unit)? = null
    private var preloading = false
    private var preloadToken = 0
    /**
     * When the preloaded video actually started playing. Until then, loaded-fraction reports
     * can still describe the previous video (often 100%), which used to skip the preload.
     */
    private var preloadPlayingAt = 0L
    /** Video being loaded / loaded in this player. */
    var videoId: String? = null
        private set
    /** Set once [videoId] is buffered and waiting paused at the start. */
    private var prepared = false
    private var durationSec = 0f
    private var fullyLoadedReported = false
    private var nudgeToken = 0
    /** True while temporarily playing at 1.25x/0.75x to close a small gap. */
    var isNudging = false
        private set
    private var requested = false
    private var playing = false
    private var playingSince = 0L
    private var lastSecond = 0f
    /**
     * Recent (receivedAt, receivedAt - mediaMs) pairs while playing. A report that arrives late or
     * is rounded down only makes the second value bigger, so the smallest one is the best estimate.
     */
    private val anchors = ArrayDeque<Pair<Long, Long>>()
    /** Reports sent before a seek can still arrive just after it; ignore them. */
    private var ignoreReportsUntil = 0L

    init {
        val listener = object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                pendingVideo?.let { preload(it) }
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                durationSec = duration
            }

            override fun onVideoLoadedFraction(youTubePlayer: YouTubePlayer, loadedFraction: Float) {
                if (!preloading) {
                    // YouTube doesn't always buffer a long video to 100%, so "90s left" counts too.
                    val nearEnd = durationSec > 0 && durationSec - lastSecond < 90
                    if (requested && playing && !fullyLoadedReported && (loadedFraction >= 0.99f || nearEnd)) {
                        fullyLoadedReported = true
                        onFullyLoaded()
                    }
                    return
                }
                if (preloadPlayingAt == 0L || now() - preloadPlayingAt < 500) return
                onProgress?.invoke((loadedFraction * 100).toInt().coerceAtMost(100))
                if (loadedFraction >= PRELOAD_FRACTION) finishPreload()
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                val t = now()
                if (t < ignoreReportsUntil) return
                lastSecond = second
                if (!playing) return
                anchors.addLast(t to t - (second * 1000).toLong())
                while (anchors.isNotEmpty() && t - anchors.first().first > 2000) anchors.removeFirst()
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                val nowPlaying = state == PlayerConstants.PlayerState.PLAYING
                if (nowPlaying != playing) {
                    anchors.clear()
                    playingSince = if (nowPlaying) now() else 0L
                }
                playing = nowPlaying
                if (preloading && nowPlaying && preloadPlayingAt == 0L) preloadPlayingAt = now()
                if (state == PlayerConstants.PlayerState.ENDED && !preloading) onEnded()
                if (state == PlayerConstants.PlayerState.PAUSED && requested && !preloading) onUnexpectedPause()
            }
        }
        val options = IFramePlayerOptions.Builder(view.context).controls(0).rel(0).build()
        view.enableAutomaticInitialization = false
        view.initialize(listener, options)
    }

    /** Position is reported about every 100ms with jitter; smooth it over the last 2 seconds. */
    override val position: Long
        get() {
            if (!playing || anchors.isEmpty()) return (lastSecond * 1000).toLong()
            return now() - anchors.minOf { it.second }
        }
    override val durationMs get() = (durationSec * 1000).toLong()
    override val isActive get() = requested
    override val isPlaying get() = playing
    override val steadyMs get() = if (playing && playingSince > 0 && !isNudging) now() - playingSince else 0L
    override val supportsSpeed = false
    /** Bigger gaps jump; smaller ones are closed smoothly with [nudge], which doesn't re-buffer. */
    override val hardSeekThresholdMs = 1000L

    /**
     * Close a gap of [errorMs] (negative = behind) without jumping: play at 1.25x (or 0.75x) for
     * |error| / 0.25 ms. Audio pitch is preserved, and the video doesn't need to buffer again.
     */
    fun nudge(errorMs: Long) {
        val p = player ?: return
        if (isNudging || !playing || errorMs == 0L) return
        isNudging = true
        val token = ++nudgeToken
        p.setPlaybackRate(if (errorMs < 0) PlayerConstants.PlaybackRate.RATE_1_25 else PlayerConstants.PlaybackRate.RATE_0_75)
        anchors.clear()
        view.postDelayed({ if (token == nudgeToken) endNudge() }, kotlin.math.abs(errorMs) * 4)
    }

    private fun endNudge() {
        if (!isNudging) return
        nudgeToken++
        isNudging = false
        player?.setPlaybackRate(PlayerConstants.PlaybackRate.RATE_1)
        // Position reports gathered at the other speed don't fit the 1x estimate.
        anchors.clear()
        ignoreReportsUntil = now() + 200
        if (playing) playingSince = now()
    }

    /**
     * Load [videoId] and buffer [PRELOAD_FRACTION] of it (playing muted, since the IFrame player only
     * buffers while playing), then rewind to the start paused. [progress] reports the loaded percent;
     * [ready] runs when done (or after [PRELOAD_TIMEOUT_MS], so a slow phone can't block everyone).
     */
    fun load(videoId: String, ready: () -> Unit, progress: (Int) -> Unit = {}) {
        requested = false
        playing = false
        anchors.clear()
        lastSecond = 0f
        onCued = ready
        onProgress = progress
        this.videoId = videoId
        prepared = false
        durationSec = 0f
        fullyLoadedReported = false
        if (player == null) pendingVideo = videoId else preload(videoId)
    }

    /** True while buffering a video muted in the background (see [load]). */
    val isPreloading get() = preloading

    /** True if [id] is already buffered here and waiting at the start (see [load]). */
    fun isPrepared(id: String) = prepared && videoId == id

    /** True if [id] is being preloaded or is already prepared here. */
    fun hasVideo(id: String) = videoId == id && (preloading || prepared || pendingVideo == id)

    private fun preload(videoId: String) {
        pendingVideo = null
        val p = player ?: return
        if (!preloadEnabled) {
            p.cueVideo(videoId, 0f)
            prepared = true
            onProgress = null
            val ready = onCued
            onCued = null
            ready?.invoke()
            return
        }
        preloading = true
        preloadPlayingAt = 0L
        val token = ++preloadToken
        p.mute()
        p.loadVideo(videoId, 0f)
        view.postDelayed({ if (preloading && token == preloadToken) finishPreload() }, PRELOAD_TIMEOUT_MS)
    }

    private fun finishPreload() {
        preloading = false
        player?.let {
            it.pause()
            it.seekTo(0f)
            it.unMute()
        }
        lastSecond = 0f
        anchors.clear()
        ignoreReportsUntil = now() + 300
        onProgress = null
        prepared = true
        onCued?.invoke()
        onCued = null
    }

    // The video is already buffered and paused at the start (see load()).
    override fun cueAt(ms: Long) {}

    override fun startFrom(ms: Long) {
        requested = true
        prepared = false
        seekTo(ms)
        player?.play()
    }

    override fun seekTo(ms: Long) {
        endNudge()
        lastSecond = ms / 1000f
        anchors.clear()
        ignoreReportsUntil = now() + 300
        if (playing) playingSince = now()
        player?.seekTo(ms / 1000f)
    }

    override fun pause() {
        requested = false
        endNudge()
        player?.pause()
    }

    override fun setSpeed(speed: Float) {}

    /** Resume after an unexpected pause, keeping the current position. */
    fun resume() {
        if (requested) player?.play()
    }

    fun stop() {
        requested = false
        endNudge()
        preloading = false
        prepared = false
        videoId = null
        pendingVideo = null
        onCued = null
        onProgress = null
        player?.pause()
        player?.unMute()
    }
}
