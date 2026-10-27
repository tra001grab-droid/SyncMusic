package com.example.syncmusic

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

sealed class PlaylistItem {
    abstract var title: String

    /** A song (mp3…) or video (mp4…) file stored in the host's library. */
    data class Local(val file: File, override var title: String, val isVideo: Boolean = false) : PlaylistItem() {
        /** Stable id (the stored file's name); clients keep their copy under it so it's sent only once. */
        val id: String get() = file.name
    }

    data class YouTube(val videoId: String, override var title: String) : PlaylistItem()
}

/** Saves the host's playlist, so the library survives closing the app. */
object PlaylistStore {
    fun save(target: File, items: List<PlaylistItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                when (item) {
                    is PlaylistItem.Local -> JSONObject()
                        .put("type", "local").put("file", item.file.name)
                        .put("title", item.title).put("video", item.isVideo)
                    is PlaylistItem.YouTube -> JSONObject()
                        .put("type", "yt").put("id", item.videoId).put("title", item.title)
                }
            )
        }
        val tmp = File(target.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(target)
    }

    /** Local files are looked up in [libraryDir]; entries whose file is gone are dropped. */
    fun load(source: File, libraryDir: File): List<PlaylistItem> = runCatching {
        val array = JSONArray(source.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            when (o.getString("type")) {
                "local" -> File(libraryDir, o.getString("file")).takeIf { it.exists() }
                    ?.let { PlaylistItem.Local(it, o.getString("title"), o.optBoolean("video")) }
                "yt" -> PlaylistItem.YouTube(o.getString("id"), o.getString("title"))
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

/** Finds songs in the local library by (part of) their name. Khmer-friendly: ignores spaces. */
object LibrarySearch {
    private fun normalize(text: String) =
        text.lowercase().replace(Regex("[\\s\u200B\u200C\u200D._\\-|()\\[\\]]+"), "")

    fun find(query: String, items: List<PlaylistItem>, limit: Int = 6): List<PlaylistItem.Local> {
        val words = query.split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.filterIsInstance<PlaylistItem.Local>()
            .filter { item -> normalize(item.title).let { t -> words.all { it in t } } }
            .take(limit)
    }
}

object YouTubeIds {
    private val patterns = listOf(
        Regex("""(?:v=|/embed/|/shorts/|/live/|youtu\.be/)([A-Za-z0-9_-]{11})"""),
        Regex("""^([A-Za-z0-9_-]{11})$"""),
    )

    /** Accepts a full YouTube link (watch, youtu.be, shorts, live, embed) or a bare 11-char id. */
    fun parse(input: String): String? =
        patterns.firstNotNullOfOrNull { it.find(input.trim())?.groupValues?.get(1) }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    // Older pages list videos as "videoId", newer ones (related list) as "contentId".
    private val pageVideoId = Regex(""""(?:videoId|contentId)":"([A-Za-z0-9_-]{11})"""")

    private fun fetchPage(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.setRequestProperty("Accept-Language", "km,en;q=0.8")
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun firstNewId(html: String, exclude: Set<String>): String? =
        pageVideoId.findAll(html).map { it.groupValues[1] }.firstOrNull { it !in exclude }

    /**
     * Next video for auto play: the first video in YouTube's related list for [videoId], falling
     * back to a search for [title]. Skips [exclude]. Blocking, call off the main thread.
     */
    fun fetchNext(videoId: String, title: String, exclude: Set<String>): String? {
        val skip = exclude + videoId
        runCatching { firstNewId(fetchPage("https://www.youtube.com/watch?v=$videoId"), skip) }
            .getOrNull()?.let { return it }
        return runCatching {
            firstNewId(fetchPage("https://www.youtube.com/results?search_query=" + URLEncoder.encode(title, "UTF-8")), skip)
        }.getOrNull()
    }

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
