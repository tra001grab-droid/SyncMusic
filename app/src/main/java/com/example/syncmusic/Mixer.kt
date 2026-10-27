package com.example.syncmusic

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.abs
import kotlin.math.ln

/**
 * One phone's sound settings: volume 0-200 (0-100 = media volume, above 100 = boost on top of
 * full volume), mute, and 5 EQ bands in dB.
 */
data class Mix(
    val volume: Int = 70,
    val muted: Boolean = false,
    val bands: List<Int> = List(BAND_HZ.size) { 0 },
) {
    /** Compact form sent between phones: "80,0,3:1:0:0:2". */
    fun encode() = "$volume,${if (muted) 1 else 0},${bands.joinToString(":")}"

    companion object {
        val BAND_HZ = listOf(60, 230, 910, 3600, 14000)
        val BAND_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
        const val MAX_VOLUME = 200
        /** Gain added at 200%: +10 dB, which sounds about twice as loud. */
        const val MAX_BOOST_MILLIBELS = 1000
        const val BAND_MIN_DB = -12
        const val BAND_MAX_DB = 12

        val PRESETS: Map<String, List<Int>> = linkedMapOf(
            "Flat" to listOf(0, 0, 0, 0, 0),
            "🔊 Bass" to listOf(8, 5, 0, 0, 1),
            "🎤 Vocal" to listOf(-2, 0, 4, 3, 1),
            "Pop" to listOf(-1, 2, 4, 2, -1),
            "Rock" to listOf(5, 3, -1, 3, 5),
            "Classic" to listOf(4, 2, -1, 2, 4),
        )

        fun decode(text: String?): Mix? = runCatching {
            val p = text!!.split(",")
            val bands = p[2].split(":").map { it.toInt().coerceIn(BAND_MIN_DB, BAND_MAX_DB) }
            Mix(p[0].toInt().coerceIn(0, MAX_VOLUME), p[1] == "1", (bands + List(BAND_HZ.size) { 0 }).take(BAND_HZ.size))
        }.getOrNull()
    }
}

/**
 * Applies a [Mix] on this phone. Volume is the phone's media volume (so it works for every
 * source). The EQ runs on the library player's audio session; for YouTube (a WebView) only the
 * phone-wide EQ can work, which some phones don't allow.
 */
class Mixer(context: Context, private val prefs: SharedPreferences) {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** Give this to ExoPlayer so the EQ below applies to it. */
    val sessionId: Int = audio.generateAudioSessionId()

    private val playerEq: Equalizer? = runCatching { Equalizer(0, sessionId) }.getOrNull()
    @Suppress("DEPRECATION")
    private val globalEq: Equalizer? = runCatching { Equalizer(0, 0) }.getOrNull()

    private val playerBoost: LoudnessEnhancer? = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
    @Suppress("DEPRECATION")
    private val globalBoost: LoudnessEnhancer? = runCatching { LoudnessEnhancer(0) }.getOrNull()
    /** Volume above 100% (0-100), remembered because the phone only knows "full volume". */
    private var boost = prefs.getInt("boost", 0)

    val boostAvailable get() = playerBoost != null || globalBoost != null
    val boostWorksForYouTube get() = globalBoost != null

    /** False when the phone refuses a phone-wide EQ, i.e. EQ won't change YouTube there. */
    val eqWorksForYouTube get() = globalEq != null
    val eqAvailable get() = playerEq != null || globalEq != null

    private var bands: List<Int> = Mix.decode(prefs.getString("mix", null))?.bands ?: List(Mix.BAND_HZ.size) { 0 }

    init {
        applyEq(bands)
        applyBoost(boost)
    }

    fun current(): Mix {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Boost only counts while the phone is at full volume (volume keys down = no boost shown).
        val volume = if (index >= max) 100 + boost else index * 100 / max
        return Mix(volume, audio.isStreamMute(AudioManager.STREAM_MUSIC), bands)
    }

    fun apply(mix: Mix) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val base = mix.volume.coerceAtMost(100)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (base * max + 50) / 100, 0) }
        val newBoost = (mix.volume - 100).coerceIn(0, 100)
        if (newBoost != boost) applyBoost(newBoost)
        if (mix.muted != audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            runCatching {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mix.muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0,
                )
            }
        }
        if (mix.bands != bands) applyEq(mix.bands)
    }

    private fun applyBoost(percent: Int) {
        boost = percent
        prefs.edit().putInt("boost", percent).apply()
        val gain = percent * Mix.MAX_BOOST_MILLIBELS / 100
        for (enhancer in listOfNotNull(playerBoost, globalBoost)) {
            runCatching {
                enhancer.setTargetGain(gain)
                enhancer.enabled = gain > 0
            }
        }
    }

    private fun applyEq(levels: List<Int>) {
        bands = levels
        prefs.edit().putString("mix", Mix(bands = levels).encode()).apply()
        for (eq in listOfNotNull(playerEq, globalEq)) {
            runCatching {
                val range = eq.bandLevelRange  // millibels, e.g. [-1500, 1500]
                for (b in 0 until eq.numberOfBands) {
                    val hz = eq.getCenterFreq(b.toShort()) / 1000
                    val millibels = (levels[nearestBand(hz)] * 100).coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), millibels.toShort())
                }
                eq.enabled = levels.any { it != 0 }
            }
        }
    }

    /** Phones have different EQ bands; use our band closest in pitch (log scale). */
    private fun nearestBand(hz: Int): Int =
        Mix.BAND_HZ.indices.minBy { abs(ln(Mix.BAND_HZ[it].toDouble()) - ln(hz.coerceAtLeast(1).toDouble())) }

    fun release() {
        runCatching { playerBoost?.release() }
        runCatching { globalBoost?.release() }
        runCatching { playerEq?.release() }
        runCatching { globalEq?.release() }
    }
}
