package com.orion.app.tts

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.orion.app.R
import com.orion.app.ui.SplashActivity
import kotlin.math.abs

/**
 * Reusable Accessibility-style TTS Speech Rate dialog.
 * Allows adjusting speech rate from 0.5x to 2.0x with live sample playback,
 * quick presets, and accessible - / + stepper buttons.
 */
object TtsSettingsDialog {

    private const val SAMPLE_PHRASE =
        "Ini adalah contoh kecepatan suara navigasi Orion. Lurus sepuluh langkah, lalu belok kanan."

    fun show(
        context: Context,
        onPreviewSpeak: (text: String, rate: Float) -> Unit,
        onRestoreRate: (rate: Float) -> Unit,
        onSaved: (newRate: Float) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_tts_speed, null)

        val tvSpeedValue = view.findViewById<TextView>(R.id.tvTtsSpeedValue)
        val slider = view.findViewById<Slider>(R.id.sliderTtsSpeed)
        val btnSlowDown = view.findViewById<MaterialButton>(R.id.btnTtsSlowDown)
        val btnSpeedUp = view.findViewById<MaterialButton>(R.id.btnTtsSpeedUp)
        val btnPresetSlow = view.findViewById<MaterialButton>(R.id.btnPresetSlow)
        val btnPresetNormal = view.findViewById<MaterialButton>(R.id.btnPresetNormal)
        val btnPresetFast = view.findViewById<MaterialButton>(R.id.btnPresetFast)
        val btnPresetVeryFast = view.findViewById<MaterialButton>(R.id.btnPresetVeryFast)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnTestTtsSpeed)
        val btnReset = view.findViewById<MaterialButton>(R.id.btnResetTtsSpeed)

        val initialRate = TTSManager.getSavedSpeechRate(context)
        var selectedRate = initialRate
        var isSaved = false

        val primaryColor = ContextCompat.getColor(context, R.color.primary)
        val cardBgColor = ContextCompat.getColor(context, R.color.background_card)
        val textPrimaryColor = ContextCompat.getColor(context, R.color.text_primary)
        val whiteColor = ContextCompat.getColor(context, R.color.white)
        val borderColor = ContextCompat.getColor(context, R.color.glass_border)

        val presets = listOf(
            btnPresetSlow to 0.7f,
            btnPresetNormal to 0.9f,
            btnPresetFast to 1.3f,
            btnPresetVeryFast to 1.7f
        )

        fun updateUi(rate: Float, fromSlider: Boolean = false) {
            val clamped = TTSManager.clampSpeechRate(rate)
            selectedRate = clamped
            tvSpeedValue.text = TTSManager.getSpeechRateLabel(clamped)
            if (!fromSlider) {
                slider.value = clamped
            }

            presets.forEach { (btn, presetVal) ->
                val isSelected = abs(clamped - presetVal) < 0.05f
                btn.backgroundTintList = ColorStateList.valueOf(if (isSelected) primaryColor else cardBgColor)
                btn.setTextColor(if (isSelected) whiteColor else textPrimaryColor)
                btn.strokeColor = ColorStateList.valueOf(if (isSelected) primaryColor else borderColor)
            }
        }

        updateUi(initialRate)

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updateUi(value, fromSlider = true)
            }
        }

        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                val rate = TTSManager.clampSpeechRate(slider.value)
                updateUi(rate, fromSlider = true)
                onPreviewSpeak("Kecepatan ${TTSManager.getSpeechRateLabel(rate)}", rate)
            }
        })

        btnSlowDown.setOnClickListener {
            val next = TTSManager.clampSpeechRate(selectedRate - 0.1f)
            updateUi(next)
            onPreviewSpeak("Kecepatan ${TTSManager.getSpeechRateLabel(next)}", next)
        }

        btnSpeedUp.setOnClickListener {
            val next = TTSManager.clampSpeechRate(selectedRate + 0.1f)
            updateUi(next)
            onPreviewSpeak("Kecepatan ${TTSManager.getSpeechRateLabel(next)}", next)
        }

        presets.forEach { (btn, presetVal) ->
            btn.setOnClickListener {
                updateUi(presetVal)
                onPreviewSpeak(SAMPLE_PHRASE, presetVal)
            }
        }

        btnTest.setOnClickListener {
            onPreviewSpeak(SAMPLE_PHRASE, selectedRate)
        }

        btnReset.setOnClickListener {
            val def = SplashActivity.DEFAULT_TTS_SPEECH_RATE
            updateUi(def)
            onPreviewSpeak("Kecepatan suara direset ke normal, ${TTSManager.getSpeechRateLabel(def)}", def)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("Simpan") { _, _ ->
                isSaved = true
                TTSManager.saveSpeechRate(context, selectedRate)
                onSaved(selectedRate)
                val msg = "Kecepatan suara disimpan ke ${TTSManager.getSpeechRateLabel(selectedRate)}"
                onPreviewSpeak(msg, selectedRate)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal") { d, _ ->
                d.dismiss()
            }
            .create()

        dialog.setOnDismissListener {
            if (!isSaved) {
                onRestoreRate(initialRate)
            }
        }

        dialog.show()
    }
}

