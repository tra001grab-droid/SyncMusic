package com.example.syncmusic

import android.annotation.SuppressLint
import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream

/**
 * Search YouTube without leaving the main screen: shows the mobile YouTube site and, when the
 * user opens a video, hands its id to [onPick] and returns to the results so they can keep browsing.
 * It lives inside MainActivity so the synced YouTube player above it stays visible (and playing).
 */
private const val NO_VIDEO_JS = """
(function () {
  if (window.__syncMusicNoVideo) return;
  window.__syncMusicNoVideo = true;
  var stop = function (v) {
    if (!v || v.tagName !== 'VIDEO') return;
    try { v.muted = true; v.pause(); v.removeAttribute('src'); v.load(); } catch (e) {}
  };
  document.addEventListener('play', function (e) { stop(e.target); }, true);
  document.addEventListener('playing', function (e) { stop(e.target); }, true);
  document.querySelectorAll('video').forEach(stop);
  var css = document.createElement('style');
  css.textContent = 'video { visibility: hidden !important; }';
  (document.head || document.documentElement).appendChild(css);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
class SearchPanel(
    private val activity: Activity,
    private val root: View,
    private val onPick: (videoId: String) -> Unit,
    onClose: () -> Unit,
    onVoice: () -> Unit,
    /** Songs in the phone's library matching a query; these play without internet. */
    private val findInLibrary: (query: String) -> List<PlaylistItem.Local>,
    private val onPickLocal: (PlaylistItem.Local) -> Unit,
) {
    private val localResults: LinearLayout = root.findViewById(R.id.localResults)
    private var web: WebView = root.findViewById(R.id.web)
    private val offlineBanner: TextView = root.findViewById(R.id.offlineBanner)
    private val query: EditText = root.findViewById(R.id.query)
    private val connectivity = activity.getSystemService(ConnectivityManager::class.java)
    private var resultsUrl = "https://m.youtube.com/"
    private var lastPick = ""
    private var lastPickAt = 0L
    private var wasOffline = false
    private var started = false

    // Reload the page by itself once the phone gets internet back.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = activity.runOnUiThread { checkInternet() }
        override fun onLost(network: Network) = activity.runOnUiThread { checkInternet() }
    }

    val isOpen get() = root.visibility == View.VISIBLE

    init {
        setUp(web)
        root.findViewById<Button>(R.id.btnGo).setOnClickListener { search() }
        root.findViewById<Button>(R.id.btnVoice).setOnClickListener { onVoice() }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        root.findViewById<Button>(R.id.btnDone).setOnClickListener { onClose() }
    }

    private fun setUp(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        // The search page never plays anything itself (the synced player above does), but YouTube's
        // mobile site auto-plays previews in the results. Block video data and stop any preview.
        web.webViewClient = object : WebViewClient() {
            // Without this, a crashed/killed WebView renderer makes Android kill the whole app.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                replaceWebView(view)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return null
                if (!host.endsWith("googlevideo.com")) return null
                return WebResourceResponse(
                    "text/plain", "utf-8", 403, "Blocked by Sync Music", emptyMap(), ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(NO_VIDEO_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(NO_VIDEO_JS, null)
            }

            // YouTube's mobile site is a single-page app, so in-page navigation shows up here.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                view.evaluateJavascript(NO_VIDEO_JS, null)
                val isVideo = url.contains("/watch") || url.contains("/shorts/")
                val id = if (isVideo) YouTubeIds.parse(url) else null
                if (id == null) {
                    if (url.contains("/results")) resultsUrl = url
                    return
                }
                // Keep the page's own player silent, then go back to the results list.
                view.evaluateJavascript(NO_VIDEO_JS, null)
                view.post { if (view.canGoBack()) view.goBack() else view.loadUrl(resultsUrl) }
                // The same navigation can be reported more than once.
                if (id == lastPick && now() - lastPickAt < 3000) return
                lastPick = id
                lastPickAt = now()
                Toast.makeText(activity, "▶ កំពុងចាក់…", Toast.LENGTH_SHORT).show()
                onPick(id)
            }
        }
    }

    /** A WebView whose renderer died can't be used again: swap in a fresh one. */
    private fun replaceWebView(dead: WebView) {
        val parent = dead.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(dead)
        val params = dead.layoutParams
        parent.removeView(dead)
        dead.destroy()
        val fresh = WebView(activity).apply { id = R.id.web }
        parent.addView(fresh, index, params)
        setUp(fresh)
        web = fresh
        if (started) fresh.loadUrl(resultsUrl)
    }

    /** Search for [text] (e.g. what the user said with voice search). */
    fun searchFor(text: String) {
        query.setText(text)
        query.setSelection(text.length)
        search()
    }

    /** Library first: if the song is already on the phone, offer that copy and don't touch YouTube. */
    private fun search() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) return
        val matches = findInLibrary(q)
        showLocalResults(q, matches)
        if (matches.isEmpty()) searchYouTube(q)
    }

    private fun searchYouTube(q: String) {
        if (!checkInternet()) return
        web.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(q))
    }

    private fun showLocalResults(q: String, matches: List<PlaylistItem.Local>) {
        localResults.removeAllViews()
        localResults.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
        if (matches.isEmpty()) return
        localResults.addView(TextView(activity).apply {
            text = "📁 មានក្នុងទូរស័ព្ទរួចហើយ (មិនស៊ី Internet):"
            setPadding(0, 8, 0, 4)
        })
        for (item in matches) {
            localResults.addView(Button(activity).apply {
                text = (if (item.isVideo) "🎬 " else "🎵 ") + item.title
                isAllCaps = false
                setOnClickListener { onPickLocal(item) }
            })
        }
        localResults.addView(Button(activity).apply {
            text = "🔍 ស្វែងរកលើ YouTube ផងដែរ"
            isAllCaps = false
            setOnClickListener {
                localResults.visibility = View.GONE
                searchYouTube(q)
            }
        })
    }

    fun show() {
        root.visibility = View.VISIBLE
        if (!started) {
            started = true
            connectivity.registerDefaultNetworkCallback(networkCallback)
            web.loadUrl(resultsUrl)
        }
        checkInternet()
    }

    fun hide() {
        root.visibility = View.GONE
    }

    /** Back button inside the panel: go back in the page first. Returns false if there's nowhere to go. */
    fun goBack(): Boolean {
        if (!web.canGoBack()) return false
        web.goBack()
        return true
    }

    /** Shows the offline banner when the active network can't actually reach the internet. */
    private fun checkInternet(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val online = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
        if (online && wasOffline) web.reload()
        wasOffline = !online
        return online
    }

    fun destroy() {
        if (started) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        web.destroy()
    }
}
