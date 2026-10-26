package com.example.syncmusic

import java.io.File
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Host side. Line-based text protocol over TCP. Every playlist switch bumps `version`;
 * messages carry it so clients ignore anything about a previous item.
 *   client -> host : PING <t0> | READY <version>
 *   host -> client : PONG <t0> <hostNow>
 *                    ITEM <version> local <httpPort> <title>
 *                    ITEM <version> yt <videoId> <title>
 *                    PLAY <version> <posMs> <hostStartTime> | PAUSE
 *                    POS <version> <posMs> <hostTime>
 */
class SyncServer(private val onEvent: (String) -> Unit) {
    private val serverSocket = ServerSocket(0)
    private val http = FileHttpServer()
    /** Connected clients -> last item version they reported READY for. */
    private val clients = ConcurrentHashMap<PrintWriter, Int>()
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
        val title = item.title.replace('\n', ' ')
        val message = when (item) {
            is PlaylistItem.Local -> {
                http.setFile(v, item.file)
                "ITEM $v local ${http.port} $title"
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
        itemMessage?.let { synchronized(out) { out.println(it) } }
        try {
            socket.getInputStream().bufferedReader().forEachLine { line ->
                val parts = line.split(" ")
                when (parts[0]) {
                    // Reply as fast as possible so round-trip time stays small.
                    "PING" -> synchronized(out) { out.println("PONG ${parts[1]} ${now()}") }
                    "READY" -> parts.getOrNull(1)?.toIntOrNull()?.let { clients[out] = it }
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(out)
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
