package com.example.syncmusic

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

sealed class PlaylistItem {
    abstract var title: String

    data class Local(val file: File, override var title: String) : PlaylistItem()
    data class YouTube(val videoId: String, override var title: String) : PlaylistItem()
}

object YouTubeIds {
    private val patterns = listOf(
        Regex("""(?:v=|/embed/|/shorts/|/live/|youtu\.be/)([A-Za-z0-9_-]{11})"""),
        Regex("""^([A-Za-z0-9_-]{11})$"""),
    )

    /** Accepts a full YouTube link (watch, youtu.be, shorts, live, embed) or a bare 11-char id. */
    fun parse(input: String): String? =
        patterns.firstNotNullOfOrNull { it.find(input.trim())?.groupValues?.get(1) }

    /** Title via YouTube's public oEmbed endpoint; blocking, call off the main thread. */
    fun fetchTitle(videoId: String): String? = runCatching {
        val target = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
        val conn = URL("https://www.youtube.com/oembed?format=json&url=$target").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            JSONObject(conn.inputStream.bufferedReader().readText()).getString("title")
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
