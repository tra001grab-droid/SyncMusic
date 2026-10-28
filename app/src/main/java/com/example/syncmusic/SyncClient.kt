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
    /** Songs received from the host are kept here (by file id) so replays need no transfer. */
    private val libraryDir: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        /** Connected to the host [hostId] at [address]:[port] (used to remember it). */
        fun onHello(hostId: String, hostName: String, address: String, port: Int)
        /** A new item was announced; stop the current one. */
        fun onItemChanging(version: Int, title: String)
        fun onLocalItem(version: Int, file: File, isVideo: Boolean)
        /** A song isn't in the local library yet and is being copied from the host. */
        fun onDownloading(version: Int)
        fun onYouTubeItem(version: Int, videoId: String)
        fun onPlay(version: Int, posMs: Long, localStartTime: Long)
        fun onHostPause()
        fun onPosition(version: Int, posMs: Long, localTime: Long)
        /** The host is skipping by [totalMs] (for the on-screen indicator only). */
        fun onSkip(totalMs: Long)
        /** Preload [videoId] in the background; it's the next song. */
        fun onNext(videoId: String)
        /** The host's mixer set this phone's volume/EQ (see Mix.decode). */
        fun onSetMix(mix: String)
        /** 🤖 Auto delay: beep at these local times so the host's microphone can time this phone. */
        fun onBeep(localTimes: List<Long>)
        /** The host turned its 20% preload switch on/off; do the same. */
        fun onHostPreload(on: Boolean)
        /** Connection failed or was lost (not called after [stop]). */
        fun onDisconnected(reason: String)
    }

    private class Sample(val rtt: Long, val offset: Long)

    companion object {
        private const val LIBRARY_LIMIT_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB
    }

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
        if (sender.isShutdown) return  // stopped: drop late messages instead of crashing
        runCatching { sender.execute { synchronized(o) { o.println(message) } } }
    }

    fun sendReady(version: Int) = send("READY $version")

    fun sendProgress(version: Int, percent: Int) = send("PROGRESS $version $percent")

    fun sendNextReady(videoId: String) = send("NEXTREADY $videoId")

    /** Tell the host who we are (shown in its mixer). */
    fun sendIam(name: String) = send("IAM ${name.replace('\n', ' ')}")

    fun sendMix(mix: Mix) = send("MIX ${mix.encode()}")

    /** Our current gap to the host (+ = ahead) and round-trip time, for the host's track view. */
    fun sendStat(gapMs: Long, rttMs: Long) = send("STAT $gapMs $rttMs")

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
            "HELLO" -> {
                val f = line.split(" ", limit = 3)
                listener.onHello(f[1], f.getOrElse(2) { "Host" }, host, port)
            }
            "PONG" -> {
                val t0 = p[1].toLong()
                val hostTime = p[2].toLong()
                val t1 = now()
                addSample(Sample(t1 - t0, hostTime - (t0 + t1) / 2))
            }
            "ITEM" -> {
                val v = p[1].toInt()
                currentVersion = v
                when (p[2]) {
                    "local" -> {
                        // ITEM <v> local <port> <fileId> <audio|video> <title>
                        val f = line.split(" ", limit = 7)
                        listener.onItemChanging(v, f.getOrElse(6) { "" })
                        fetchSong(v, f[3].toInt(), f[4], f[5] == "video")
                    }
                    "yt" -> {
                        listener.onItemChanging(v, p.getOrElse(4) { "" })
                        listener.onYouTubeItem(v, p[3])
                    }
                }
            }
            "PLAY" -> listener.onPlay(p[1].toInt(), p[2].toLong(), p[3].toLong() - offset)
            "PAUSE" -> listener.onHostPause()
            "POS" -> listener.onPosition(p[1].toInt(), p[2].toLong(), p[3].toLong() - offset)
            "SKIP" -> listener.onSkip(p[1].toLong())
            "NEXT" -> listener.onNext(p[1])
            "SETMIX" -> listener.onSetMix(p[1])
            "PRELOAD" -> listener.onHostPreload(p[1] == "1")
            "BEEP" -> listener.onBeep(p[1].split(",").mapNotNull { it.toLongOrNull()?.minus(offset) })
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

    /** Use the library copy if we have one; otherwise copy it from the host (over Wi-Fi) and keep it. */
    private fun fetchSong(version: Int, httpPort: Int, fileId: String, isVideo: Boolean) = thread(isDaemon = true) {
        libraryDir.mkdirs()
        val target = File(libraryDir, fileId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
        if (target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            listener.onLocalItem(version, target, isVideo)
            return@thread
        }
        try {
            listener.onDownloading(version)
            listener.onStatus("កំពុងទាញយកពី Host…")
            val partial = File(libraryDir, target.name + ".part")
            val conn = URL("http://$host:$httpPort/song/$version").openConnection() as HttpURLConnection
            conn.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            partial.renameTo(target)
            trimLibrary(keep = target)
            // Host may have moved on while we were downloading; the copy stays in the library.
            if (version == currentVersion) listener.onLocalItem(version, target, isVideo)
        } catch (e: Exception) {
            if (version == currentVersion) listener.onStatus("ទាញយកបរាជ័យ: ${e.message}")
        }
    }

    /** Keep the library under [LIBRARY_LIMIT_BYTES] by dropping the least recently played copies. */
    private fun trimLibrary(keep: File) {
        val files = libraryDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= LIBRARY_LIMIT_BYTES) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        sender.shutdownNow()
    }
}
