package com.example.syncmusic

import android.content.Context
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.res.Configuration
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

/** What the player area shows for the current item. */
private enum class ItemKind { AUDIO, VIDEO, YOUTUBE }

class MainActivity : AppCompatActivity(), SyncClient.Listener {

    companion object {
        private const val SERVICE_TYPE = "_syncmusic._tcp."
        private const val START_DELAY_MS = 1500L      // time given to every phone to get ready
        private const val POS_INTERVAL_MS = 1000L
        private const val SOFT_ERROR_MS = 15L          // error above this -> nudge speed
        private const val YOUTUBE_NUDGE_MS = 60L       // YouTube gap above this -> 1.25x/0.75x catch-up
        private const val SKIP_SETTLE_MS = 700L        // wait this long after the last skip tap
        private const val READY_TIMEOUT_MS = 60_000L   // max wait for clients before auto-play
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nsd: NsdManager
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var videoView: androidx.media3.ui.PlayerView
    private lateinit var playerContainer: FrameLayout
    private var fullscreen = false
    private var savedVisibility = emptyMap<View, Int>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var searchPanel: SearchPanel

    private lateinit var localPlayer: LocalPlayer
    /** The YouTube player on screen. */
    private lateinit var ytPlayer: YouTubeSyncPlayer
    /** A second, transparent player that preloads the next song; the two swap when it starts. */
    private lateinit var ytSpare: YouTubeSyncPlayer
    private var active: SyncPlayer? = null
    private var pendingStart: Runnable? = null

    private var server: SyncServer? = null
    private var client: SyncClient? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null

    // Host state (main thread only)
    private val playlist = mutableListOf<PlaylistItem>()
    private lateinit var playlistAdapter: ArrayAdapter<String>
    private var currentIndex = -1
    private var waitForClients: Runnable? = null
    private var hosting = false
    private var autoPlayEnabled = true
    /** Host: the upcoming YouTube video being preloaded, and whether the host has it ready. */
    private var nextVideoId: String? = null
    private var hostNextReady = false
    /** Turned off for the session if this phone can't run two YouTube players at once. */
    private var backgroundPreloadAllowed = true
    private val unexpectedPauses = ArrayDeque<Long>()
    private var clientVideoId: String? = null
    /** Skip taps add up ("+5s", "+10s", "+15s"); the jump happens once the taps stop. */
    private var pendingSkipMs = 0L
    private val applySkip = Runnable {
        val total = pendingSkipMs
        pendingSkipMs = 0L
        if (total != 0L) hostSeekBy(total)
    }
    private val hideSkip = Runnable { findViewById<View>(R.id.skipOverlay).visibility = View.GONE }
    private var inForeground = false
    private var backgroundYouTube = true

    // Client state (main thread only)
    private var clientVersion = 0
    private var clientTitle = ""
    private var loaded = false
    private var hostPlaying = false
    /** Recent sync errors (ms); decisions use their median so one noisy reading can't cause a jump. */
    private val syncErrors = ArrayDeque<Long>()
    /** YouTube buffers after every jump; aim this far ahead. Learned per phone. */
    private var youtubeSeekLeadMs = 400L
    private var measuringSeekLead = false
    private var calibrationMs = 0L

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addLocalSongs(uris)
    }


    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) searchPanel.searchFor(text)
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            val s = server ?: return
            val p = active ?: return
            if (p.isActive) {
                // While the host itself is buffering its position isn't moving; reporting it would
                // tell every client to jump to a wrong time.
                if (p.isPlaying) s.broadcast("POS ${s.version} ${p.position} ${now()}")
                updateNextButton()
                main.postDelayed(this, POS_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReport()
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        status = findViewById(R.id.status)
        nowPlaying = findViewById(R.id.nowPlaying)
        playerContainer = findViewById(R.id.playerContainer)
        findViewById<Button>(R.id.btnFullscreen).setOnClickListener { setFullscreen(!fullscreen) }
        // Not registered as a lifecycle observer: the library would pause the video on every
        // onStop (screen off). onStop() below decides instead, based on the user's setting.

        // Only the host advances the playlist; clients follow its ITEM messages.
        localPlayer = LocalPlayer(this) { onItemEnded() }
        videoView = findViewById(R.id.videoView)
        localPlayer.attach(videoView)
        ytPlayer = makeYouTubePlayer(findViewById(R.id.youtubeView))
        ytSpare = makeYouTubePlayer(findViewById(R.id.youtubeView2))
        // Taps on the video would pause YouTube behind the app's back; route them through the app instead.
        findViewById<View>(R.id.playerTouchGuard).setOnClickListener {
            when {
                server != null -> if (active?.isActive == true) hostPause() else hostPlay()
                else -> Toast.makeText(this, "Host ជាអ្នកបញ្ជាការចាក់", Toast.LENGTH_SHORT).show()
            }
        }

        backgroundYouTube = prefs.getBoolean("yt_background", true)
        ytPlayer.view.enableBackgroundPlayback(backgroundYouTube)
        ytSpare.view.enableBackgroundPlayback(backgroundYouTube)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBackground).apply {
            isChecked = backgroundYouTube
            setOnCheckedChangeListener { _, checked ->
                backgroundYouTube = checked
                ytPlayer.view.enableBackgroundPlayback(checked)
                ytSpare.view.enableBackgroundPlayback(checked)
                prefs.edit().putBoolean("yt_background", checked).apply()
            }
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("syncmusic").apply { setReferenceCounted(false); acquire() }

        findViewById<Button>(R.id.btnHost).setOnClickListener { startHost() }
        findViewById<Button>(R.id.btnJoin).setOnClickListener { startDiscovery() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val text = findViewById<EditText>(R.id.manualAddress).text.toString().trim()
            val host = text.substringBefore(":")
            val port = text.substringAfter(":", "").toIntOrNull()
            if (host.isEmpty() || port == null) setStatus("សូមវាយជាទម្រង់ IP:port") else connect(listOf(host), port)
        }

        findViewById<Button>(R.id.btnAddSongs).setOnClickListener { pickAudio.launch(arrayOf("audio/*", "video/*")) }
        findViewById<Button>(R.id.btnAddYoutube).setOnClickListener { addYouTube() }
        searchPanel = SearchPanel(
            this,
            findViewById(R.id.searchPanel),
            onPick = { id -> hostSelect(addYouTubeId(id), autoPlay = true) },
            onClose = { showSearch(false) },
            onVoice = { startVoiceSearch() },
            findInLibrary = { q -> LibrarySearch.find(q, playlist) },
            onPickLocal = { item ->
                val index = playlist.indexOfFirst { it === item }
                if (index >= 0) hostSelect(index, autoPlay = true)
            },
        )
        searchPanel.hide()
        sizePlayer()
        findViewById<Button>(R.id.btnSearch).setOnClickListener { showSearch(true) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen -> setFullscreen(false)
                    !searchPanel.isOpen -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    !searchPanel.goBack() -> showSearch(false)
                }
            }
        })
        findViewById<Button>(R.id.btnPlay).setOnClickListener { hostPlay() }
        findViewById<Button>(R.id.btnPause).setOnClickListener { hostPause() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener {
            if (currentIndex > 0) hostSelect(currentIndex - 1, autoPlay = true)
        }
        findViewById<Button>(R.id.btnBack5).setOnClickListener { queueSkip(-5000) }
        findViewById<Button>(R.id.btnForward5).setOnClickListener { queueSkip(5000) }
        autoPlayEnabled = prefs.getBoolean("autoplay", true)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoplay).apply {
            isChecked = autoPlayEnabled
            setOnCheckedChangeListener { _, checked ->
                autoPlayEnabled = checked
                prefs.edit().putBoolean("autoplay", checked).apply()
            }
        }
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            if (currentIndex + 1 < playlist.size) hostSelect(currentIndex + 1, autoPlay = true)
        }

        playlistAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        findViewById<ListView>(R.id.playlist).apply {
            adapter = playlistAdapter
            setOnItemClickListener { _, _, position, _ -> hostSelect(position, autoPlay = true) }
            setOnItemLongClickListener { _, _, position, _ -> removeItem(position); true }
        }

        val label = findViewById<TextView>(R.id.calibrationLabel)
        findViewById<SeekBar>(R.id.calibration).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                calibrationMs = (progress - 300).toLong()
                label.text = "កែតម្រូវពេល (Latency): $calibrationMs ms"
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        if (intent?.action == Intent.ACTION_SEND) handleShare(intent) else offerLastSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A YouTube link shared from another app (YouTube "Share" -> Sync Music) goes into the playlist. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = YouTubeIds.parse(text)
        when {
            id == null -> Toast.makeText(this, "Link YouTube មិនត្រឹមត្រូវ", Toast.LENGTH_SHORT).show()
            client != null -> Toast.makeText(this, "មានតែ Host ទេ ដែលអាចបន្ថែមបទចម្រៀង", Toast.LENGTH_LONG).show()
            else -> {
                startHost()
                addYouTubeId(id)
                Toast.makeText(this, "✔ បានបន្ថែមទៅបញ្ជី", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setStatus(text: String) {
        main.post { status.text = text }
    }

    private fun showPanel(host: Boolean) {
        findViewById<View>(R.id.modePanel).visibility = View.GONE
        findViewById<View>(R.id.hostPanel).visibility = if (host) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hostControls).visibility = if (host) View.VISIBLE else View.GONE
        findViewById<View>(R.id.clientPanel).visibility = if (host) View.GONE else View.VISIBLE
    }

    /** Speak a song or singer name in Khmer; uses the phone's speech recognizer (Google). */
    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "km-KH")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "km-KH")
            .putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "km-KH")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "និយាយឈ្មោះបទចម្រៀង ឬអ្នកចម្រៀង")
        try {
            voiceSearch.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "ទូរស័ព្ទនេះមិនមានមុខងារស្គាល់សំឡេងទេ — សូមដំឡើង/បើក app Google", Toast.LENGTH_LONG).show()
        }
    }

    /** Search replaces the playlist area; the player above stays visible so YouTube keeps playing. */
    private fun showSearch(open: Boolean) {
        findViewById<View>(R.id.hostPanel).visibility = if (open) View.GONE else View.VISIBLE
        if (open) searchPanel.show() else searchPanel.hide()
        sizePlayer()
    }

    /**
     * Full width normally; about half width while searching so the results get the space.
     * Sizes are explicit 16:9 so the player can never grow and push the controls off screen.
     */
    private fun sizePlayer() {
        if (fullscreen) return
        val metrics = resources.displayMetrics
        val screenWidth = minOf(metrics.widthPixels, metrics.heightPixels)  // the app is portrait
        val padding = (32 * metrics.density).toInt()
        val width = if (searchPanel.isOpen) screenWidth * 11 / 20 else screenWidth - padding
        // Exact height on the container too: with wrap_content, its match_parent tap guard would
        // stretch it to all free space and push the controls off screen.
        playerContainer.layoutParams = (playerContainer.layoutParams as LinearLayout.LayoutParams).apply {
            this.width = width
            height = width * 9 / 16
            weight = 0f
        }
        for (v in listOf(ytPlayer.view, ytSpare.view)) {
            v.layoutParams = v.layoutParams.apply {
                this.width = ViewGroup.LayoutParams.MATCH_PARENT
                height = width * 9 / 16
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizePlayer()
    }

    /** Our own fullscreen: only the player, landscape, system bars hidden. The WebView is never moved. */
    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        val root = findViewById<LinearLayout>(R.id.root)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        val button = findViewById<Button>(R.id.btnFullscreen)
        if (on) {
            // Keep the host's control bar under the video in fullscreen too.
            val controls = findViewById<View>(R.id.hostControls)
            savedVisibility = (0 until root.childCount).map { root.getChildAt(it) }
                .filter { it !== playerContainer && it !== controls }
                .associateWith { it.visibility }
            savedVisibility.keys.forEach { it.visibility = View.GONE }
            root.setPadding(0, 0, 0, 0)
            playerContainer.layoutParams = (playerContainer.layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = 0
                weight = 1f
            }
            for (v in listOf(ytPlayer.view, ytSpare.view)) {
                v.layoutParams = v.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            }
            supportActionBar?.hide()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            button.text = "✕"
        } else {
            savedVisibility.forEach { (view, visibility) -> view.visibility = visibility }
            val pad = (16 * resources.displayMetrics.density).toInt()
            root.setPadding(pad, pad, pad, pad)
            supportActionBar?.show()
            WindowCompat.setDecorFitsSystemWindows(window, true)
            bars.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            button.text = "⛶"
            // Width depends on the (now portrait) screen size, so wait for the rotation first.
            root.post { sizePlayer() }
        }
    }

    private fun showItem(title: String?, kind: ItemKind) {
        nowPlaying.visibility = if (title == null) View.GONE else View.VISIBLE
        nowPlaying.text = when (kind) {
            ItemKind.YOUTUBE -> "▶ YouTube: $title"
            ItemKind.VIDEO -> "🎬 $title"
            ItemKind.AUDIO -> "🎵 $title"
        }
        val showPlayer = title != null && kind != ItemKind.AUDIO
        if (!showPlayer) setFullscreen(false)
        playerContainer.visibility = if (showPlayer) View.VISIBLE else View.GONE
        videoView.visibility = if (kind == ItemKind.VIDEO) View.VISIBLE else View.GONE
    }

    private fun kindOf(item: PlaylistItem.Local) = if (item.isVideo) ItemKind.VIDEO else ItemKind.AUDIO

    // ---------------------------------------------------------------- Host

    private fun startHost() {
        if (hosting) return
        hosting = true
        prefs.edit().putString("last_role", "host").apply()
        showPanel(host = true)
        if (playlist.isEmpty()) {
            playlist.addAll(PlaylistStore.load(File(filesDir, "playlist.json"), filesDir))
            refreshPlaylist()
        }
        startBackgroundService()
        thread {
            val s = SyncServer(hostId(), Build.MODEL) { event -> setStatus(hostStatus(event)) }
            s.start()
            server = s
            val info = NsdServiceInfo().apply {
                serviceName = "SyncMusic"
                serviceType = SERVICE_TYPE
                port = s.port
                // The address NSD resolves can be the mobile-data one, so publish every LAN address.
                setAttribute("ips", Network.lanAddresses().joinToString(","))
                // Lets a client that connected before find this same phone again.
                setAttribute("id", hostId())
            }
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            registration = reg
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            setStatus(hostStatus("រួចរាល់ — សូមបន្ថែមបទចម្រៀង ឬ YouTube"))
        }
    }

    private fun hostStatus(event: String): String {
        val s = server
        val addresses = Network.lanAddresses().joinToString("  ") { "$it:${s?.port ?: "?"}" }.ifEmpty { "?" }
        return "Host: $addresses · ទូរស័ព្ទភ្ជាប់: ${s?.clientCount ?: 0}\n$event"
    }

    private fun refreshPlaylist() {
        playlistAdapter.clear()
        playlistAdapter.addAll(playlist.mapIndexed { i, item ->
            val icon = when {
                item is PlaylistItem.YouTube -> "📺"
                item is PlaylistItem.Local && item.isVideo -> "🎬"
                else -> "🎵"
            }
            (if (i == currentIndex) "▶ " else "    ") + "$icon ${item.title}"
        })
        // The playlist is the host's library: keep it across app restarts.
        if (hosting) runCatching { PlaylistStore.save(File(filesDir, "playlist.json"), playlist) }
    }

    private fun addLocalSongs(uris: List<Uri>) {
        setStatus(hostStatus("កំពុងបន្ថែម ${uris.size} បទ…"))
        thread {
            for (uri in uris) {
                val title = displayName(uri) ?: "បទចម្រៀង"
                val isVideo = contentResolver.getType(uri)?.startsWith("video/") == true
                val file = File(filesDir, "host_${UUID.randomUUID()}")
                try {
                    contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                } catch (e: Exception) {
                    setStatus(hostStatus("មិនអាចបើក $title"))
                    continue
                }
                main.post {
                    playlist.add(PlaylistItem.Local(file, title.substringBeforeLast('.'), isVideo))
                    refreshPlaylist()
                }
            }
            setStatus(hostStatus("បានបន្ថែមបទចម្រៀង"))
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun addYouTube() {
        val input = findViewById<EditText>(R.id.youtubeUrl)
        val id = YouTubeIds.parse(input.text.toString())
        if (id == null) {
            Toast.makeText(this, "Link YouTube មិនត្រឹមត្រូវ", Toast.LENGTH_SHORT).show()
            return
        }
        input.text.clear()
        addYouTubeId(id)
    }

    /** Adds a YouTube video to the playlist and returns its index. */
    private fun addYouTubeId(id: String): Int {
        val item = PlaylistItem.YouTube(id, id)
        playlist.add(item)
        refreshPlaylist()
        thread {
            val title = YouTubeIds.fetchTitle(id) ?: return@thread
            main.post {
                item.title = title
                refreshPlaylist()
                if (playlist.getOrNull(currentIndex) === item) showItem(title, ItemKind.YOUTUBE)
            }
        }
        return playlist.lastIndex
    }

    private fun removeItem(index: Int) {
        if (index == currentIndex) {
            hostPause()
            currentIndex = -1
            active = null
            ytPlayer.stop()
            resetNext()
            showItem(null, ItemKind.AUDIO)
        } else if (index < currentIndex) {
            currentIndex--
        }
        (playlist.removeAt(index) as? PlaylistItem.Local)?.file?.delete()
        refreshPlaylist()
    }

    private fun hostSelect(index: Int, autoPlay: Boolean, startAtMs: Long? = null) {
        val s = server ?: return
        if (index !in playlist.indices) return
        hostStopCurrent()
        currentIndex = index
        val item = playlist[index]
        val version = s.setItem(item)
        refreshPlaylist()
        // Already preloaded as "next" in the spare player? Swap it in and skip the wait.
        val preloaded = item is PlaylistItem.YouTube && ytSpare.isPrepared(item.videoId)
        if (preloaded) swapYouTubePlayers()
        resetNext()
        when (item) {
            is PlaylistItem.Local -> {
                ytPlayer.stop()
                showItem(item.title, kindOf(item))
                localPlayer.load(item.file)
                active = localPlayer
                if (autoPlay) startWhenClientsReady(version, startAtMs)
            }
            is PlaylistItem.YouTube -> {
                localPlayer.pause()
                showItem(item.title, ItemKind.YOUTUBE)
                active = ytPlayer
                if (preloaded) {
                    if (autoPlay) startWhenClientsReady(version, startAtMs)
                } else {
                    showPreload(true)
                    setStatus(hostStatus("⏳ កំពុងទាញយក…"))
                    ytPlayer.load(
                        item.videoId,
                        ready = {
                            showPreload(false)
                            if (autoPlay) startWhenClientsReady(version, startAtMs)
                        },
                    )
                }
            }
        }
    }

    /** Wait until every client has the new item ready (or a timeout), then start everyone together. */
    private fun startWhenClientsReady(version: Int, fromMs: Long? = null) {
        val s = server ?: return
        val startedAt = now()
        cancelWait()
        val check = object : Runnable {
            override fun run() {
                if (s.version != version) return
                val ready = s.readyCount(version)
                if (ready >= s.clientCount || now() - startedAt > READY_TIMEOUT_MS) {
                    waitForClients = null
                    hostPlay(fromMs)
                } else {
                    setStatus(hostStatus("⏳ កំពុងទាញយក… ($ready/${s.clientCount} ទូរស័ព្ទរួចរាល់)"))
                    main.postDelayed(this, 200)
                }
            }
        }
        waitForClients = check
        main.post(check)
    }

    private fun cancelWait() {
        waitForClients?.let { main.removeCallbacks(it) }
        waitForClients = null
    }

    private fun hostPlay(fromMs: Long? = null) {
        val s = server ?: return
        if (currentIndex < 0) {
            if (playlist.isNotEmpty()) hostSelect(0, autoPlay = true)
            return
        }
        val p = active ?: return
        cancelWait()
        val pos = fromMs ?: p.position
        val startAt = now() + START_DELAY_MS
        s.broadcast("PLAY ${s.version} $pos $startAt")
        main.removeCallbacks(positionTicker)
        p.cueAt(pos)
        scheduleStart(startAt - now()) {
            p.startFrom(pos)
            main.postDelayed(positionTicker, POS_INTERVAL_MS)
        }
        setStatus(hostStatus("✅ កំពុង sync"))
    }

    private fun queueSkip(deltaMs: Long) {
        if (currentIndex < 0) return
        pendingSkipMs += deltaMs
        showSkip(pendingSkipMs)
        server?.broadcast("SKIP $pendingSkipMs")
        main.removeCallbacks(applySkip)
        main.postDelayed(applySkip, SKIP_SETTLE_MS)
    }

    /** "⏩ +15s" / "⏪ -10s" over the video (or a toast when no video is shown). */
    private fun showSkip(totalMs: Long) {
        val text = if (totalMs >= 0) "⏩ +${totalMs / 1000}s" else "⏪ ${totalMs / 1000}s"
        if (playerContainer.visibility != View.VISIBLE) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<TextView>(R.id.skipOverlay).apply {
            this.text = text
            visibility = View.VISIBLE
        }
        main.removeCallbacks(hideSkip)
        main.postDelayed(hideSkip, SKIP_SETTLE_MS + 800)
    }

    override fun onSkip(totalMs: Long) {
        main.post { showSkip(totalMs) }
    }

    /** ⏪ / ⏩: every phone jumps to the new position at the same moment. */
    private fun hostSeekBy(deltaMs: Long) {
        val p = active ?: return
        if (currentIndex < 0) return
        val target = (p.position + deltaMs).coerceAtLeast(0L)
        if (p.isActive) {
            hostPlay(target)
        } else {
            // Paused: just move; everyone starts from here on the next ▶.
            p.seekTo(target)
            setStatus(hostStatus("⏸ ${formatTime(target)}"))
        }
    }

    private fun formatTime(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun hostStopCurrent() {
        showPreload(false)
        main.removeCallbacks(applySkip)
        pendingSkipMs = 0L
        cancelStart()
        cancelWait()
        main.removeCallbacks(positionTicker)
        active?.pause()
    }

    private fun hostPause() {
        hostStopCurrent()
        server?.broadcast("PAUSE")
        setStatus(hostStatus("បានផ្អាក"))
    }

    private fun onItemEnded() {
        if (server == null) return
        if (currentIndex + 1 < playlist.size) {
            hostSelect(currentIndex + 1, autoPlay = true)
            return
        }
        val last = playlist.getOrNull(currentIndex)
        when {
            !autoPlayEnabled || last == null -> {
                hostPause()
                setStatus(hostStatus("ចប់បញ្ជីបទចម្រៀង"))
            }
            last is PlaylistItem.YouTube -> autoPlayNextYouTube(last)
            // Local songs: start the playlist again.
            else -> hostSelect(0, autoPlay = true)
        }
    }

    /** End of playlist with Auto play on: find a related song on YouTube and keep going. */
    private fun autoPlayNextYouTube(last: PlaylistItem.YouTube) {
        val endedIndex = currentIndex
        val exclude = playlist.filterIsInstance<PlaylistItem.YouTube>().map { it.videoId }.toSet()
        setStatus(hostStatus("🔁 Auto play: កំពុងរកបទបន្ទាប់…"))
        thread {
            val next = YouTubeIds.fetchNext(last.videoId, last.title, exclude)
            main.post {
                if (currentIndex != endedIndex) return@post  // user picked something meanwhile
                if (next != null) {
                    hostSelect(addYouTubeId(next), autoPlay = true)
                } else {
                    // Couldn't reach YouTube's list: repeat the playlist instead of stopping.
                    hostSelect(0, autoPlay = true)
                }
            }
        }
    }

    // -------------------------------------------------------------- Client

    /** Finds a host on the Wi-Fi; with [onlyHostId], only that (remembered) host. */
    private fun startDiscovery(onlyHostId: String? = null) {
        showPanel(host = false)
        stopDiscovery()
        setStatus("កំពុងស្វែងរក Host នៅក្នុង Wi-Fi…")
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) = setStatus("ស្វែងរកបរាជ័យ ($code)")
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (client != null) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.let { String(it) }
                        if (onlyHostId != null && id != onlyHostId) return
                        val advertised = info.attributes["ips"]?.let { String(it).split(",") }.orEmpty()
                        val resolved = listOfNotNull(info.host?.hostAddress)
                        val candidates = Network.preferSameSubnet((advertised + resolved).filter { it.isNotBlank() })
                        if (candidates.isNotEmpty()) main.post { connect(candidates, info.port) }
                    }
                })
            }
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun connect(hosts: List<String>, port: Int) {
        if (client != null) return
        showPanel(host = false)
        stopDiscovery()
        client = SyncClient(hosts, port, File(filesDir, "library"), this).also { it.start() }
        startBackgroundService()
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    override fun onStatus(text: String) = setStatus(text)

    override fun onHello(hostId: String, hostName: String, address: String, port: Int) {
        main.post {
            reconnectingHostId = null
            // The address that worked first, then earlier ones (the host may switch networks).
            val ips = (listOf(address) + prefs.getString("known_host_ips", "").orEmpty().split(","))
                .filter { it.isNotBlank() }.distinct().take(4)
            prefs.edit()
                .putString("last_role", "client")
                .putString("known_host_id", hostId)
                .putString("known_host_name", hostName)
                .putString("known_host_ips", ips.joinToString(","))
                .putInt("known_host_port", port)
                .apply()
            setStatus("✔ បានភ្ជាប់ទៅ $hostName")
        }
    }

    // ------------------------------------------------------- remembered devices

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    /** Set while trying a remembered host's saved address; on failure we search for it instead. */
    private var reconnectingHostId: String? = null

    /** This phone's permanent id when it is the host. */
    private fun hostId(): String = prefs.getString("host_id", null)
        ?: UUID.randomUUID().toString().take(8).also { prefs.edit().putString("host_id", it).apply() }

    /** At launch: offer to resume as host, or to reconnect to the host used last time, in one tap. */
    private fun offerLastSession() {
        when (prefs.getString("last_role", null)) {
            "host" -> AlertDialog.Builder(this)
                .setTitle("Sync Music")
                .setMessage("ចាប់ផ្ដើមជា Host ដូចលើកមុន?")
                .setPositiveButton("✔ អនុញ្ញាត") { _, _ -> startHost() }
                .setNegativeButton("ទេ", null)
                .show()
            "client" -> {
                val id = prefs.getString("known_host_id", null) ?: return
                val name = prefs.getString("known_host_name", "Host")
                AlertDialog.Builder(this)
                    .setTitle("Sync Music")
                    .setMessage("ភ្ជាប់ទៅ $name ដែលធ្លាប់ភ្ជាប់?")
                    .setPositiveButton("✔ អនុញ្ញាត") { _, _ -> reconnectKnownHost(id) }
                    .setNegativeButton("ទេ", null)
                    .show()
            }
        }
    }

    private fun reconnectKnownHost(id: String) {
        val ips = prefs.getString("known_host_ips", "").orEmpty().split(",").filter { it.isNotBlank() }
        val port = prefs.getInt("known_host_port", SyncServer.PREFERRED_PORT)
        if (ips.isEmpty()) {
            startDiscovery(onlyHostId = id)
            return
        }
        reconnectingHostId = id
        connect(Network.preferSameSubnet(ips), port)
    }

    override fun onDownloading(version: Int) {
        main.post { if (version == clientVersion) setStatus("⏳ កំពុងទាញយកពី Host (លើកដំបូងតែប៉ុណ្ណោះ)…") }
    }

    override fun onDisconnected(reason: String) {
        main.post {
            client?.stop()
            client = null
            // The remembered address didn't work (new IP?): look for that same host on the Wi-Fi.
            val knownId = reconnectingHostId
            if (knownId != null) {
                reconnectingHostId = null
                startDiscovery(onlyHostId = knownId)
                setStatus("កំពុងស្វែងរក ${prefs.getString("known_host_name", "Host")}…")
                return@post
            }
            loaded = false
            hostPlaying = false
            cancelStart()
            active?.pause()
            findViewById<View>(R.id.modePanel).visibility = View.VISIBLE
            status.text = "$reason\nសូមព្យាយាមម្ដងទៀត ឬវាយ IP:port ដែលបង្ហាញលើ Host"
        }
    }

    override fun onItemChanging(version: Int, title: String) {
        main.post {
            clientVersion = version
            clientTitle = title
            loaded = false
            showPreload(false)
            hostPlaying = false
            cancelStart()
            resetSync()
            localPlayer.pause()
            localPlayer.setSpeed(1f)
            ytPlayer.stop()
            nowPlaying.visibility = View.VISIBLE
            nowPlaying.text = title
        }
    }

    override fun onLocalItem(version: Int, file: File, isVideo: Boolean) {
        main.post {
            if (version != clientVersion) return@post
            showItem(clientTitle, if (isVideo) ItemKind.VIDEO else ItemKind.AUDIO)
            localPlayer.load(file)
            active = localPlayer
            markLoaded(version)
        }
    }

    override fun onYouTubeItem(version: Int, videoId: String) {
        main.post {
            if (version != clientVersion) return@post
            showItem(clientTitle, ItemKind.YOUTUBE)
            clientVideoId = videoId
            if (ytSpare.isPrepared(videoId)) {
                // Preloaded in the background as "next": ready right away.
                swapYouTubePlayers()
                active = ytPlayer
                markLoaded(version)
            } else {
                ytSpare.stop()
                active = ytPlayer
                showPreload(true)
                setStatus("⏳ កំពុងទាញយក…")
                var lastSent = -1
                ytPlayer.load(
                    videoId,
                    ready = { if (version == clientVersion) markLoaded(version) },
                    progress = { pct ->
                        if (version == clientVersion && pct != lastSent) {
                            lastSent = pct
                            client?.sendProgress(version, pct)
                        }
                    },
                )
            }
            // The host may have announced the item before it knew the title.
            if (clientTitle == videoId) thread {
                val title = YouTubeIds.fetchTitle(videoId) ?: return@thread
                main.post { if (version == clientVersion) showItem(title, ItemKind.YOUTUBE) }
            }
        }
    }

    /** YouTube paused without the app asking (tap on video, audio focus lost, …). */
    private fun onYouTubePausedByUser() {
        // Taps on the video go through the tap guard, so this pause came from elsewhere.
        if (ytSpare.isPreloading) {
            // The two YouTube players fight over the phone's audio focus: give up preloading here.
            disableBackgroundPreload()
            resumeYouTubeSoon()
            return
        }
        val t = now()
        unexpectedPauses.addLast(t)
        while (unexpectedPauses.isNotEmpty() && t - unexpectedPauses.first() > 15_000) unexpectedPauses.removeFirst()
        if (unexpectedPauses.size >= 3) {
            // Something keeps taking the sound (a phone call, another app): stop fighting it.
            unexpectedPauses.clear()
            if (server != null) hostPause()
            return
        }
        resumeYouTubeSoon()
    }

    private fun resumeYouTubeSoon() {
        val shouldPlay = if (server != null) ytPlayer.isActive else hostPlaying && canPlay(ytPlayer)
        if (shouldPlay) main.postDelayed({ ytPlayer.resume() }, 300)
    }

    private fun disableBackgroundPreload() {
        if (!backgroundPreloadAllowed) return
        backgroundPreloadAllowed = false
        ytSpare.stop()
        if (server != null) {
            nextVideoId = null
            hostNextReady = false
            server?.announceNext(null)
            updateNextButton()
        }
        Toast.makeText(this, "ទូរស័ព្ទនេះមិនអាចទាញយកបទបន្ទាប់ទុកមុនបានទេ — បានបិទមុខងារនេះ", Toast.LENGTH_LONG).show()
    }

    private fun markLoaded(version: Int) {
        loaded = true
        showPreload(false)
        client?.sendReady(version)
        // If the host is already playing, the next POS message starts us in sync.
        setStatus("✔ រួចរាល់ — រង់ចាំទូរស័ព្ទផ្សេងទៀត…")
    }

    override fun onPlay(version: Int, posMs: Long, localStartTime: Long) {
        main.post {
            if (version != clientVersion) return@post
            hostPlaying = true
            val p = active ?: return@post
            if (!loaded || !canPlay(p)) return@post
            cancelStart()
            p.setSpeed(1f)
            val delay = localStartTime - now()
            if (delay > 0) {
                p.cueAt(posMs + calibrationMs)
                scheduleStart(delay) { p.startFrom(posMs + calibrationMs) }
            } else {
                // Joining late: YouTube will buffer after this jump, so aim a bit ahead.
                p.startFrom(posMs - delay + calibrationMs + if (p === ytPlayer) youtubeSeekLeadMs else 0L)
            }
            resetSync()
            setStatus("កំពុងចាក់ (sync) — RTT ${client?.bestRtt} ms")
        }
    }

    override fun onHostPause() {
        main.post {
            hostPlaying = false
            cancelStart()
            resetSync()
            active?.pause()
            active?.setSpeed(1f)
            setStatus("បានផ្អាក")
        }
    }

    override fun onPosition(version: Int, posMs: Long, localTime: Long) {
        main.post {
            if (version != clientVersion || !loaded) return@post
            val p = active ?: return@post
            hostPlaying = true
            if (pendingStart != null || !canPlay(p)) return@post
            val youtube = p === ytPlayer
            val expected = posMs + (now() - localTime) + calibrationMs
            if (!p.isActive) {
                p.startFrom(expected + if (youtube) youtubeSeekLeadMs else 0L)
                resetSync()
                return@post
            }
            // Buffering, or just resumed: the position isn't trustworthy yet, so don't react.
            if (!p.isPlaying || p.steadyMs < if (youtube) 1500L else 300L) {
                setStatus("កំពុង buffer…")
                return@post
            }
            syncErrors.addLast(p.position - expected)
            if (syncErrors.size > 3) syncErrors.removeFirst()
            if (syncErrors.size < if (youtube) 3 else 2) return@post
            val error = syncErrors.sorted()[syncErrors.size / 2]

            // First steady reading after a jump shows how far buffering left us behind; learn it.
            if (measuringSeekLead && youtube) {
                youtubeSeekLeadMs = (youtubeSeekLeadMs - error * 7 / 10).coerceIn(0L, 3000L)
                measuringSeekLead = false
            }
            when {
                abs(error) > p.hardSeekThresholdMs -> {
                    p.setSpeed(1f)
                    p.seekTo(expected + if (youtube) youtubeSeekLeadMs else 0L)
                    resetSync()
                    measuringSeekLead = youtube
                }
                // Small error: play slightly faster/slower (pitch is preserved) until aligned.
                p.supportsSpeed && abs(error) > SOFT_ERROR_MS ->
                    p.setSpeed((1f - error / 2000f).coerceIn(0.97f, 1.03f))
                youtube && abs(error) > YOUTUBE_NUDGE_MS -> {
                    ytPlayer.nudge(error)
                    resetSync()
                }
                else -> p.setSpeed(1f)
            }
            val synced = abs(error) <= if (youtube) YOUTUBE_NUDGE_MS else SOFT_ERROR_MS * 2
            val state = if (synced) "✅ កំពុង sync" else "⏳ កំពុងតម្រូវ…"
            setStatus("$state\nគម្លាត: $error ms · RTT ${client?.bestRtt} ms")
        }
    }

    /** "កំពុងទាញយក" with a spinner over the video while it preloads. */
    private fun showPreload(show: Boolean) {
        findViewById<View>(R.id.preloadOverlay).visibility = if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------- next-song preload

    private fun makeYouTubePlayer(view: YouTubePlayerView): YouTubeSyncPlayer {
        lateinit var p: YouTubeSyncPlayer
        // Only the player on screen may drive the app; the spare one just preloads.
        p = YouTubeSyncPlayer(
            view,
            onEnded = { if (p === ytPlayer) onItemEnded() },
            onUnexpectedPause = { if (p === ytPlayer) onYouTubePausedByUser() },
            onFullyLoaded = { if (p === ytPlayer) prepareNext() },
        )
        // Without this, a crashed/killed WebView renderer makes Android kill the whole app.
        findWebView(view)?.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post { rebuildYouTubePlayer(p) }
                return true
            }
        }
        return p
    }

    private fun findWebView(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWebView(v.getChildAt(it)) }
        else -> null
    }

    /** The player's WebView died: put a fresh player in its place and pick up where we were. */
    private fun rebuildYouTubePlayer(dead: YouTubeSyncPlayer) {
        if (dead !== ytPlayer && dead !== ytSpare) return
        val old = dead.view
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        val params = old.layoutParams
        parent.removeView(old)
        runCatching { old.release() }
        val fresh = layoutInflater.inflate(R.layout.view_youtube_player, parent, false) as YouTubePlayerView
        fresh.alpha = old.alpha
        parent.addView(fresh, index, params)
        fresh.enableBackgroundPlayback(backgroundYouTube)
        val replacement = makeYouTubePlayer(fresh)
        if (dead === ytSpare) {
            ytSpare = replacement
            if (server != null) {
                nextVideoId = null
                hostNextReady = false
                updateNextButton()
            }
            return
        }
        val wasActive = active === dead
        ytPlayer = replacement
        if (!wasActive) return
        active = ytPlayer
        setStatus("⚠ YouTube player ត្រូវចាប់ផ្ដើមឡើងវិញ…")
        // Both YouTube WebViews die together; reload only after both have been replaced.
        if (server != null) {
            // Reload the current song on every phone, keeping the position.
            val resumeAt = dead.position
            val index = currentIndex
            main.postDelayed({ if (currentIndex == index) hostSelect(index, autoPlay = true, startAtMs = resumeAt) }, 500)
        } else {
            val id = clientVideoId ?: return
            loaded = false
            main.postDelayed({ onYouTubeItem(clientVersion, id) }, 500)
        }
    }

    /** If the app crashes, show the error on the next start so it can be reported. */
    private fun installCrashReport() {
        val report = File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                report.writeText("Sync Music $version\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
        if (report.exists()) {
            val text = runCatching { report.readText() }.getOrDefault("")
            report.delete()
            AlertDialog.Builder(this)
                .setTitle("App បានគាំងពីលើកមុន")
                .setMessage("សូមថតអេក្រង់នេះផ្ញើមក ដើម្បីកែកំហុស:\n\n" + text.take(1200))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** The spare player (holding the preloaded next song) becomes the one on screen. */
    private fun swapYouTubePlayers() {
        val old = ytPlayer
        ytPlayer = ytSpare
        ytSpare = old
        old.stop()
        ytPlayer.view.alpha = 1f
        ytSpare.view.alpha = 0f
    }

    /** Host: the current song is downloaded, so preload 20% of the next one on every phone. */
    private fun prepareNext() {
        if (server == null || nextVideoId != null || !backgroundPreloadAllowed) return
        val current = playlist.getOrNull(currentIndex) as? PlaylistItem.YouTube ?: return
        val next = playlist.getOrNull(currentIndex + 1)
        when {
            next is PlaylistItem.YouTube -> preloadNext(next.videoId)
            next == null && autoPlayEnabled -> {
                // End of the playlist: pick the auto play song now so it can be preloaded too.
                val index = currentIndex
                val exclude = playlist.filterIsInstance<PlaylistItem.YouTube>().map { it.videoId }.toSet()
                thread {
                    val id = YouTubeIds.fetchNext(current.videoId, current.title, exclude) ?: return@thread
                    main.post {
                        if (currentIndex != index || currentIndex != playlist.lastIndex || nextVideoId != null) return@post
                        addYouTubeId(id)
                        preloadNext(id)
                    }
                }
            }
        }
    }

    private fun preloadNext(videoId: String) {
        nextVideoId = videoId
        hostNextReady = false
        updateNextButton()
        ytSpare.load(videoId, ready = {
            if (nextVideoId == videoId) {
                hostNextReady = true
                updateNextButton()
            }
        })
        server?.announceNext(videoId)
    }

    private fun resetNext() {
        nextVideoId = null
        hostNextReady = false
        ytSpare.stop()
        server?.announceNext(null)
        updateNextButton()
    }

    /** ⏭ turns green once the next song is preloaded on the host and every client. */
    private fun updateNextButton() {
        val s = server
        val id = nextVideoId
        val ready = s != null && id != null && hostNextReady && s.nextReadyCount(id) >= s.clientCount
        findViewById<Button>(R.id.btnNext).backgroundTintList =
            if (ready) ColorStateList.valueOf(0xFF2E7D32.toInt()) else null
    }

    override fun onNext(videoId: String) {
        main.post {
            if (!backgroundPreloadAllowed) return@post
            if (ytSpare.isPrepared(videoId)) {
                client?.sendNextReady(videoId)
            } else if (!ytSpare.hasVideo(videoId)) {
                ytSpare.load(videoId, ready = { client?.sendNextReady(videoId) })
            }
        }
    }

    private fun resetSync() {
        syncErrors.clear()
        measuringSeekLead = false
    }

    // ---------------------------------------------------------------- misc

    /** Local songs keep playing with the screen off (see PlaybackService). */
    private fun startBackgroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PlaybackService.start(this)
    }

    // YouTube in the background is optional (switch on the main screen): YouTube's terms don't
    // allow it, so an app using it can't be published on the Play Store.
    private fun canPlay(p: SyncPlayer) = p !== ytPlayer || inForeground || backgroundYouTube

    override fun onStart() {
        super.onStart()
        inForeground = true
    }

    override fun onStop() {
        super.onStop()
        inForeground = false
        if (active === ytPlayer && ytPlayer.isActive && !backgroundYouTube) {
            if (server != null) {
                hostPause()
            } else {
                cancelStart()
                ytPlayer.pause()
                status.text = "YouTube ផ្អាកពេលបិទអេក្រង់ — បើកវិញដើម្បីបន្តចាក់"
            }
        }
    }

    private fun scheduleStart(delayMs: Long, action: () -> Unit) {
        cancelStart()
        val r = Runnable {
            pendingStart = null
            action()
        }
        pendingStart = r
        main.postDelayed(r, delayMs.coerceAtLeast(0))
    }

    private fun cancelStart() {
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackService.stop(this)
        searchPanel.destroy()
        main.removeCallbacksAndMessages(null)
        stopDiscovery()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        client?.stop()
        server?.let { s -> thread { s.stop() } }
        multicastLock?.release()
        localPlayer.release()
        runCatching { ytPlayer.view.release() }
        runCatching { ytSpare.view.release() }
    }
}
