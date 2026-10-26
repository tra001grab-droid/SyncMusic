package com.example.syncmusic

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    /** True once playback was requested (even while still buffering). */
    val isActive: Boolean
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

class LocalPlayer(context: Context, onEnded: () -> Unit) : SyncPlayer {
    private val exo = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        })
    }

    override val position get() = exo.currentPosition
    override val isActive get() = exo.playWhenReady
    override val supportsSpeed = true
    override val hardSeekThresholdMs = 100L

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

    override fun seekTo(ms: Long) = exo.seekTo(ms)
    override fun pause() = exo.pause()
    override fun setSpeed(speed: Float) = exo.setPlaybackSpeed(speed)
    fun release() = exo.release()
}

/**
 * YouTube via the official IFrame player (android-youtube-player). Audio is never extracted;
 * every phone streams the video itself and we only align positions, so precision is lower.
 */
class YouTubeSyncPlayer(view: YouTubePlayerView, onEnded: () -> Unit) : SyncPlayer {
    private var player: YouTubePlayer? = null
    private var pendingVideo: String? = null
    private var onCued: (() -> Unit)? = null
    private var requested = false
    private var playing = false
    private var lastSecond = 0f
    private var lastSecondAt = 0L

    init {
        val listener = object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                pendingVideo?.let { cue(it) }
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                lastSecond = second
                lastSecondAt = now()
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                playing = state == PlayerConstants.PlayerState.PLAYING
                if (playing) lastSecondAt = now()
                if (state == PlayerConstants.PlayerState.ENDED) onEnded()
            }
        }
        val options = IFramePlayerOptions.Builder(view.context).controls(0).rel(0).build()
        view.enableAutomaticInitialization = false
        view.initialize(listener, options)
    }

    /** Position is reported roughly every 100ms; extrapolate between reports while playing. */
    override val position: Long
        get() {
            val base = (lastSecond * 1000).toLong()
            return if (playing) base + (now() - lastSecondAt) else base
        }
    override val isActive get() = requested
    override val supportsSpeed = false
    override val hardSeekThresholdMs = 250L

    /** Cue [videoId]; [ready] runs once the player can accept commands. */
    fun load(videoId: String, ready: () -> Unit) {
        requested = false
        playing = false
        lastSecond = 0f
        onCued = ready
        if (player == null) pendingVideo = videoId else cue(videoId)
    }

    private fun cue(videoId: String) {
        pendingVideo = null
        player?.cueVideo(videoId, 0f)
        onCued?.invoke()
        onCued = null
    }

    // seekTo() on a cued video starts playback in the IFrame API, so nothing is done here.
    override fun cueAt(ms: Long) {}

    override fun startFrom(ms: Long) {
        requested = true
        seekTo(ms)
        player?.play()
    }

    override fun seekTo(ms: Long) {
        lastSecond = ms / 1000f
        lastSecondAt = now()
        player?.seekTo(ms / 1000f)
    }

    override fun pause() {
        requested = false
        player?.pause()
    }

    override fun setSpeed(speed: Float) {}

    fun stop() {
        requested = false
        player?.pause()
    }
}
