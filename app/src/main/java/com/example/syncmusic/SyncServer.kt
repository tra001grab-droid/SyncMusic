package com.example.syncmusic

import java.io.File
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Host side. Line-based text protocol over TCP. Every playlist switch bumps `version`;
 * messages carry it so clients ignore anything about a previous item.
 *   client -> host : PING <t0> | READY <version> | PROGRESS <version> <percent> | NEXTREADY <videoId>
 *                    IAM <name> | MIX <mix>   (mixer: who the phone is, its volume/EQ)
 *                    STAT <gapMs> <rttMs>     (its current gap to the host, for the mixer's tracks)
 *   host -> client : HELLO <hostId> <hostName>   (first line after connecting)
 *                    PONG <t0> <hostNow>
 *                    ITEM <version> local <httpPort> <fileId> <audio|video> <title>
 *                    ITEM <version> yt <videoId> <title>
 *                    PLAY <version> <posMs> <hostStartTime> | PAUSE
 *                    POS <version> <posMs> <hostTime>
 *                    SKIP <totalMs>   (only shows "⏩ +15s" on screen; the jump itself is a PLAY)
 *                    NEXT <videoId>   (preload 20% of the upcoming YouTube video in the background)
 *                    SETMIX <mix>     (host's mixer changed this phone's volume/EQ; see Mix.encode)
 *                    PRELOAD <0|1>    (host's "► ធ្វើអោយភ្លេងរត់ត្រូវគ្នា" switch; clients follow it)
 *                    HOLD <0|1>       (✋ stop measuring: 1 = clients stop correcting, just play on)
 *                    BEEP <t1,t2,...> [hz]  (🤖 Auto delay: beep at these host times)
 *                    LISTEN <until> <hz> <t,t;t,t;...>  (🤖 Auto delay with this phone's mic: the
 *                                     beep times of every phone; answered with LAGS)
 *                    TICKS <start> <periodMs> <hz> | TICKS 0  (🎧 by ear: beep every period / stop)
 *   client -> host : CUR <version> <posMs> <durationMs> <hostTime>  (its song position, every second)
 *   host -> client : ALIGN          (🎯 jump once to exactly where the host is, even with ✋ on)
 *   client -> host : LAGS <lag,lag;lag,lag;...> | LAGS nomic   ("x" = beep not heard)
 */
class SyncServer(
    /** Permanent id of this host, so clients can recognize it next time. */
    private val hostId: String,
    private val hostName: String,
    private val onEvent: (String) -> Unit,
) {
    companion object {
        /** Same port every time when possible, so a remembered address keeps working. */
        const val PREFERRED_PORT = 47800
    }

    private val serverSocket = runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PREFERRED_PORT))
        }
    }.getOrElse { ServerSocket(0) }
    private val http = FileHttpServer()
    /** Connected clients -> last item version they reported READY for. */
    private val clients = ConcurrentHashMap<PrintWriter, Int>()
    /** Connected clients -> how much of the current YouTube video they have buffered (%). */
    private val progress = ConcurrentHashMap<PrintWriter, Int>()
    /** Connected clients -> the upcoming video they have preloaded. */
    private val nextReady = ConcurrentHashMap<PrintWriter, String>()
    @Volatile private var nextMessage: String? = null
    @Volatile private var preloadMessage: String? = null
    @Volatile private var holdMessage: String? = null

    /** A connected phone as the mixer shows it. */
    class ClientInfo(val key: Int, address: String) {
        @Volatile var name: String = address
        /** Its latest Mix.encode(), as reported by it (or as last set by the host). */
        @Volatile var mix: String? = null
        /** Its latest gap to the host (ms; + = ahead) and round-trip time, and when they came. */
        @Volatile var gapMs: Long? = null
        @Volatile var rttMs: Long? = null
        @Volatile var statAt: Long = 0
        /** Its song position (ms) at host time [cursorAt], the song's length, for [cursorVersion]. */
        @Volatile var cursorMs: Long = 0
        @Volatile var cursorAt: Long = 0
        @Volatile var durationMs: Long = 0
        @Volatile var cursorVersion: Int = -1
    }

    private val info = ConcurrentHashMap<PrintWriter, ClientInfo>()
    private val nextKey = java.util.concurrent.atomic.AtomicInteger(1)
    /** Called (on a network thread) when a phone connects, disconnects or reports its mix. */
    @Volatile var onClientsChanged: (() -> Unit)? = null
    /** A client's answer to LISTEN (its key, the LAGS text). */
    @Volatile var onLags: ((Int, String) -> Unit)? = null

    fun clientList(): List<ClientInfo> = info.values.sortedBy { it.key }

    /** Send [message] to one phone only. */
    fun sendTo(key: Int, message: String) {
        val out = info.entries.firstOrNull { it.value.key == key }?.key ?: return
        if (sender.isShutdown) return
        runCatching { sender.execute { synchronized(out) { out.println(message) } } }
    }
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var itemMessage: String? = null

    @Volatile var version = 0
        private set
    val port: Int get() = serverSocket.localPort
    val clientCount: Int get() = clients.size

    fun start() {
        http.start()
        thread(name = "sync-accept", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try { serverSocket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    /** Announce a new playlist item to all clients; returns its version. */
    fun setItem(item: PlaylistItem): Int {
        val v = ++version
        progress.clear()
        val title = item.title.replace('\n', ' ')
        val message = when (item) {
            is PlaylistItem.Local -> {
                http.setFile(v, item.file)
                "ITEM $v local ${http.port} ${item.id} ${if (item.isVideo) "video" else "audio"} $title"
            }
            is PlaylistItem.YouTube -> {
                http.setFile(v, null)
                "ITEM $v yt ${item.videoId} $title"
            }
        }
        itemMessage = message
        broadcast(message)
        return v
    }

    fun readyCount(v: Int) = clients.values.count { it == v }

    /** Ask every client to preload [videoId] as the next song (null: nothing to preload). */
    fun announceNext(videoId: String?) {
        nextMessage = videoId?.let { "NEXT $it" }
        nextMessage?.let { broadcast(it) }
    }

    /** The 20% preload switch: every client switches the same way (also those joining later). */
    fun setPreload(on: Boolean) {
        preloadMessage = "PRELOAD ${if (on) 1 else 0}"
        broadcast(preloadMessage!!)
    }

    /** ✋ Stop measuring on/off for every client (also those joining later). */
    fun setHold(on: Boolean) {
        holdMessage = "HOLD ${if (on) 1 else 0}"
        broadcast(holdMessage!!)
    }

    fun nextReadyCount(videoId: String) = clients.keys.count { nextReady[it] == videoId }

    /** Loading percent of each client that isn't ready yet for version [v]. */
    fun pendingProgress(v: Int): List<Int> =
        clients.filter { it.value != v }.keys.map { progress[it] ?: 0 }

    fun broadcast(message: String) {
        // After stop() there is no one to send to; late callbacks (e.g. a player finishing
        // its preload) must not crash the app.
        if (sender.isShutdown) return
        runCatching {
            sender.execute {
                for (out in clients.keys) {
                    synchronized(out) { out.println(message) }
                    if (out.checkError()) clients.remove(out)
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val out = PrintWriter(socket.getOutputStream(), true)
        clients[out] = 0
        info[out] = ClientInfo(nextKey.getAndIncrement(), socket.inetAddress.hostAddress ?: "?")
        onClientsChanged?.invoke()
        onEvent("ទូរស័ព្ទភ្ជាប់: ${socket.inetAddress.hostAddress}")
        synchronized(out) { out.println("HELLO $hostId ${hostName.replace('\n', ' ')}") }
        itemMessage?.let { synchronized(out) { out.println(it) } }
        nextMessage?.let { synchronized(out) { out.println(it) } }
        preloadMessage?.let { synchronized(out) { out.println(it) } }
        holdMessage?.let { synchronized(out) { out.println(it) } }
        try {
            socket.getInputStream().bufferedReader().forEachLine { line ->
                val parts = line.split(" ")
                when (parts[0]) {
                    // Reply as fast as possible so round-trip time stays small.
                    "PING" -> synchronized(out) { out.println("PONG ${parts[1]} ${now()}") }
                    "READY" -> parts.getOrNull(1)?.toIntOrNull()?.let { clients[out] = it }
                    "NEXTREADY" -> parts.getOrNull(1)?.let { nextReady[out] = it }
                    "IAM" -> {
                        info[out]?.name = line.split(" ", limit = 2).getOrElse(1) { "?" }
                        onClientsChanged?.invoke()
                    }
                    "MIX" -> {
                        info[out]?.mix = parts.getOrNull(1)
                        onClientsChanged?.invoke()
                    }
                    "LAGS" -> info[out]?.let { c -> onLags?.invoke(c.key, parts.getOrElse(1) { "" }) }
                    "CUR" -> info[out]?.let { c ->
                        c.cursorVersion = parts.getOrNull(1)?.toIntOrNull() ?: -1
                        c.cursorMs = parts.getOrNull(2)?.toLongOrNull() ?: 0
                        c.durationMs = parts.getOrNull(3)?.toLongOrNull() ?: 0
                        c.cursorAt = parts.getOrNull(4)?.toLongOrNull() ?: 0
                    }
                    "STAT" -> info[out]?.let { c ->
                        c.gapMs = parts.getOrNull(1)?.toLongOrNull()
                        c.rttMs = parts.getOrNull(2)?.toLongOrNull()
                        c.statAt = now()
                    }
                    "PROGRESS" -> if (parts.getOrNull(1)?.toIntOrNull() == version) {
                        parts.getOrNull(2)?.toIntOrNull()?.let { progress[out] = it }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(out)
            progress.remove(out)
            nextReady.remove(out)
            info.remove(out)
            onClientsChanged?.invoke()
            socket.close()
            onEvent("ទូរស័ព្ទផ្ដាច់")
        }
    }

    fun stop() {
        serverSocket.close()
        http.stop()
        sender.shutdownNow()
    }
}
