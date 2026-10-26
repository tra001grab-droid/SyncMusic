package com.example.syncmusic

import java.io.File
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Client side. Estimates the host clock offset NTP-style (keeping the sample with
 * the lowest round-trip time) and converts every host timestamp into local time.
 */
class SyncClient(
    private val candidates: List<String>,
    private val port: Int,
    private val cacheDir: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        /** A new item was announced; stop the current one. */
        fun onItemChanging(version: Int, title: String)
        fun onLocalItem(version: Int, file: File)
        fun onYouTubeItem(version: Int, videoId: String)
        fun onPlay(version: Int, posMs: Long, localStartTime: Long)
        fun onHostPause()
        fun onPosition(version: Int, posMs: Long, localTime: Long)
        /** Connection failed or was lost (not called after [stop]). */
        fun onDisconnected(reason: String)
    }

    private class Sample(val rtt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()
    private val sender = Executors.newSingleThreadExecutor()
    private var socket: Socket? = null
    private var out: PrintWriter? = null
    @Volatile private var running = true
    @Volatile private var currentVersion = 0
    /** The candidate address that actually accepted the connection. */
    @Volatile private var host = ""

    /** hostTime = localTime + offset */
    @Volatile var offset = 0L
        private set
    @Volatile var bestRtt = Long.MAX_VALUE
        private set

    fun start() {
        thread(name = "sync-client", isDaemon = true) {
            try {
                val s = connectToAny()
                s.tcpNoDelay = true
                socket = s
                out = PrintWriter(s.getOutputStream(), true)
                listener.onStatus("បានភ្ជាប់ទៅ Host $host")
                startPinging()
                s.getInputStream().bufferedReader().forEachLine { handle(it) }
                if (running) listener.onDisconnected("Host បានផ្ដាច់")
            } catch (e: Exception) {
                if (running) listener.onDisconnected("ការភ្ជាប់បរាជ័យ: ${e.message}")
            }
            running = false
        }
    }

    /** A host can have several addresses (Wi-Fi, hotspot, mobile data); use the first reachable one. */
    private fun connectToAny(): Socket {
        var lastError: Exception? = null
        for (candidate in candidates) {
            listener.onStatus("កំពុងភ្ជាប់ទៅ $candidate:$port …")
            val s = Socket()
            try {
                s.connect(InetSocketAddress(candidate, port), 2500)
                host = candidate
                return s
            } catch (e: Exception) {
                s.close()
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("រកមិនឃើញអាសយដ្ឋាន Host")
    }

    /** Safe to call from any thread (including main): the write happens on a worker. */
    fun send(message: String) {
        val o = out ?: return
        sender.execute { synchronized(o) { o.println(message) } }
    }

    fun sendReady(version: Int) = send("READY $version")

    fun sendProgress(version: Int, percent: Int) = send("PROGRESS $version $percent")

    private fun startPinging() = thread(name = "sync-ping", isDaemon = true) {
        var count = 0
        while (running) {
            // Written directly (not via sender) so queued messages don't inflate the RTT.
            out?.let { o -> synchronized(o) { o.println("PING ${now()}") } }
            // Burst at the start for a quick estimate, then refresh slowly to follow drift.
            Thread.sleep(if (count++ < 15) 100L else 2000L)
        }
    }

    private fun handle(line: String) {
        val p = line.split(" ", limit = 5)
        when (p[0]) {
            "PONG" -> {
                val t0 = p[1].toLong()
                val hostTime = p[2].toLong()
                val t1 = now()
                addSample(Sample(t1 - t0, hostTime - (t0 + t1) / 2))
            }
            "ITEM" -> {
                val v = p[1].toInt()
                currentVersion = v
                listener.onItemChanging(v, p.getOrElse(4) { "" })
                when (p[2]) {
                    "local" -> download(v, p[3].toInt())
                    "yt" -> listener.onYouTubeItem(v, p[3])
                }
            }
            "PLAY" -> listener.onPlay(p[1].toInt(), p[2].toLong(), p[3].toLong() - offset)
            "PAUSE" -> listener.onHostPause()
            "POS" -> listener.onPosition(p[1].toInt(), p[2].toLong(), p[3].toLong() - offset)
        }
    }

    @Synchronized
    private fun addSample(sample: Sample) {
        samples.addLast(sample)
        if (samples.size > 30) samples.removeFirst()
        val best = samples.minBy { it.rtt }
        offset = best.offset
        bestRtt = best.rtt
    }

    private fun download(version: Int, httpPort: Int) = thread(isDaemon = true) {
        try {
            listener.onStatus("កំពុងទាញយកបទចម្រៀងពី Host…")
            val target = File(cacheDir, "song_$version")
            val conn = URL("http://$host:$httpPort/song/$version").openConnection() as HttpURLConnection
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            // Host may have moved on while we were downloading.
            if (version != currentVersion) {
                target.delete()
                return@thread
            }
            cacheDir.listFiles { f -> f.name.startsWith("song_") && f != target }?.forEach { it.delete() }
            listener.onLocalItem(version, target)
        } catch (e: Exception) {
            if (version == currentVersion) listener.onStatus("ទាញយកបរាជ័យ: ${e.message}")
        }
    }

    fun stop() {
        running = false
        socket?.close()
        sender.shutdownNow()
    }
}
