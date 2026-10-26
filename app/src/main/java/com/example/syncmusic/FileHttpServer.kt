package com.example.syncmusic

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP server that serves the local song currently selected by the host at
 * `/song/<version>`. Requests for any other version get 404, so a client never stores
 * an outdated song under a newer name.
 */
class FileHttpServer {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    @Volatile
    private var current: Pair<Int, File>? = null

    fun setFile(version: Int, file: File?) {
        current = file?.let { version to it }
    }

    fun start() {
        thread(name = "http-accept", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try { serverSocket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket): Unit = socket.use {
        val reader = it.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val requested = requestLine.split(" ").getOrNull(1)?.substringAfterLast("/")?.toIntOrNull()
        // Skip the remaining headers.
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
        }
        val out = it.getOutputStream()
        val f = current?.takeIf { c -> c.first == requested }?.second
        if (f == null || !f.exists()) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${f.length()}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        f.inputStream().use { input -> input.copyTo(out) }
        out.flush()
    }

    fun stop() = serverSocket.close()
}
