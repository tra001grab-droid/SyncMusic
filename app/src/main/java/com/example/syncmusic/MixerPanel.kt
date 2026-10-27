package com.example.syncmusic

import android.app.Activity
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
) {
    companion object {
        const val SELF = "self"
        const val ALL = "all"
    }

    private val deviceRow: LinearLayout = root.findViewById(R.id.mixerDevices)
    private val volume: SeekBar = root.findViewById(R.id.mixerVolume)
    private val volumeLabel: TextView = root.findViewById(R.id.mixerVolumeLabel)
    private val mute: Button = root.findViewById(R.id.mixerMute)
    private val bandSliders = mutableListOf<SeekBar>()
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
        volume.setOnSeekBarChangeListener(listener { value -> change(mix.copy(volume = value)) })
        mute.setOnClickListener { change(mix.copy(muted = !mix.muted)) }

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
        root.visibility = View.VISIBLE
        refresh(force = true)
    }

    fun hide() {
        root.visibility = View.GONE
    }

    /** Re-read devices and the selected phone's settings (e.g. a client reported a change). */
    fun refresh(force: Boolean = false) {
        if (!isOpen || (touching && !force)) return
        val list = devices()
        if (list.none { it.first == selected }) selected = SELF
        root.findViewById<View>(R.id.mixerDevicesScroll).visibility = if (list.size > 1) View.VISIBLE else View.GONE
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
        volume.progress = mix.volume
        volumeLabel.text = "🔊 Volume: ${mix.volume}%" + if (mix.muted) "  (🔇 បិទសំឡេង)" else ""
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
