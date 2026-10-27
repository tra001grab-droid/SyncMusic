package com.example.syncmusic

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Past searches, newest first; each remembers the first video played from it (for its thumbnail). */
class SearchHistory(private val prefs: SharedPreferences) {
    data class Entry(val query: String, val videoId: String?)

    fun all(): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Entry(o.getString("q"), o.optString("v").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    fun add(query: String) {
        val old = all()
        val previous = old.firstOrNull { it.query.equals(query, ignoreCase = true) }
        save(listOf(Entry(query, previous?.videoId)) + old.filter { it !== previous })
    }

    /** The video picked after the latest search becomes that search's thumbnail. */
    fun attachVideo(videoId: String) {
        val list = all()
        val first = list.firstOrNull() ?: return
        if (first.videoId == null) save(listOf(first.copy(videoId = videoId)) + list.drop(1))
    }

    fun remove(query: String) = save(all().filter { it.query != query })

    private fun save(list: List<Entry>) {
        val array = JSONArray()
        list.take(MAX).forEach { array.put(JSONObject().put("q", it.query).put("v", it.videoId ?: "")) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "search_history"
        const val MAX = 30
    }
}

/** Search suggestions from YouTube, like the YouTube app's search box. */
object YouTubeSuggest {
    /** Blocking; call off the main thread. Tries two endpoints; empty list if both fail. */
    fun fetch(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        return fetchFrom("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=km&ie=utf-8&oe=utf-8&q=$q")
            .ifEmpty { fetchFrom("https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&hl=km&q=$q") }
    }

    private fun fetchFrom(url: String): List<String> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        val body = try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
        parse(body)
    }.getOrDefault(emptyList())

    /**
     * Accepts ["q", ["a", "b"]] (client=firefox) and also ["q", [["a", 0], ["b", 0]]]
     * or the same wrapped in a JS call (client=youtube).
     */
    fun parse(body: String): List<String> {
        val json = body.substring(body.indexOf('[').coerceAtLeast(0), body.lastIndexOf(']') + 1)
        val list = JSONArray(json).getJSONArray(1)
        return (0 until list.length()).mapNotNull { i ->
            when (val v = list.get(i)) {
                is String -> v
                is JSONArray -> v.optString(0).ifEmpty { null }
                else -> null
            }
        }
    }
}

/** Small thumbnail loader with a memory cache (no image library needed). */
object Thumbnails {
    private val cache = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(3)

    fun youtube(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    fun load(url: String, into: ImageView) {
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        pool.execute {
            val bitmap = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                try {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            // The row may have been reused for another suggestion meanwhile.
            into.post { if (into.tag == url) into.setImageBitmap(bitmap) }
        }
    }
}

/**
 * The list shown under the search box while typing: 📁 library songs, 🕘 history (with the
 * thumbnail of what was played), 🔍 YouTube suggestions. ↖ copies a row into the box.
 */
class SuggestionList(
    private val activity: Activity,
    private val list: ListView,
    private val history: SearchHistory,
    private val findInLibrary: (String) -> List<PlaylistItem.Local>,
    private val onSearch: (String) -> Unit,
    private val onPickLocal: (PlaylistItem.Local) -> Unit,
    private val onFill: (String) -> Unit,
) {
    private sealed class Row {
        data class Library(val item: PlaylistItem.Local) : Row()
        data class History(val entry: SearchHistory.Entry) : Row()
        data class Suggestion(val text: String) : Row()
        data class Hint(val text: String) : Row()
    }

    private var rows = emptyList<Row>()
    private var token = 0
    /** True between update() and hide(), even while the list has no rows yet. */
    private var open = false
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind((convertView as? LinearLayout) ?: makeRow(), rows[position])
    }

    val isShowing get() = list.visibility == View.VISIBLE

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (val row = rows[position]) {
                is Row.Library -> onPickLocal(row.item)
                is Row.History -> onSearch(row.entry.query)
                is Row.Suggestion -> onSearch(row.text)
                is Row.Hint -> if (lastText.isNotBlank()) onSearch(lastText.trim())
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows[position] as? Row.History ?: return@setOnItemLongClickListener false
            history.remove(row.entry.query)
            update(lastText)
            true
        }
    }

    private var lastText = ""

    /** Rebuild for [text]: local rows right away, YouTube suggestions shortly after (debounced). */
    fun update(text: String) {
        open = true
        lastText = text
        val q = text.trim()
        val local = if (q.isEmpty()) emptyList() else findInLibrary(q).map { Row.Library(it) }
        val past = history.all()
            .filter { q.isEmpty() || it.query.contains(q, ignoreCase = true) }
            .take(if (q.isEmpty()) 15 else 4)
            .map { Row.History(it) }
        show(local + past)
        val myToken = ++token
        if (q.isEmpty()) return
        list.postDelayed({
            if (myToken != token) return@postDelayed
            thread(isDaemon = true) {
                val remote = YouTubeSuggest.fetch(q)
                activity.runOnUiThread {
                    // Check "open", not visibility: with no history/library rows yet the list is hidden.
                    if (myToken != token || !open) return@runOnUiThread
                    val known = past.map { it.entry.query.lowercase() }.toSet()
                    show(local + past + remote.filter { it.lowercase() !in known }.take(10).map { Row.Suggestion(it) }, loading = false)
                }
            }
        }, 250)
    }

    private fun show(newRows: List<Row>, loading: Boolean = true) {
        // Nothing to list: a hint instead of an empty screen.
        val hint = when {
            lastText.isBlank() -> "វាយឈ្មោះបទចម្រៀង ឬចុច 🎤"
            loading -> "កំពុងស្វែងរក…"
            else -> "🔍 ស្វែងរក \"${lastText.trim()}\""
        }
        rows = newRows.ifEmpty { listOf(Row.Hint(hint)) }
        adapter.notifyDataSetChanged()
        list.visibility = View.VISIBLE
    }

    fun hide() {
        open = false
        token++
        list.visibility = View.GONE
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun makeRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(4), dp(8))
        minimumHeight = dp(56)
        addView(TextView(activity).apply { textSize = 20f; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(activity).apply { textSize = 17f; setTextColor(Color.WHITE); maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(80), dp(45)).apply { marginStart = dp(8) })
        addView(TextView(activity).apply {
            text = "↖"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun bind(view: LinearLayout, row: Row): View {
        val icon = view.getChildAt(0) as TextView
        val label = view.getChildAt(1) as TextView
        val thumb = view.getChildAt(2) as ImageView
        val fill = view.getChildAt(3) as TextView
        val (iconText, text, videoId) = when (row) {
            is Row.Library -> Triple(if (row.item.isVideo) "🎬" else "📁", row.item.title, null)
            is Row.History -> Triple("🕘", row.entry.query, row.entry.videoId)
            is Row.Suggestion -> Triple("🔍", row.text, null)
            is Row.Hint -> Triple("", row.text, null)
        }
        icon.text = iconText
        label.text = text
        if (videoId != null) {
            thumb.visibility = View.VISIBLE
            Thumbnails.load(Thumbnails.youtube(videoId), thumb)
        } else {
            thumb.tag = null
            thumb.setImageDrawable(null)
            thumb.visibility = View.GONE
        }
        // Library rows play directly; the others can be copied into the box to keep typing.
        fill.visibility = if (row is Row.Library || row is Row.Hint) View.INVISIBLE else View.VISIBLE
        fill.setOnClickListener { onFill(text) }
        return view
    }
}
