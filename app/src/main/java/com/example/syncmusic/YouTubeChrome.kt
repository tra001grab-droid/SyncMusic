package com.example.syncmusic

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hides the YouTube player's own buttons (big ▶/❚❚, title bar, share, logo, end screens) so they
 * don't sit on top of the app's controls. The IFrame player has no option for most of these, so
 * the embed page is fetched here and a small style sheet is added to it. If anything goes wrong
 * the page loads normally (null), so at worst YouTube's buttons are simply visible again.
 */
object YouTubeChrome {
    private const val CSS = "<style>" +
        ".ytp-large-play-button,.ytp-pause-overlay,.ytp-chrome-top,.ytp-gradient-top,.ytp-watermark," +
        ".ytp-youtube-button,.ytp-share-button,.ytp-title,.ytp-impression-link,.ytp-show-cards-title," +
        ".ytp-chrome-bottom,.ytp-gradient-bottom,.ytp-ce-element,.ytp-endscreen-content," +
        ".ytp-paid-content-overlay,.ytp-overflow-button,.ytp-cued-thumbnail-overlay-image+button," +
        ".ytp-suggested-action,.ytp-featured-product,.ytp-mobile-a11y-hidden-seek-button" +
        "{display:none!important}</style>"

    /** Request headers not to copy: we want plain (uncompressed) text to edit. */
    private val skipRequestHeaders = setOf("accept-encoding")
    /** Response headers not to pass on: the body changed, and a CSP would block the added style. */
    private val skipResponseHeaders = setOf(
        "content-encoding", "content-length", "transfer-encoding",
        "content-security-policy", "content-security-policy-report-only",
    )

    /** Called by the player WebView's client (off the main thread). */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (request.isForMainFrame || request.method != "GET") return null
        if (url.host?.endsWith("youtube.com") != true || url.path?.startsWith("/embed/") != true) return null
        return runCatching { fetchWithStyle(url.toString(), request.requestHeaders) }.getOrNull()
    }

    private fun fetchWithStyle(url: String, headers: Map<String, String>): WebResourceResponse? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        for ((name, value) in headers) {
            if (name.lowercase() !in skipRequestHeaders) conn.setRequestProperty(name, value)
        }
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        try {
            if (conn.responseCode != 200) return null
            val type = conn.contentType ?: return null
            if (!type.startsWith("text/html")) return null
            conn.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val styled = if (html.contains("</head>")) html.replaceFirst("</head>", "$CSS</head>") else CSS + html
            val responseHeaders = conn.headerFields
                .filterKeys { it != null && it.lowercase() !in skipResponseHeaders && it.lowercase() != "set-cookie" }
                .mapValues { it.value.joinToString(", ") }
            return WebResourceResponse(
                "text/html", "utf-8", 200, "OK", responseHeaders,
                ByteArrayInputStream(styled.toByteArray(Charsets.UTF_8)),
            )
        } finally {
            conn.disconnect()
        }
    }
}
