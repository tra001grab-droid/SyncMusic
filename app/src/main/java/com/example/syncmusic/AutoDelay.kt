package com.example.syncmusic

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 🤖 Auto delay: every phone beeps at a scheduled moment and the host's microphone hears when
 * each beep really comes out. A phone heard later than the host has more output delay, so it
 * plays that much ahead (Mix.delayMs). Only differences between beeps in one recording are used,
 * so the microphone's own delay cancels out.
 */
object AutoDelay {
    const val RATE = 48000
    const val BEEP_HZ = 2000.0
    const val BEEP_MS = 80
    /** Time between two beeps; also the most delay that can be measured. */
    const val GAP_MS = 700L
    const val ROUNDS = 3

    /** now() (elapsedRealtime) in ms → System.nanoTime, which audio timestamps use. */
    private fun toNano(ms: Double): Long =
        (ms * 1_000_000).toLong() - SystemClock.elapsedRealtimeNanos() + System.nanoTime()

    private fun toMs(nano: Long): Double =
        (nano + SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1_000_000.0

    /**
     * Plays a short beep at each of [times] (now() ms). Blocking; call on a background thread.
     * Uses the track's timestamp so the beep leaves the speaker on time as far as Android knows —
     * what's left is the delay Android doesn't report, which is what the music has too.
     */
    fun beep(times: List<Long>) {
        if (times.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(RATE / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val beepFrames = RATE * BEEP_MS / 1000
            val starts = LongArray(times.size) { -1 }
            val chunk = ShortArray(RATE / 100)  // 10 ms
            val ts = AudioTimestamp()
            var written = 0L
            val end = times.max() + BEEP_MS + 300
            while (now() < end) {
                // Where each beep should start in the stream, from the latest frame ↔ time mapping.
                val haveTs = track.getTimestamp(ts) && ts.framePosition > 0
                for (i in times.indices) {
                    if (starts[i] >= 0 && starts[i] < written) continue  // already playing: keep it
                    val target = toNano(times[i].toDouble())
                    starts[i] = if (haveTs) {
                        ts.framePosition + (target - ts.nanoTime) * RATE / 1_000_000_000L
                    } else {
                        track.playbackHeadPosition.toLong() + (times[i] - now()) * RATE / 1000
                    }
                }
                for (n in chunk.indices) {
                    val f = written + n
                    var v = 0.0
                    for (s in starts) {
                        val k = f - s
                        if (k in 0 until beepFrames) {
                            // 5 ms fade in/out so it clicks less; the onset stays sharp.
                            val fade = min(1.0, min(k, beepFrames - k) / (RATE * 0.005))
                            v += sin(2 * PI * BEEP_HZ * k / RATE) * fade
                        }
                    }
                    chunk[n] = (v.coerceIn(-1.0, 1.0) * 0.9 * Short.MAX_VALUE).toInt().toShort()
                }
                val w = track.write(chunk, 0, chunk.size)
                if (w <= 0) break
                written += w
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Microphone samples and the now() time of the first one. */
    class Recording(val samples: ShortArray, val startMs: Double)

    /** Records until now() reaches [untilMs]. Blocking; needs RECORD_AUDIO. Null if the mic can't be used. */
    @SuppressLint("MissingPermission")
    fun record(untilMs: Long): Recording? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = runCatching {
            // Voice recognition: no echo cancelling/noise removal that could eat the beeps.
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: return null
        try {
            val total = ((untilMs - now()).coerceAtLeast(0) * RATE / 1000 + RATE).toInt()
            val data = ShortArray(total)
            rec.startRecording()
            var read = 0
            var startMs = Double.NaN
            val ts = AudioTimestamp()
            while (read < total && now() < untilMs) {
                val n = rec.read(data, read, min(RATE / 50, total - read))
                if (n <= 0) break
                read += n
                // Time of frame 0, from the recorder's timestamp when it has one (once is enough).
                if (startMs.isNaN() && Build.VERSION.SDK_INT >= 24 &&
                    rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                ) {
                    startMs = toMs(ts.nanoTime) - ts.framePosition * 1000.0 / RATE
                }
            }
            if (startMs.isNaN()) startMs = now() - read * 1000.0 / RATE
            return Recording(data.copyOf(read), startMs)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /**
     * When the beep expected at [expectedMs] was heard, minus [expectedMs] (ms), or null if it
     * wasn't clearly louder than the room. Looks from 150 ms early up to just before the next beep.
     */
    fun lag(rec: Recording, expectedMs: Long): Double? {
        val win = RATE * 4 / 1000   // 4 ms Goertzel window
        val hop = RATE / 1000       // every 1 ms
        val from = ((expectedMs - 150 - rec.startMs) * RATE / 1000).toInt()
        val to = ((expectedMs + GAP_MS - 150 - rec.startMs) * RATE / 1000).toInt()
        if (from < 0 || to + win > rec.samples.size) return null
        val coeff = 2 * cos(2 * PI * BEEP_HZ / RATE)
        val energy = DoubleArray((to - from) / hop)
        for (j in energy.indices) {
            var s1 = 0.0
            var s2 = 0.0
            val base = from + j * hop
            for (k in 0 until win) {
                val s = rec.samples[base + k] + coeff * s1 - s2
                s2 = s1
                s1 = s
            }
            energy[j] = s1 * s1 + s2 * s2 - coeff * s1 * s2
        }
        if (energy.isEmpty()) return null
        val sorted = energy.sorted()
        val noise = sorted[sorted.size * 3 / 10].coerceAtLeast(1.0)
        val peakAt = energy.indices.maxBy { energy[it] }
        val peak = energy[peakAt]
        if (peak < noise * 20) return null
        val threshold = noise + (peak - noise) * 0.25
        // The first time it gets loud, searching back from the loudest point.
        var j = peakAt
        while (j > 0 && energy[j - 1] >= threshold) j--
        val heardMs = rec.startMs + (from + j * hop + win / 2) * 1000.0 / RATE
        return heardMs - expectedMs
    }
}
