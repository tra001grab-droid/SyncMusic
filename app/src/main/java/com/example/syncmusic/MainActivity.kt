package com.example.syncmusic

import android.content.Context
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
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

class MainActivity : AppCompatActivity(), SyncClient.Listener {

    companion object {
        private const val SERVICE_TYPE = "_syncmusic._tcp."
        private const val START_DELAY_MS = 1500L      // time given to every phone to get ready
        private const val POS_INTERVAL_MS = 1000L
        private const val SOFT_ERROR_MS = 15L          // error above this -> nudge speed
        private const val YOUTUBE_NUDGE_MS = 60L       // YouTube gap above this -> 1.25x/0.75x catch-up
        private const val READY_TIMEOUT_MS = 60_000L   // max wait for clients before auto-play
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nsd: NsdManager
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var youtubeView: YouTubePlayerView
    private lateinit var playerContainer: FrameLayout
    private var fullscreen = false
    private var savedVisibility = emptyMap<View, Int>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var searchPanel: SearchPanel

    private lateinit var localPlayer: LocalPlayer
    private lateinit var ytPlayer: YouTubeSyncPlayer
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
                main.postDelayed(this, POS_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        status = findViewById(R.id.status)
        nowPlaying = findViewById(R.id.nowPlaying)
        youtubeView = findViewById(R.id.youtubeView)
        playerContainer = findViewById(R.id.playerContainer)
        findViewById<Button>(R.id.btnFullscreen).setOnClickListener { setFullscreen(!fullscreen) }
        // Not registered as a lifecycle observer: the library would pause the video on every
        // onStop (screen off). onStop() below decides instead, based on the user's setting.

        // Only the host advances the playlist; clients follow its ITEM messages.
        localPlayer = LocalPlayer(this) { onItemEnded() }
        ytPlayer = YouTubeSyncPlayer(youtubeView, onEnded = { onItemEnded() }, onUnexpectedPause = { onYouTubePausedByUser() })
        // Taps on the video would pause YouTube behind the app's back; route them through the app instead.
        findViewById<View>(R.id.playerTouchGuard).setOnClickListener {
            when {
                server != null -> if (active?.isActive == true) hostPause() else hostPlay()
                else -> Toast.makeText(this, "Host ជាអ្នកបញ្ជាការចាក់", Toast.LENGTH_SHORT).show()
            }
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        backgroundYouTube = prefs.getBoolean("yt_background", true)
        youtubeView.enableBackgroundPlayback(backgroundYouTube)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBackground).apply {
            isChecked = backgroundYouTube
            setOnCheckedChangeListener { _, checked ->
                backgroundYouTube = checked
                youtubeView.enableBackgroundPlayback(checked)
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

        findViewById<Button>(R.id.btnAddSongs).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.btnAddYoutube).setOnClickListener { addYouTube() }
        searchPanel = SearchPanel(
            this,
            findViewById(R.id.searchPanel),
            onPick = { id -> hostSelect(addYouTubeId(id), autoPlay = true) },
            onClose = { showSearch(false) },
            onVoice = { startVoiceSearch() },
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
        findViewById<Button>(R.id.btnBack5).setOnClickListener { hostSeekBy(-5000) }
        findViewById<Button>(R.id.btnForward5).setOnClickListener { hostSeekBy(5000) }
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        autoPlayEnabled = settings.getBoolean("autoplay", true)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoplay).apply {
            isChecked = autoPlayEnabled
            setOnCheckedChangeListener { _, checked ->
                autoPlayEnabled = checked
                settings.edit().putBoolean("autoplay", checked).apply()
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

        handleShare(intent)
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
        youtubeView.layoutParams = youtubeView.layoutParams.apply {
            this.width = ViewGroup.LayoutParams.MATCH_PARENT
            height = width * 9 / 16
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
            youtubeView.layoutParams = youtubeView.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
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

    private fun showItem(title: String?, youtube: Boolean) {
        nowPlaying.visibility = if (title == null) View.GONE else View.VISIBLE
        nowPlaying.text = if (youtube) "▶ YouTube: $title" else "🎵 $title"
        if (!youtube) setFullscreen(false)
        playerContainer.visibility = if (youtube) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- Host

    private fun startHost() {
        if (hosting) return
        hosting = true
        showPanel(host = true)
        startBackgroundService()
        thread {
            val s = SyncServer { event -> setStatus(hostStatus(event)) }
            s.start()
            server = s
            val info = NsdServiceInfo().apply {
                serviceName = "SyncMusic"
                serviceType = SERVICE_TYPE
                port = s.port
                // The address NSD resolves can be the mobile-data one, so publish every LAN address.
                setAttribute("ips", Network.lanAddresses().joinToString(","))
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
            val icon = if (item is PlaylistItem.YouTube) "📺" else "🎵"
            (if (i == currentIndex) "▶ " else "    ") + "$icon ${item.title}"
        })
    }

    private fun addLocalSongs(uris: List<Uri>) {
        setStatus(hostStatus("កំពុងបន្ថែម ${uris.size} បទ…"))
        thread {
            for (uri in uris) {
                val title = displayName(uri) ?: "បទចម្រៀង"
                val file = File(filesDir, "host_${UUID.randomUUID()}")
                try {
                    contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                } catch (e: Exception) {
                    setStatus(hostStatus("មិនអាចបើក $title"))
                    continue
                }
                main.post {
                    playlist.add(PlaylistItem.Local(file, title.substringBeforeLast('.')))
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
                if (playlist.getOrNull(currentIndex) === item) showItem(title, youtube = true)
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
            showItem(null, youtube = false)
        } else if (index < currentIndex) {
            currentIndex--
        }
        (playlist.removeAt(index) as? PlaylistItem.Local)?.file?.delete()
        refreshPlaylist()
    }

    private fun hostSelect(index: Int, autoPlay: Boolean) {
        val s = server ?: return
        if (index !in playlist.indices) return
        hostStopCurrent()
        currentIndex = index
        val item = playlist[index]
        val version = s.setItem(item)
        refreshPlaylist()
        when (item) {
            is PlaylistItem.Local -> {
                ytPlayer.stop()
                showItem(item.title, youtube = false)
                localPlayer.load(item.file)
                active = localPlayer
                if (autoPlay) startWhenClientsReady(version)
            }
            is PlaylistItem.YouTube -> {
                localPlayer.pause()
                showItem(item.title, youtube = true)
                active = ytPlayer
                setStatus(hostStatus("⏳ កំពុងរៀបចំ (ទាញយក ${(YouTubeSyncPlayer.PRELOAD_FRACTION * 100).toInt()}%)…"))
                ytPlayer.load(
                    item.videoId,
                    ready = {
                        showPreload(null)
                        if (autoPlay) startWhenClientsReady(version)
                    },
                    progress = { pct ->
                        showPreload(pct)
                        setStatus(hostStatus("⏳ Host កំពុងទាញយក: $pct% / ${(YouTubeSyncPlayer.PRELOAD_FRACTION * 100).toInt()}%")) },
                )
            }
        }
    }

    /** Wait until every client has the new item ready (or a timeout), then start everyone together. */
    private fun startWhenClientsReady(version: Int) {
        val s = server ?: return
        val startedAt = now()
        cancelWait()
        val check = object : Runnable {
            override fun run() {
                if (s.version != version) return
                val ready = s.readyCount(version)
                if (ready >= s.clientCount || now() - startedAt > READY_TIMEOUT_MS) {
                    waitForClients = null
                    hostPlay()
                } else {
                    val pending = s.pendingProgress(version).joinToString(", ") { "$it%" }
                    setStatus(hostStatus("⏳ រង់ចាំទូរស័ព្ទ… ($ready/${s.clientCount} រួចរាល់) កំពុងទាញយក: $pending"))
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
        showPreload(null)
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

    private fun startDiscovery() {
        showPanel(host = false)
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
        client = SyncClient(hosts, port, cacheDir, this).also { it.start() }
        startBackgroundService()
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    override fun onStatus(text: String) = setStatus(text)

    override fun onDisconnected(reason: String) {
        main.post {
            client?.stop()
            client = null
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
            showPreload(null)
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

    override fun onLocalItem(version: Int, file: File) {
        main.post {
            if (version != clientVersion) return@post
            showItem(clientTitle, youtube = false)
            localPlayer.load(file)
            active = localPlayer
            markLoaded(version)
        }
    }

    override fun onYouTubeItem(version: Int, videoId: String) {
        main.post {
            if (version != clientVersion) return@post
            showItem(clientTitle, youtube = true)
            active = ytPlayer
            var lastSent = -1
            ytPlayer.load(
                videoId,
                ready = { if (version == clientVersion) markLoaded(version) },
                progress = { pct ->
                    if (version == clientVersion && pct != lastSent) {
                        lastSent = pct
                        showPreload(pct)
                        client?.sendProgress(version, pct)
                        setStatus("⏳ កំពុងទាញយក: $pct% / ${(YouTubeSyncPlayer.PRELOAD_FRACTION * 100).toInt()}%")
                    }
                },
            )
            // The host may have announced the item before it knew the title.
            if (clientTitle == videoId) thread {
                val title = YouTubeIds.fetchTitle(videoId) ?: return@thread
                main.post { if (version == clientVersion) showItem(title, youtube = true) }
            }
        }
    }

    /** YouTube paused without the app asking (tap on video, audio focus lost, …). */
    private fun onYouTubePausedByUser() {
        if (server != null) {
            // Host: treat it as a real pause so every phone pauses too.
            hostPause()
        } else if (hostPlaying && canPlay(ytPlayer)) {
            // Client: the host is still playing, so keep going.
            main.postDelayed({ ytPlayer.resume() }, 300)
        }
    }

    private fun markLoaded(version: Int) {
        loaded = true
        showPreload(null)
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

    /** Big "⏳ 12% / 20%" on the video while it preloads; null hides it. */
    private fun showPreload(percent: Int?) {
        val overlay = findViewById<TextView>(R.id.preloadOverlay)
        if (percent == null) {
            overlay.visibility = View.GONE
            return
        }
        overlay.text = "⏳ កំពុងទាញយក $percent% / ${(YouTubeSyncPlayer.PRELOAD_FRACTION * 100).toInt()}%"
        overlay.visibility = View.VISIBLE
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
        youtubeView.release()
        playlist.filterIsInstance<PlaylistItem.Local>().forEach { it.file.delete() }
    }
}
