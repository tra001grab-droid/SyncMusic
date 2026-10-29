package com.example.syncmusic

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
import android.provider.Settings
import android.database.ContentObserver
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
import android.widget.ScrollView
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
import kotlin.math.roundToInt

/** What the player area shows for the current item. */
private enum class ItemKind { AUDIO, VIDEO, YOUTUBE }

class MainActivity : AppCompatActivity(), SyncClient.Listener {

    companion object {
        private const val SERVICE_TYPE = "_syncmusic._tcp."
        private const val START_DELAY_MS = 1500L      // time given to every phone to get ready
        private const val POS_INTERVAL_MS = 1000L
        private const val EAR_PERIOD_MS = 1000L        // 🎧 by ear: one beep a second
        private const val EAR_MAX_MS = 180_000L        // 🎧 beeps stop by themselves after 3 minutes
        private const val SOFT_ERROR_MS = 15L          // error above this -> nudge speed
        private const val YOUTUBE_NUDGE_MS = 60L       // YouTube gap above this -> 1.25x/0.75x catch-up
        private const val SKIP_SETTLE_MS = 1000L       // wait this long after the last skip (double) tap
        private const val READY_TIMEOUT_MS = 60_000L   // max wait for clients before auto-play
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nsd: NsdManager
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var videoView: androidx.media3.ui.PlayerView
    private lateinit var playerContainer: FrameLayout
    private var fullscreen = false
    private var searchHiddenRows = emptyMap<View, Int>()
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
    private var backgroundYouTube = false

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
    /** This phone's sound delay compensation, set in the mixer (⏱ Delay). */
    private val calibrationMs get() = mixer.delayMs.toLong()

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addLocalSongs(uris)
    }


    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runAutoDelay() else mixerPanel.setAutoStatus("⚠ ត្រូវការ Microphone ដើម្បីវាស់ delay")
    }

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
                // Report what is heard from the host: its media position minus its own sound delay.
                if (p.isPlaying) s.broadcast("POS ${s.version} ${p.position - calibrationMs} ${now()}")
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
        mixer = Mixer(this, prefs)
        localPlayer = LocalPlayer(this, mixer.sessionId) { onItemEnded() }
        videoView = findViewById(R.id.videoView)
        localPlayer.attach(videoView)
        ytPlayer = makeYouTubePlayer(findViewById(R.id.youtubeView))
        ytSpare = makeYouTubePlayer(findViewById(R.id.youtubeView2))
        setUpPlayerUi()

        // Off unless turned on (YouTube's terms don't allow playing on with the screen off). New key
        // so it starts off after updating too.
        backgroundYouTube = prefs.getBoolean("yt_background_v2", false)
        ytPlayer.view.enableBackgroundPlayback(backgroundYouTube)
        ytSpare.view.enableBackgroundPlayback(backgroundYouTube)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBackground).apply {
            isChecked = backgroundYouTube
            setOnCheckedChangeListener { _, checked ->
                backgroundYouTube = checked
                ytPlayer.view.enableBackgroundPlayback(checked)
                ytSpare.view.enableBackgroundPlayback(checked)
                prefs.edit().putBoolean("yt_background_v2", checked).apply()
            }
        }
        YouTubeSyncPlayer.preloadEnabled = prefs.getBoolean("preload_20", true)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPreload).apply {
            isChecked = YouTubeSyncPlayer.preloadEnabled
            setOnCheckedChangeListener { _, checked ->
                YouTubeSyncPlayer.preloadEnabled = checked
                prefs.edit().putBoolean("preload_20", checked).apply()
                server?.setPreload(checked)
                // Off: also stop preloading the next song (saves internet).
                if (!checked) {
                    if (server != null) resetNext() else ytSpare.stop()
                }
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

        mixerPanel = MixerPanel(
            this,
            findViewById(R.id.mixerPanel),
            devices = ::mixerDevices,
            getMix = ::mixerGet,
            setMix = ::mixerSet,
            tracks = ::mixerTracks,
            autoDelayOn = { prefs.getBoolean("auto_delay", false) },
            onAutoDelay = { on ->
                prefs.edit().putBoolean("auto_delay", on).apply()
                if (on) runAutoDelay()
            },
            onMeasure = ::runAutoDelay,
            silentOn = { prefs.getBoolean("auto_silent", false) },
            onSilent = { on -> prefs.edit().putBoolean("auto_silent", on).apply() },
            listenerName = ::listenerName,
            onPickListener = ::pickListener,
            onEar = ::toggleEarMode,
            onHide = ::stopEarMode,
            note = {
                val eq = when {
                    !mixer.eqAvailable -> "⚠ ទូរស័ព្ទនេះមិនអនុញ្ញាត EQ ទេ — Volume នៅតែដំណើរការ"
                    !mixer.eqWorksForYouTube -> "ℹ EQ ដំណើរការជាមួយបទ/វីដេអូក្នុងទូរស័ព្ទ។ ទូរស័ព្ទនេះមិនអនុញ្ញាតឲ្យ EQ ប្ដូរសំឡេង YouTube ទេ។"
                    else -> "EQ ដំណើរការជាមួយបទក្នុងទូរស័ព្ទ និង YouTube។"
                }
                val boost = when {
                    !mixer.boostAvailable -> "⚠ ទូរស័ព្ទនេះមិនអនុញ្ញាត Boost លើស 100% ទេ។"
                    !mixer.boostWorksForYouTube -> "🚀 Boost (101-200%) ដំណើរការជាមួយបទ/វីដេអូក្នុងទូរស័ព្ទ តែមិនមែន YouTube លើទូរស័ព្ទនេះទេ។"
                    else -> "🚀 Boost (101-200%) ដំណើរការជាមួយបទក្នុងទូរស័ព្ទ និង YouTube។"
                }
                "$eq\n\n$boost\n⚠ Boost ខ្លាំងពេកអាចធ្វើឲ្យសំឡេងបែក ឬខូចឧបាល័រ — សូមប្រើដោយប្រុងប្រយ័ត្ន។"
            },
        )
        findViewById<Button>(R.id.btnMixer).setOnClickListener { mixerPanel.show() }
        findViewById<View>(R.id.headerLogo).setOnClickListener { showAbout() }
        findViewById<View>(R.id.headerTitle).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.btnMixerClient).setOnClickListener { mixerPanel.show() }
        // Volume keys change the media volume: keep the mixer (and the host's view of us) current.
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        findViewById<Button>(R.id.btnAddSongs).setOnClickListener { pickAudio.launch(arrayOf("audio/*", "video/*")) }
        findViewById<Button>(R.id.btnAddYoutube).setOnClickListener { addYouTube() }
        searchPanel = SearchPanel(
            this,
            findViewById(R.id.searchPanel),
            findViewById(R.id.searchHeader),
            onPick = { id -> hostSelect(addYouTubeId(id), autoPlay = true) },
            onClose = { showSearch(false) },
            onVoice = { startVoiceSearch() },
            findInLibrary = { q -> LibrarySearch.find(q, playlist) },
            onPickLocal = { item ->
                val index = playlist.indexOfFirst { it === item }
                if (index >= 0) hostSelect(index, autoPlay = true)
            },
            suggestionsView = findViewById(R.id.suggestionsList),
            onResultsScroll = ::onResultsScroll,
        )
        searchPanel.hide()
        sizePlayer()
        findViewById<Button>(R.id.btnSearch).setOnClickListener { showSearch(true) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    mixerPanel.isOpen -> mixerPanel.hide()
                    searchPanel.closeSuggestions() -> {}
                    fullscreen -> setFullscreen(false)
                    // Like music apps: Back on the main screen keeps the session running in the background.
                    !searchPanel.isOpen -> moveTaskToBack(true)
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
        refreshControls()
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
        if (open == searchPanel.isOpen) return
        findViewById<View>(R.id.hostPanel).visibility = if (open) View.GONE else View.VISIBLE
        // The "សិង្ហនគរ" header takes the top: hide the app bar and the host's status rows.
        val rows = listOf(R.id.status, R.id.switchBackground, R.id.switchPreload, R.id.nowPlaying).map { findViewById<View>(it) }
        headerHidden = false
        searchHeader.translationY = 0f
        playerCollapsed = false
        if (open) {
            searchHiddenRows = rows.associateWith { it.visibility }
            rows.forEach { it.visibility = View.GONE }
            supportActionBar?.hide()
            searchPanel.show()
            // Room for the floating header once it has been measured.
            searchHeader.post { headerSpace.layoutParams = headerSpace.layoutParams.apply { height = searchHeader.height } }
        } else {
            headerSpace.layoutParams = headerSpace.layoutParams.apply { height = 0 }
            searchHiddenRows.forEach { (view, visibility) -> view.visibility = visibility }
            searchHiddenRows = emptyMap()
            supportActionBar?.show()
            searchPanel.hide()
        }
        applyRootPadding()
        sizePlayer()
    }

    /** Normal screens have a 16dp margin; the search screen and fullscreen go edge to edge. */
    private fun applyRootPadding() {
        val pad = if (fullscreen || searchPanel.isOpen) 0 else (16 * resources.displayMetrics.density).toInt()
        findViewById<View>(R.id.root).setPadding(pad, pad, pad, pad)
    }

    // --------------------------------------------------------------- player placement
    //
    // The player floats above the page (so its web views never move). In the page, playerSpace
    // keeps its room; the player follows it, slides up with it when the results are scrolled,
    // or becomes a small mini player in the corner, or fills the screen.

    private lateinit var frameRoot: FrameLayout
    private lateinit var playerSpace: View
    private lateinit var headerSpace: View
    private lateinit var searchHeader: View
    private lateinit var controlsOverlay: View
    private var mini = false
    /** Search screen: the player has slid up with the results. */
    private var playerCollapsed = false
    private var headerHidden = false
    private var lastScrollChangeAt = 0L
    private var spaceAnimator: ValueAnimator? = null
    private var headerSpaceAnimator: ValueAnimator? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Width of the player in the page: full width on the search screen, inside the margins otherwise. */
    private fun dockedWidth(): Int {
        val metrics = resources.displayMetrics
        val screenWidth = minOf(metrics.widthPixels, metrics.heightPixels)  // the app is portrait
        return if (searchPanel.isOpen) screenWidth else screenWidth - dp(32)
    }

    private fun dockedHeight() = dockedWidth() * 9 / 16

    private fun sizePlayer() {
        if (fullscreen) return
        playerSpace.layoutParams = playerSpace.layoutParams.apply {
            width = dockedWidth()
            height = if (mini || playerCollapsed) 0 else dockedHeight()
        }
        placePlayer()
    }

    /** Put the floating player where the current mode says. */
    private fun placePlayer() {
        val lp = playerContainer.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        when {
            fullscreen -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                lp.leftMargin = 0
                lp.topMargin = 0
            }
            mini -> {
                // About thumb size, bottom-right, above the bottom buttons.
                val w = dp(168)
                val h = w * 9 / 16
                lp.width = w
                lp.height = h
                lp.leftMargin = (frameRoot.width - w - dp(12)).coerceAtLeast(0)
                lp.topMargin = (frameRoot.height - h - dp(76)).coerceAtLeast(0)
            }
            else -> {
                // Follow the room in the page; when that room shrinks, slide up with it.
                val root = findViewById<View>(R.id.root)
                val h = dockedHeight()
                lp.width = dockedWidth()
                lp.height = h
                lp.leftMargin = root.left + playerSpace.left
                lp.topMargin = root.top + playerSpace.top + playerSpace.height - h
            }
        }
        playerContainer.layoutParams = lp
    }

    private fun setMini(on: Boolean) {
        if (on == mini || fullscreen) return
        mini = on
        hideOverlay()
        playerCollapsed = false
        // Back to full size: the search header takes its own room above the video again,
        // otherwise it would come back over (and hide) the top of the video.
        if (!on && searchPanel.isOpen) dockHeader()
        sizePlayer()
    }

    private fun dockHeader() {
        headerHidden = false
        searchHeader.animate().translationY(0f).setDuration(200).start()
        animateHeaderSpace(searchHeader.height)
    }

    /** Search screen: results scrolled up → the player slides up out of view; back at the top → it returns. */
    private fun setPlayerCollapsed(collapsed: Boolean) {
        if (collapsed == playerCollapsed || mini || fullscreen) return
        if (collapsed && (!searchPanel.isOpen || playerContainer.visibility != View.VISIBLE)) return
        playerCollapsed = collapsed
        if (collapsed) hideOverlay()
        spaceAnimator?.cancel()
        spaceAnimator = ValueAnimator.ofInt(playerSpace.layoutParams.height, if (collapsed) 0 else dockedHeight()).apply {
            duration = 200
            addUpdateListener { anim -> playerSpace.layoutParams = playerSpace.layoutParams.apply { height = anim.animatedValue as Int } }
            start()
        }
    }

    // -------------------------------------------------------------- search header

    private fun animateHeaderSpace(target: Int) {
        headerSpaceAnimator?.cancel()
        headerSpaceAnimator = ValueAnimator.ofInt(headerSpace.layoutParams.height, target).apply {
            duration = 200
            addUpdateListener { anim -> headerSpace.layoutParams = headerSpace.layoutParams.apply { height = anim.animatedValue as Int } }
            start()
        }
    }

    /** Results scrolled: up → the search bar slides away (the video moves up); down → it comes back (the video moves down). */
    private fun onResultsScroll(y: Int, dy: Int) {
        if (!searchPanel.isOpen || fullscreen) return
        if (y <= 0) {
            dockHeader()
            return
        }
        // Resizing moves the page a little; don't take that for the user's scroll.
        if (now() - lastScrollChangeAt < 300) return
        if (dy > 8 && !headerHidden) {
            // Up: only the search bar slides away; the full-size video moves up to the top.
            lastScrollChangeAt = now()
            headerHidden = true
            searchHeader.animate().translationY(-searchHeader.height.toFloat()).setDuration(200).start()
            animateHeaderSpace(0)
        } else if (dy < -8 && headerHidden) {
            // Down: the search bar comes back and the video moves down with it.
            lastScrollChangeAt = now()
            dockHeader()
        }
    }

    // ------------------------------------------------------- on-video controls

    private var overlayShown = false
    private var seeking = false
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    /** A 3 s hide is pending (see scheduleOverlayHide). */
    private var overlayHideScheduled = false
    /** Keeps the red progress line (and, when shown, the time and ⏯) up to date while a video is on screen. */
    private val overlayTicker = object : Runnable {
        override fun run() {
            if (playerContainer.visibility != View.VISIBLE) return
            updateOverlay()
            main.postDelayed(this, 500)
        }
    }

    private fun startProgressTicker() {
        main.removeCallbacks(overlayTicker)
        // Clients only see where the song is; the host can drag the dot.
        findViewById<SeekBar>(R.id.ovSeek).isEnabled = server != null
        main.post(overlayTicker)
    }

    private val playerGestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    mini -> setMini(false)
                    overlayShown -> hideOverlay()
                    else -> showOverlay()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Double tap: right half +5 s, left half -5 s; more double taps add up (+10, +15 …).
                if (mini || server == null || currentIndex < 0) return false
                hideOverlay()
                queueSkip(if (e.x > playerContainer.width / 2f) 5000 else -5000)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                // Drag the video down → mini player.
                val start = e1 ?: return false
                val down = e2.y - start.y
                if (!mini && !fullscreen && down > dp(60) && down > abs(e2.x - start.x)) {
                    setMini(true)
                    return true
                }
                return false
            }
        })
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setUpPlayerUi() {
        frameRoot = findViewById(R.id.frameRoot)
        playerSpace = findViewById(R.id.playerSpace)
        headerSpace = findViewById(R.id.searchHeaderSpace)
        searchHeader = findViewById(R.id.searchHeader)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        val gestures = View.OnTouchListener { _, event -> playerGestures.onTouchEvent(event) }
        findViewById<View>(R.id.playerTouchGuard).setOnTouchListener(gestures)
        controlsOverlay.setOnTouchListener(gestures)
        // The player follows its room in the page whenever the page moves.
        playerSpace.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!mini && !fullscreen) placePlayer() }
        frameRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (mini) placePlayer() }
        // The search box (🔍) changes the header's height: keep its room in step while it's docked.
        searchHeader.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (searchPanel.isOpen && !headerHidden && bottom - top != oldBottom - oldTop) {
                headerSpace.layoutParams = headerSpace.layoutParams.apply { height = bottom - top }
            }
        }

        findViewById<View>(R.id.ovPlayPause).setOnClickListener {
            if (active?.isActive == true) {
                hostPause()  // paused: the controls stay on screen
                updateOverlay()
            } else {
                hostPlay()
                hideOverlay()  // ▶ pressed: get out of the way at once
            }
        }
        findViewById<View>(R.id.ovPrev).setOnClickListener {
            if (currentIndex > 0) hostSelect(currentIndex - 1, autoPlay = true)
            scheduleOverlayHide()
        }
        findViewById<View>(R.id.ovNext).setOnClickListener {
            if (currentIndex + 1 < playlist.size) hostSelect(currentIndex + 1, autoPlay = true)
            scheduleOverlayHide()
        }
        findViewById<SeekBar>(R.id.ovSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setOverlayTime(progress.toLong(), bar.max.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
                main.removeCallbacks(hideOverlayRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                // Dragged the red dot: every phone jumps there together.
                val target = bar.progress.toLong()
                if (active?.isActive == true) hostPlay(target) else active?.seekTo(target)
                scheduleOverlayHide()
            }
        })
    }

    private fun showOverlay() {
        overlayShown = true
        controlsOverlay.visibility = View.VISIBLE
        // Clients only see where the song is; the host controls it.
        val host = server != null
        findViewById<View>(R.id.overlayButtons).visibility = if (host) View.VISIBLE else View.GONE
        updateOverlay()
        scheduleOverlayHide()
    }

    /** Playing: hide after 3 s. Paused: stay until tapped. */
    private fun scheduleOverlayHide() {
        main.removeCallbacks(hideOverlayRunnable)
        overlayHideScheduled = active?.isActive == true || server == null
        if (overlayHideScheduled) main.postDelayed(hideOverlayRunnable, 3000)
    }

    private fun hideOverlay() {
        overlayShown = false
        overlayHideScheduled = false
        main.removeCallbacks(hideOverlayRunnable)
        if (::controlsOverlay.isInitialized) controlsOverlay.visibility = View.GONE
    }

    private fun updateOverlay() {
        val p = active
        val position = p?.position ?: 0L
        val duration = p?.durationMs ?: 0L
        findViewById<TextView>(R.id.ovPlayPause).text = if (p?.isActive == true) "❚❚" else "▶"
        if (seeking) return
        // Shown while the song was still loading/starting (so no hide was set): now it plays, hide in 3 s.
        if (overlayShown && !overlayHideScheduled && p?.isActive == true) scheduleOverlayHide()
        findViewById<SeekBar>(R.id.ovSeek).apply {
            max = duration.coerceAtLeast(1L).toInt()
            progress = position.coerceIn(0L, duration).toInt()
        }
        setOverlayTime(position, duration)
    }

    private fun setOverlayTime(position: Long, duration: Long) {
        findViewById<TextView>(R.id.ovTime).text = "${formatTime(position.coerceAtLeast(0))} / ${formatTime(duration)}"
    }

    /** The button bar is only needed when there's no video to tap (songs). */
    private fun refreshControls() {
        findViewById<View>(R.id.hostControls).visibility =
            if (hosting && playerContainer.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizePlayer()
    }

    /** Our own fullscreen: the floating player fills the screen, landscape, system bars hidden. */
    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        if (on && mini) setMini(false)
        fullscreen = on
        val root = findViewById<View>(R.id.root)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        val button = findViewById<Button>(R.id.btnFullscreen)
        if (on) {
            root.visibility = View.INVISIBLE
            if (searchPanel.isOpen) searchHeader.visibility = View.GONE
            supportActionBar?.hide()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            button.text = "✕"
            placePlayer()
        } else {
            root.visibility = View.VISIBLE
            if (searchPanel.isOpen) searchHeader.visibility = View.VISIBLE
            applyRootPadding()
            if (!searchPanel.isOpen) supportActionBar?.show()
            WindowCompat.setDecorFitsSystemWindows(window, true)
            bars.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            button.text = "⛶"
            // Sizes depend on the (now portrait) screen, so wait for the rotation first.
            root.post { sizePlayer() }
        }
    }

    private fun showItem(title: String?, kind: ItemKind) {
        val titleVisibility = if (title == null) View.GONE else View.VISIBLE
        // On the search screen the title row stays hidden; restore it this way when leaving.
        if (searchPanel.isOpen) searchHiddenRows = searchHiddenRows + (nowPlaying to titleVisibility)
        else nowPlaying.visibility = titleVisibility
        nowPlaying.text = when (kind) {
            ItemKind.YOUTUBE -> "▶ YouTube: $title"
            ItemKind.VIDEO -> "🎬 $title"
            ItemKind.AUDIO -> "🎵 $title"
        }
        val showPlayer = title != null && kind != ItemKind.AUDIO
        if (!showPlayer) setFullscreen(false)
        playerContainer.visibility = if (showPlayer) View.VISIBLE else View.GONE
        playerSpace.visibility = playerContainer.visibility
        videoView.visibility = if (kind == ItemKind.VIDEO) View.VISIBLE else View.GONE
        if (!showPlayer) hideOverlay() else startProgressTicker()
        refreshControls()
        sizePlayer()
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
            s.onLags = ::onLags
            s.onClientsChanged = {
                main.post {
                    mixerPanel.refresh()
                    autoMeasureNewPhones()
                }
            }
            s.start()
            s.setPreload(YouTubeSyncPlayer.preloadEnabled)
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
        s.broadcast("PLAY ${s.version} ${pos - calibrationMs} $startAt")
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
        val text = if (totalMs >= 0) "+${totalMs / 1000}" else "${totalMs / 1000}"
        if (playerContainer.visibility != View.VISIBLE) {
            Toast.makeText(this, text + "s", Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<TextView>(R.id.skipOverlay).apply {
            this.text = text
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.CENTER }
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
            client?.sendIam(Build.MODEL)
            reportMix()
        }
    }

    // ------------------------------------------------------------------ mixer

    private lateinit var mixer: Mixer
    private lateinit var mixerPanel: MixerPanel
    private var lastReportedMix: String? = null

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            reportMix()
            mixerPanel.refresh()
        }
    }

    /** Client: tell the host our current volume/EQ (only when it changed). */
    private fun reportMix() {
        val c = client ?: return
        val mix = mixer.current()
        if (mix.encode() == lastReportedMix) return
        lastReportedMix = mix.encode()
        c.sendMix(mix)
    }

    private fun mixerDevices(): List<Pair<String, String>> {
        val s = server ?: return listOf(MixerPanel.SELF to "📱 ទូរស័ព្ទនេះ")
        return listOf(MixerPanel.SELF to "📱 ទូរស័ព្ទនេះ (Host)", MixerPanel.ALL to "🔊 ទាំងអស់") +
            s.clientList().map { "c${it.key}" to "📱 ${it.name}" }
    }

    /** Host: every phone's gap to the host for the mixer's track view (stale readings shown as unknown). */
    private fun mixerTracks(): List<MixerPanel.Track> {
        val s = server ?: return emptyList()
        val self = mixer.current()
        val host = MixerPanel.Track(MixerPanel.SELF, "${Build.MODEL} (Host)", true, 0, null, self.delayMs, self.bluetooth)
        return listOf(host) + s.clientList().map { c ->
            val mix = Mix.decode(c.mix)
            val fresh = now() - c.statAt < 5000
            MixerPanel.Track(
                "c${c.key}", c.name, false,
                gapMs = if (fresh) c.gapMs else null,
                rttMs = c.rttMs,
                delayMs = mix?.delayMs ?: 0,
                bluetooth = mix?.bluetooth == true,
            )
        }
    }

    private fun clientOf(deviceId: String) =
        server?.clientList()?.firstOrNull { "c${it.key}" == deviceId }

    private fun mixerGet(deviceId: String): Mix? = when (deviceId) {
        MixerPanel.SELF, MixerPanel.ALL -> mixer.current()
        else -> Mix.decode(clientOf(deviceId)?.mix)
    }

    private fun mixerSet(deviceId: String, mix: Mix) {
        val s = server
        when (deviceId) {
            MixerPanel.SELF -> {
                mixer.apply(mix)
                reportMix()
            }
            MixerPanel.ALL -> {
                // Volume/EQ for everyone, but each phone keeps its own delay.
                mixer.apply(mix.copy(delayMs = mixer.delayMs))
                s?.clientList()?.forEach { c ->
                    setClientMix(c, mix.copy(delayMs = Mix.decode(c.mix)?.delayMs ?: 0))
                }
            }
            else -> clientOf(deviceId)?.let { setClientMix(it, mix) }
        }
    }

    private fun setClientMix(target: SyncServer.ClientInfo, mix: Mix) {
        target.mix = mix.encode()  // shown right away; the phone confirms with its own MIX
        server?.sendTo(target.key, "SETMIX ${mix.encode()}")
    }

    override fun onSetMix(mix: String) {
        main.post {
            Mix.decode(mix)?.let { mixer.apply(it) }
            reportMix()
            mixerPanel.refresh()
        }
    }

    // ------------------------------------------------------------ 🤖 auto delay

    private var measuringDelay = false
    /** Phones already measured; with 🤖 Auto on, a phone that joins later is measured once too. */
    private val measuredKeys = mutableSetOf<Int>()
    private val autoMeasure = Runnable { runAutoDelay() }
    /** Whose microphone listens: MixerPanel.SELF (this host) or a client's "c<key>". */
    private var delayListener = MixerPanel.SELF
    /** Waiting for a client's LAGS; null when not. */
    private var pendingLags: ((String?) -> Unit)? = null
    private val beepHz get() = if (prefs.getBoolean("auto_silent", false)) AutoDelay.SILENT_HZ else AutoDelay.AUDIBLE_HZ

    private fun listenerClient() = clientOf(delayListener)?.takeIf { it.mix != null }

    private fun listenerName() = listenerClient()?.name ?: "${Build.MODEL} (Host)"

    /** 🎙 Pick the phone whose microphone listens (e.g. the host's is broken). */
    private fun pickListener() {
        val phones = server?.clientList()?.filter { it.mix != null }.orEmpty()
        val ids = listOf(MixerPanel.SELF) + phones.map { "c${it.key}" }
        val names = listOf("👑 ${Build.MODEL} (Host)") + phones.map { "📱 ${it.name}" }
        AlertDialog.Builder(this)
            .setTitle("🎙 ប្រើ Microphone ទូរស័ព្ទណា?")
            .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(delayListener).coerceAtLeast(0)) { dialog, which ->
                delayListener = ids[which]
                mixerPanel.updateListener()
                dialog.dismiss()
            }
            .setNegativeButton("បោះបង់", null)
            .show()
    }

    private fun autoMeasureNewPhones() {
        val s = server ?: return
        if (!prefs.getBoolean("auto_delay", false) || measuringDelay || earMode) return
        // Wait for its MIX (it knows its own delay) and let it settle in before beeping.
        if (s.clientList().any { it.mix != null && it.key !in measuredKeys }) {
            main.removeCallbacks(autoMeasure)
            main.postDelayed(autoMeasure, 4000)
        }
    }

    /**
     * Host: pause, let every phone beep in turn (host first) at set times while one phone's
     * microphone listens (this one, or the one picked with 🎙), then set each phone's ⏱ Delay
     * from how much later than the host it was heard, and play on.
     */
    private fun runAutoDelay() {
        val s = server ?: return
        if (measuringDelay || earMode) return
        val listener = listenerClient()
        if (listener == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val phones = s.clientList().filter { it.mix != null }
        if (phones.isEmpty()) {
            mixerPanel.setAutoStatus("គ្មានទូរស័ព្ទកូនភ្ជាប់ទេ")
            return
        }
        main.removeCallbacks(autoMeasure)
        measuringDelay = true
        val wasPlaying = active?.isPlaying == true
        if (wasPlaying) hostPause()
        val hz = beepHz
        mixerPanel.setAutoStatus(
            "🤖 កំពុងវាស់ (🎙 ${listener?.name ?: "Host"})… " +
                if (hz == AutoDelay.SILENT_HZ) "ប៊ិបស្ងាត់ 19 kHz" else "សូមស្ងាត់មួយភ្លែត (ស្ដាប់ឮ ប៊ីប)"
        )
        setStatus(hostStatus("🤖 កំពុងវាស់ delay…"))
        val count = phones.size + 1
        val start = now() + 1500
        fun at(round: Int, phone: Int) = start + (round * count + phone) * AutoDelay.GAP_MS
        val times = (0 until count).map { j -> (0 until AutoDelay.ROUNDS).map { at(it, j) } }
        val until = at(AutoDelay.ROUNDS - 1, count - 1) + AutoDelay.GAP_MS + 200
        // Every phone beeps the same way, whoever listens.
        phones.forEachIndexed { i, c -> s.sendTo(c.key, "BEEP ${times[i + 1].joinToString(",")} $hz") }
        thread(isDaemon = true) { AutoDelay.beep(times[0], hz) }

        // The lags of every beep, per phone (null = this microphone couldn't record).
        fun done(lags: List<List<Double?>>?, error: String?) {
            pendingLags = null
            main.removeCallbacks(lagsTimeout)
            val medians = lags?.map { phone -> phone.filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] } }
            finishAutoDelay(phones, medians, error, wasPlaying)
        }
        if (listener == null) {
            thread(isDaemon = true) {
                val rec = AutoDelay.record(until)
                val lags = rec?.let { r -> times.map { phone -> phone.map { AutoDelay.lag(r, it, hz) } } }
                main.post { done(lags, if (rec == null) "⚠ មិនអាចប្រើ Microphone បានទេ — សាក 🎙 ទូរស័ព្ទផ្សេង" else null) }
            }
        } else {
            s.sendTo(listener.key, "LISTEN $until $hz ${times.joinToString(";") { it.joinToString(",") }}")
            pendingLags = { text ->
                when {
                    text == null -> done(null, "⚠ ${listener.name} មិនឆ្លើយ — សូម update app លើទូរស័ព្ទនោះ")
                    text == "nomic" -> done(null, "⚠ ${listener.name}: សូមអនុញ្ញាត Microphone លើទូរស័ព្ទនោះ រួចចុច 🔄")
                    else -> done(text.split(";").map { phone -> phone.split(",").map { it.toDoubleOrNull() } }, null)
                }
            }
            main.postDelayed(lagsTimeout, until - now() + 5000)
        }
    }

    private val lagsTimeout = Runnable { pendingLags?.invoke(null) }

    /** A client answered LISTEN. */
    private fun onLags(key: Int, text: String) = main.post {
        if (listenerClient()?.key == key) pendingLags?.invoke(text)
    }

    private fun finishAutoDelay(phones: List<SyncServer.ClientInfo>, lags: List<Double?>?, error: String?, resume: Boolean) {
        measuringDelay = false
        measuredKeys += phones.map { it.key }
        val hostLag = lags?.getOrNull(0)
        val text = when {
            error != null || lags == null -> error ?: "⚠"
            hostLag == null -> "⚠ មិនឮសំឡេងទូរស័ព្ទមេ — បើក Volume / ដាក់ទូរស័ព្ទជិតគ្នា ហើយចុច 🔄" +
                if (beepHz == AutoDelay.SILENT_HZ) "\n(ឬបិទ 🔇 ប៊ិបស្ងាត់ — ឧបាល័រខ្លះមិនអាចចេញ 19 kHz)" else ""
            else -> "✔ បានកំណត់ Delay ស្វ័យប្រវត្តិ:\n" + phones.mapIndexed { i, c ->
                val lag = lags.getOrNull(i + 1)
                val mix = Mix.decode(c.mix)
                if (lag == null || mix == null) {
                    "📱 ${c.name}: ✖ មិនឮ (Volume ទាប/កាស/iPhone) — ប្រើ 🎧"
                } else {
                    // Heard later than the host = its sound comes out late = it should play ahead.
                    val delay = (mixer.delayMs + ((lag - hostLag) / 10).roundToInt() * 10)
                        .coerceIn(Mix.MIN_DELAY_MS, Mix.MAX_DELAY_MS)
                    setClientMix(c, mix.copy(delayMs = delay))
                    "📱 ${c.name}: ⏱ ${if (delay > 0) "+" else ""}$delay ms"
                }
            }.joinToString("\n")
        }
        mixerPanel.setAutoStatus(text)
        mixerPanel.refresh()
        if (resume) hostPlay() else setStatus(hostStatus("បានផ្អាក"))
    }

    // -------------------------------------------------------------- 🎧 by ear

    /** Host: every phone beeps together once a second until stopped; the ⏱ Delays are set by ear. */
    private var earMode = false
    private var earResume = false
    private var earStopAt = 0L
    private val earAutoStop = Runnable { stopEarMode() }

    private fun earPitch(index: Int) = 880.0 + 440.0 * index

    private fun toggleEarMode() = if (earMode) stopEarMode() else startEarMode()

    private fun startEarMode() {
        val s = server ?: return
        if (measuringDelay) return
        val phones = s.clientList().filter { it.mix != null }
        earMode = true
        earResume = active?.isPlaying == true
        if (earResume) hostPause()
        val start = now() + 1000
        // At most 3 minutes, so beeps can't be left on by mistake.
        earStopAt = start + EAR_MAX_MS
        main.postDelayed(earAutoStop, EAR_MAX_MS + 1000)
        phones.forEachIndexed { i, c -> s.sendTo(c.key, "TICKS $start $EAR_PERIOD_MS ${earPitch(i + 1)}") }
        thread(isDaemon = true) {
            AutoDelay.ticks(start, EAR_PERIOD_MS, earPitch(0), { mixer.delayMs }) { earMode && now() < earStopAt }
        }
        val who = (listOf("👑 Host") + phones.map { "📱 ${it.name}" })
            .mapIndexed { i, name -> "$name ${listOf("🔈 ទាប", "🔉 មធ្យម", "🔊 ខ្ពស់")[i.coerceAtMost(2)].let { if (i > 2) "🔔 ខ្ពស់ជាង" else it }}" }
        mixerPanel.setEarStatus(
            "🎧 ស្ដាប់កាស/ឧបាល័រទាំងអស់: ប៊ិបម្ដងក្នុង 1 វិនាទី (សំឡេងទាប→ខ្ពស់):\n" + who.joinToString("\n") +
                "\nចុចលើ track ហើយកែ ⏱ Delay ±10 រហូតលឺប៊ិបតែមួយ។\nលឺយឺត → ＋   លឺលឿន → −",
            running = true,
        )
        setStatus(hostStatus("🎧 វាស់ដោយត្រចៀក…"))
    }

    private fun stopEarMode() {
        if (!earMode) return
        earMode = false
        main.removeCallbacks(earAutoStop)
        server?.broadcast("TICKS 0")
        mixerPanel.setEarStatus("ពាក់កាស ឬ Microphone ខូច? វាស់ដោយត្រចៀក →", running = false)
        if (earResume) hostPlay() else setStatus(hostStatus("បានផ្អាក"))
    }

    override fun onHostPreload(on: Boolean) {
        main.post {
            // Follows the host while connected (the switch is locked until disconnecting).
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPreload).apply {
                isChecked = on
                isEnabled = false
            }
        }
    }

    override fun onBeep(localTimes: List<Long>, hz: Double) {
        thread(isDaemon = true) { AutoDelay.beep(localTimes, hz) }
    }

    /** Client: 🎧 beeps until the host says stop (or the time limit / disconnect). */
    private val tickGeneration = java.util.concurrent.atomic.AtomicInteger()

    override fun onTicks(localStart: Long?, periodMs: Long, hz: Double) {
        val generation = tickGeneration.incrementAndGet()
        if (localStart == null) return
        thread(isDaemon = true) {
            AutoDelay.ticks(localStart, periodMs, hz, { mixer.delayMs }) {
                tickGeneration.get() == generation && now() < localStart + EAR_MAX_MS
            }
        }
    }

    /** Client: the host uses this phone's microphone for 🤖 Auto delay. */
    override fun onListen(localUntil: Long, hz: Double, localTimes: List<List<Long>>) {
        val c = client ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            c.sendLags(null)
            main.post { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            return
        }
        thread(isDaemon = true) {
            val rec = AutoDelay.record(localUntil)
            c.sendLags(rec?.let { r -> localTimes.map { phone -> phone.map { AutoDelay.lag(r, it, hz) } } })
        }
    }

    // ------------------------------------------------------------------ about

    /** ℹ Copyright, ownership notices, the open-source licenses and privacy. */
    private fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        val text = """
            |Sync Music  v$version
            |© 2026 Chetra Phon. រក្សាសិទ្ធិគ្រប់យ៉ាង (All rights reserved).
            |ទំនាក់ទំនង: chetraphon@gmail.com
            |
            |កម្មវិធីនេះផ្ដល់ជូនដោយឥតគិតថ្លៃ "តាមស្ថានភាពដែលមាន" (as is) ដោយគ្មានការធានា។
            |
            |▶ YouTube
            |កម្មវិធីនេះមិនមែនជារបស់ YouTube ឬ Google ទេ ហើយមិនមានទំនាក់ទំនងជាមួយពួកគេទេ។ YouTube ជាពាណិជ្ជសញ្ញារបស់ Google LLC។ វីដេអូ YouTube ត្រូវបានចាក់តាម YouTube player ផ្លូវការ (IFrame) ហើយស្ថិតក្រោមលក្ខខណ្ឌរបស់ YouTube: https://www.youtube.com/t/terms
            |
            |🎵 បទចម្រៀង
            |បទចម្រៀង និងវីដេអូទាំងអស់ ជាកម្មសិទ្ធិរបស់ម្ចាស់ដើម។ កម្មវិធីនេះមិនលក់ ឬចែកចាយមាតិកាណាមួយទេ។ សូមចាក់តែបទដែលអ្នកមានសិទ្ធិប្រើប៉ុណ្ណោះ។
            |
            |🔒 ឯកជនភាព
            |• Microphone ប្រើតែពេលវាស់ 🤖 Auto Delay ប៉ុណ្ណោះ — មិនរក្សាទុក មិនផ្ញើទៅណាទេ។
            |• ទូរស័ព្ទនិយាយគ្នាតែក្នុង Wi-Fi តែមួយប៉ុណ្ណោះ។
            |• កម្មវិធីមិនប្រមូលទិន្នន័យផ្ទាល់ខ្លួនទេ។
            |
            |📦 Open-source (Apache License 2.0)
            |• AndroidX (AppCompat) — © The Android Open Source Project
            |• Media3 / ExoPlayer — © The Android Open Source Project
            |• android-youtube-player — © Pierfrancesco Soffritti
            |• Kotlin — © JetBrains s.r.o.
        """.trimMargin()
        val body = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextIsSelectable(true)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("ℹ អំពី / កម្មសិទ្ធិបញ្ញា")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("បិទ", null)
            .setNeutralButton("📄 Apache License 2.0") { _, _ -> showLicense() }
            .show()
    }

    private fun showLicense() {
        val license = runCatching { assets.open("apache-2.0.txt").bufferedReader().readText() }.getOrDefault("")
        val body = TextView(this).apply {
            text = license
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Apache License 2.0")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("បិទ", null)
            .show()
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
            tickGeneration.incrementAndGet()
            findViewById<View>(R.id.switchPreload).isEnabled = true
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
            client?.let { it.sendStat(error, it.bestRtt) }

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
        if (server == null || nextVideoId != null || !backgroundPreloadAllowed || !YouTubeSyncPlayer.preloadEnabled) return
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
        val green = if (ready) ColorStateList.valueOf(0xFF2E7D32.toInt()) else null
        findViewById<Button>(R.id.btnNext).backgroundTintList = green
        findViewById<View>(R.id.ovNext).backgroundTintList = green
    }

    override fun onNext(videoId: String) {
        main.post {
            if (!backgroundPreloadAllowed || !YouTubeSyncPlayer.preloadEnabled) return@post
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
        contentResolver.unregisterContentObserver(volumeObserver)
        mixer.release()
        searchPanel.destroy()
        main.removeCallbacksAndMessages(null)
        stopDiscovery()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        // Clear these first: player callbacks can still arrive after this and must find nothing to do.
        val oldClient = client
        val oldServer = server
        earMode = false
        tickGeneration.incrementAndGet()
        client = null
        server = null
        oldClient?.stop()
        oldServer?.let { s -> thread { s.stop() } }
        multicastLock?.release()
        localPlayer.release()
        runCatching { ytPlayer.view.release() }
        runCatching { ytSpare.view.release() }
    }
}
