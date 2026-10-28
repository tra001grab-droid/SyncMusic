package com.example.syncmusic

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * The 🎚 Mixer screen. On the host the device row picks which phone (or all of them) the
 * controls apply to; a client only has itself.
 */
class MixerPanel(
    private val activity: Activity,
    private val root: View,
    /** (id, label) of each phone the controls can apply to. */
    private val devices: () -> List<Pair<String, String>>,
    private val getMix: (deviceId: String) -> Mix?,
    private val setMix: (deviceId: String, mix: Mix) -> Unit,
    /** Note shown under the EQ (e.g. that it can't change YouTube on this phone). */
    private val note: () -> String,
    /** Host only: every phone's track (empty on a client, which hides the section). */
    private val tracks: () -> List<Track> = { emptyList() },
    /** 🤖 Auto delay switch: current state, and what to do when it's flipped / 🔄 is pressed. */
    private val autoDelayOn: () -> Boolean = { false },
    private val onAutoDelay: (on: Boolean) -> Unit = {},
    private val onMeasure: () -> Unit = {},
) {
    /** One phone in the track view. [gapMs]: + = ahead of the host, null = no reading yet. */
    data class Track(
        /** Same id as in [devices] (SELF or a client's id); tapping the track opens its controls. */
        val id: String,
        val name: String,
        val isHost: Boolean,
        val gapMs: Long?,
        val rttMs: Long?,
        val delayMs: Int,
        val bluetooth: Boolean,
    )

    private val trackTicker = object : Runnable {
        override fun run() {
            if (!isOpen) return
            renderTracks()
            root.postDelayed(this, 1000)
        }
    }
    companion object {
        const val SELF = "self"
        const val ALL = "all"
    }

    private val deviceRow: LinearLayout = root.findViewById(R.id.mixerDevices)
    private val volume: SeekBar = root.findViewById(R.id.mixerVolume)
    private val volumeLabel: TextView = root.findViewById(R.id.mixerVolumeLabel)
    private val mute: Button = root.findViewById(R.id.mixerMute)
    private val boostLock: Button = root.findViewById(R.id.mixerBoostLock)
    private val delay: SeekBar = root.findViewById(R.id.mixerDelay)
    private val delayLabel: TextView = root.findViewById(R.id.mixerDelayLabel)
    private val autoSwitch: androidx.appcompat.widget.SwitchCompat = root.findViewById(R.id.mixerAutoDelay)
    private val autoStatus: TextView = root.findViewById(R.id.mixerAutoStatus)
    private val bandSliders = mutableListOf<SeekBar>()
    /** Volume/Delay/EQ. Host: hidden until a track is tapped, then shown right under it. */
    private val controls: LinearLayout = root.findViewById(R.id.mixerControls)
    private val controlsHome = controls.parent as ViewGroup
    private val controlsHomeIndex = controlsHome.indexOfChild(controls)
    /** Host: the track whose controls are open (ALL = the "every phone" row), null = all closed. */
    private var expanded: String? = null
    /** Which rows the track list was last built with; rebuilt only when this changes. */
    private var trackLayout = ""
    private val bandValues = mutableListOf<TextView>()

    private var selected = SELF
    /** Settings shown on screen for [selected]; edits change this and are sent with setMix. */
    private var mix = Mix()
    /** Don't overwrite the screen with incoming updates while a finger is on a slider. */
    private var touching = false
    private var updating = false

    val isOpen get() = root.visibility == View.VISIBLE

    init {
        root.findViewById<Button>(R.id.mixerClose).setOnClickListener { hide() }
        autoSwitch.isChecked = autoDelayOn()
        autoSwitch.setOnCheckedChangeListener { _, on ->
            showAutoRow()
            onAutoDelay(on)
        }
        root.findViewById<Button>(R.id.mixerAutoRun).setOnClickListener { onMeasure() }
        showAutoRow()
        volume.setOnSeekBarChangeListener(listener { value -> change(mix.copy(volume = value)) })
        mute.setOnClickListener { change(mix.copy(muted = !mix.muted)) }
        // Slider steps of 10 ms from -500 to +500; the buttons fine-tune by 10 ms.
        delay.setOnSeekBarChangeListener(listener { step -> change(mix.copy(delayMs = step * 10 + Mix.MIN_DELAY_MS)) })
        root.findViewById<Button>(R.id.mixerDelayMinus).setOnClickListener {
            change(mix.copy(delayMs = (mix.delayMs - 10).coerceAtLeast(Mix.MIN_DELAY_MS)))
        }
        root.findViewById<Button>(R.id.mixerDelayPlus).setOnClickListener {
            change(mix.copy(delayMs = (mix.delayMs + 10).coerceAtMost(Mix.MAX_DELAY_MS)))
        }
        boostLock.setOnClickListener {
            if (mix.boostUnlocked) {
                // Lock again: back to at most 100% right away.
                change(mix.copy(boostUnlocked = false, volume = mix.volume.coerceAtMost(100)))
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("⚠ បើក Boost (101-200%)?")
                    .setMessage(
                        "Boost ធ្វើឲ្យសំឡេងខ្លាំងលើសពីកម្រិតធម្មតារបស់ទូរស័ព្ទ។\n\n" +
                            "• អាចធ្វើឲ្យសំឡេងបែក\n• ប្រើយូរៗ អាចធ្វើឲ្យខូចឧបាល័រ\n• កុំប្រើជាមួយកាសស្ដាប់ត្រចៀក\n\n" +
                            "ចាប់ផ្ដើមពីកម្រិតទាប ហើយឡើងបន្តិចម្ដងៗ។"
                    )
                    .setPositiveButton("🔓 យល់ព្រម បើក") { _, _ -> change(mix.copy(boostUnlocked = true)) }
                    .setNegativeButton("បោះបង់", null)
                    .show()
            }
        }

        val presets: LinearLayout = root.findViewById(R.id.mixerPresets)
        for ((name, levels) in Mix.PRESETS) {
            presets.addView(Button(activity).apply {
                text = name
                isAllCaps = false
                setOnClickListener { change(mix.copy(bands = levels)) }
            })
        }

        val bandsBox: LinearLayout = root.findViewById(R.id.mixerBands)
        Mix.BAND_LABELS.forEachIndexed { i, label ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            val slider = SeekBar(activity).apply { max = Mix.BAND_MAX_DB - Mix.BAND_MIN_DB }
            slider.setOnSeekBarChangeListener(listener { value ->
                change(mix.copy(bands = mix.bands.toMutableList().also { it[i] = value + Mix.BAND_MIN_DB }))
            })
            row.addView(slider, LinearLayout.LayoutParams(0, dp(44), 1f))
            val value = TextView(activity).apply {
                setTextColor(Color.LTGRAY)
                gravity = Gravity.END
            }
            row.addView(value, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            bandsBox.addView(row)
            bandSliders += slider
            bandValues += value
        }
    }

    fun show() {
        expanded = null
        trackLayout = ""
        root.visibility = View.VISIBLE
        renderTracks()
        refresh(force = true)
        root.removeCallbacks(trackTicker)
        root.post(trackTicker)
    }

    private fun renderTracks() {
        val list = tracks()
        val section = root.findViewById<View>(R.id.mixerTracksSection)
        section.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.mixerLegend).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) {
            // Client: just its own controls, always open, in their usual place.
            if (controls.parent !== controlsHome) {
                (controls.parent as? ViewGroup)?.removeView(controls)
                controlsHome.addView(controls, controlsHomeIndex)
            }
            controls.visibility = View.VISIBLE
            return
        }
        if (expanded != null && expanded != ALL && list.none { it.id == expanded }) expanded = null
        val box: LinearLayout = root.findViewById(R.id.mixerTracks)
        // Rebuild only when phones join/leave or another track opens: rebuilding under a finger
        // on a slider would break the drag.
        val layout = list.joinToString(",") { it.id } + "|" + expanded
        if (layout != trackLayout) {
            trackLayout = layout
            (controls.parent as? ViewGroup)?.removeView(controls)
            box.removeAllViews()
            for (track in list) {
                box.addView(makeTrackRow().apply { tag = track.id; setOnClickListener { toggle(track.id) } })
                if (track.id == expanded) box.addView(controls)
            }
            box.addView(makeTrackRow().apply {
                tag = ALL
                getChildAt(1).visibility = View.GONE
                getChildAt(2).visibility = View.GONE
                setOnClickListener { toggle(ALL) }
            })
            if (expanded == null || expanded == ALL) box.addView(controls)
        }
        controls.visibility = if (expanded != null) View.VISIBLE else View.GONE
        val byId = list.associateBy { it.id }
        for (i in 0 until box.childCount) {
            val row = box.getChildAt(i) as? LinearLayout ?: continue
            if (row === controls) continue
            val id = row.tag as? String ?: continue
            val open = id == expanded
            row.setBackgroundColor(if (open) 0xFF1A1F26.toInt() else Color.TRANSPARENT)
            val track = byId[id]
            if (track != null) {
                bindTrackRow(row, track, open)
            } else if (id == ALL) {
                (row.getChildAt(0) as TextView).text = (if (open) "▾ " else "▸ ") + "🔊 ទាំងអស់ — Volume/EQ គ្រប់ទូរស័ព្ទ"
            }
        }
    }

    /** Tap a track: open its controls (closing any other), tap again to close. */
    private fun toggle(id: String) {
        expanded = if (expanded == id) null else id
        expanded?.let { selected = it }
        renderTracks()
        refresh(force = true)
    }

    private fun makeTrackRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(4))
        isClickable = true
        addView(TextView(activity).apply { setTextColor(Color.WHITE); textSize = 14f })
        addView(TrackBarView(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        addView(TextView(activity).apply { setTextColor(Color.LTGRAY); textSize = 12f })
    }

    private fun bindTrackRow(row: LinearLayout, track: Track, open: Boolean) {
        (row.getChildAt(0) as TextView).text = (if (open) "▾ " else "▸ ") +
            (if (track.isHost) "👑 " else "📱 ") + track.name +
            if (track.bluetooth) "  🎧" else ""
        (row.getChildAt(1) as TrackBarView).errorMs = if (track.isHost) 0 else track.gapMs
        val gap = track.gapMs
        val gapText = when {
            track.isHost -> "0 ms (ទូរស័ព្ទមេ)"
            gap == null -> "— (មិនទាន់ចាក់)"
            gap > 0 -> "+$gap ms លឿនជាង"
            gap < 0 -> "$gap ms យឺតជាង"
            else -> "0 ms"
        }
        val rtt = track.rttMs?.let { " · RTT $it ms" } ?: ""
        val delay = if (track.delayMs != 0) " · ⏱ ${if (track.delayMs > 0) "+" else ""}${track.delayMs} ms" else ""
        (row.getChildAt(2) as TextView).text = gapText + rtt + delay
    }

    private fun showAutoRow() {
        root.findViewById<View>(R.id.mixerAutoRow).visibility = if (autoSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /** Progress/result of the 🤖 Auto delay measurement. */
    fun setAutoStatus(text: String) {
        autoStatus.text = text
    }

    fun hide() {
        root.visibility = View.GONE
    }

    /** Re-read devices and the selected phone's settings (e.g. a client reported a change). */
    fun refresh(force: Boolean = false) {
        if (!isOpen || (touching && !force)) return
        val list = devices()
        if (list.none { it.first == selected }) selected = SELF
        // On the host the tracks above pick the phone, so no extra row of buttons.
        root.findViewById<View>(R.id.mixerDevicesScroll).visibility =
            if (list.size > 1 && tracks().isEmpty()) View.VISIBLE else View.GONE
        deviceRow.removeAllViews()
        for ((id, label) in list) {
            deviceRow.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                // Selected: white on blue. Others: black on light grey (white text was unreadable there).
                val isSelected = id == selected
                backgroundTintList = ColorStateList.valueOf(if (isSelected) 0xFF1565C0.toInt() else 0xFFE0E0E0.toInt())
                setTextColor(if (isSelected) Color.WHITE else Color.BLACK)
                setOnClickListener {
                    selected = id
                    refresh(force = true)
                }
            })
        }
        mix = getMix(selected) ?: Mix()
        render()
        root.findViewById<TextView>(R.id.mixerNote).text = note()
    }

    private fun change(newMix: Mix) {
        if (updating) return
        mix = newMix
        render()
        setMix(selected, newMix)
    }

    private fun render() {
        updating = true
        delay.progress = (mix.delayMs - Mix.MIN_DELAY_MS) / 10
        delayLabel.text = "⏱ Delay: " + (if (mix.delayMs > 0) "+" else "") + "${mix.delayMs} ms" +
            if (mix.bluetooth) "   🎧 Bluetooth (ជាធម្មតា +150 ដល់ +250)" else ""
        // One delay for every phone makes no sense: only per phone.
        root.findViewById<View>(R.id.mixerDelaySection).visibility = if (selected == ALL) View.GONE else View.VISIBLE
        // Locked: the slider stops at 100%.
        volume.max = if (mix.boostUnlocked) Mix.MAX_VOLUME else 100
        volume.progress = mix.volume
        boostLock.text = if (mix.boostUnlocked) "🔓 Boost កំពុងបើក (ដល់ 200%) — ចុចដើម្បីចាក់សោវិញ"
            else "🔒 Boost ត្រូវបានចាក់សោ (អតិបរមា 100%) — ចុចដើម្បីបើក"
        boostLock.backgroundTintList = ColorStateList.valueOf(if (mix.boostUnlocked) 0xFFB71C1C.toInt() else 0xFF424242.toInt())
        boostLock.setTextColor(Color.WHITE)
        volumeLabel.text = "🔊 Volume: ${mix.volume}%" +
            (if (mix.volume > 100) "  🚀 Boost" else "") +
            if (mix.muted) "  (🔇 បិទសំឡេង)" else ""
        mute.text = if (mix.muted) "🔈 បើកសំឡេង" else "🔇 Mute"
        mix.bands.forEachIndexed { i, db ->
            bandSliders[i].progress = db - Mix.BAND_MIN_DB
            bandValues[i].text = (if (db > 0) "+" else "") + "$db dB"
        }
        updating = false
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            touching = true
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            touching = false
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
