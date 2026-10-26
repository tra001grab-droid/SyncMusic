package com.example.syncmusic

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Search YouTube without leaving the app: shows the mobile YouTube site and, when the
 * user opens a video, returns its id so MainActivity plays it on every phone right away.
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
    }

    private var picked: String? = null
    private lateinit var web: WebView
    private var resultsUrl = "https://m.youtube.com/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        val query = findViewById<EditText>(R.id.query)
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Don't start videos inside the search page.
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webViewClient = object : WebViewClient() {
            // YouTube's mobile site is a single-page app, so in-page navigation shows up here.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                val isVideo = url.contains("/watch") || url.contains("/shorts/")
                val id = if (isVideo) YouTubeIds.parse(url) else null
                if (id == null) {
                    if (url.contains("/results")) resultsUrl = url
                    return
                }
                if (picked != null) return
                picked = id
                view.stopLoading()
                Toast.makeText(this@SearchActivity, "▶ កំពុងចាក់…", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        fun search() {
            val q = query.text.toString().trim()
            if (q.isNotEmpty()) web.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(q))
        }
        findViewById<Button>(R.id.btnGo).setOnClickListener { search() }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(resultsUrl)
    }

    override fun finish() {
        picked?.let { setResult(RESULT_OK, Intent().putExtra(EXTRA_VIDEO_ID, it)) }
        super.finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
