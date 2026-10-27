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
 *   host -> client : HELLO <hostId> <hostName>   (first line after connecting)
 *                    PONG <t0> <hostNow>
 *                    ITEM <version> local <httpPort> <fileId> <audio|video> <title>
 *                    ITEM <version> yt <videoId> <title>
 *                    PLAY <version> <posMs> <hostStartTime> | PAUSE
 *                    POS <version> <posMs> <hostTime>
 *                    SKIP <totalMs>   (only shows "⏩ +15s" on screen; the jump itself is a PLAY)
 *                    NEXT <videoId>   (preload 20% of the upcoming YouTube video in the background)
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

    fun nextReadyCount(videoId: String) = clients.keys.count { nextReady[it] == videoId }

    /** Loading percent of each client that isn't ready yet for version [v]. */
    fun pendingProgress(v: Int): List<Int> =
        clients.filter { it.value != v }.keys.map { progress[it] ?: 0 }

    fun broadcast(message: String) {
        sender.execute {
            for (out in clients.keys) {
                synchronized(out) { out.println(message) }
                if (out.checkError()) clients.remove(out)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val out = PrintWriter(socket.getOutputStream(), true)
        clients[out] = 0
        onEvent("ទូរស័ព្ទភ្ជាប់: ${socket.inetAddress.hostAddress}")
        synchronized(out) { out.println("HELLO $hostId ${hostName.replace('\n', ' ')}") }
        itemMessage?.let { synchronized(out) { out.println(it) } }
        nextMessage?.let { synchronized(out) { out.println(it) } }
        try {
            socket.getInputStream().bufferedReader().forEachLine { line ->
                val parts = line.split(" ")
                when (parts[0]) {
                    // Reply as fast as possible so round-trip time stays small.
                    "PING" -> synchronized(out) { out.println("PONG ${parts[1]} ${now()}") }
                    "READY" -> parts.getOrNull(1)?.toIntOrNull()?.let { clients[out] = it }
                    "NEXTREADY" -> parts.getOrNull(1)?.let { nextReady[out] = it }
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
