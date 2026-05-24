package com.orion.app.voice

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import com.orion.app.R

/**
 * Helper class to show a "listening" popup dialog similar to OK Google.
 * Shows a pulsing mic icon and status text when wake word "Hello Orion" is detected.
 */
class ListeningDialogHelper(private val context: Context) {

    private var dialog: Dialog? = null
    private var autoDismissHandler: Handler? = null
    private var autoDismissRunnable: Runnable? = null

    companion object {
        private const val AUTO_DISMISS_MS = 6000L  // Auto dismiss after 6 seconds
    }

    /**
     * Show the listening popup.
     * @param statusText Main text e.g. "Mendengarkan..."
     * @param hintText Secondary hint e.g. "Ucapkan perintah Anda"
     */
    fun show(
        statusText: String = "Mendengarkan...",
        hintText: String = "Ucapkan perintah Anda"
    ) {
        // Dismiss existing dialog if any
        dismiss()

        dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_listening)
            setCancelable(true)
            setCanceledOnTouchOutside(true)

            // Transparent background so our custom shape shows
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                setGravity(Gravity.CENTER)
                // Dim behind
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.4f)
                // Smooth enter/exit animation
                setWindowAnimations(android.R.style.Animation_Dialog)
            }

            // Set texts
            findViewById<TextView>(R.id.tvListeningStatus)?.text = statusText
            findViewById<TextView>(R.id.tvListeningHint)?.text = hintText

            // Start pulse ring animation
            val pulseRing = findViewById<View>(R.id.viewPulseRing)
            try {
                val pulseAnim = AnimationUtils.loadAnimation(context, R.anim.pulse_ring)
                pulseRing?.startAnimation(pulseAnim)
            } catch (e: Exception) {
                // Animation not critical
            }

            show()
        }

        // Auto-dismiss after timeout
        scheduleAutoDismiss()
    }

    /**
     * Update the status text while dialog is showing (e.g. "Memproses...")
     */
    fun updateStatus(statusText: String) {
        dialog?.findViewById<TextView>(R.id.tvListeningStatus)?.text = statusText
    }

    /**
     * Update the hint text while dialog is showing
     */
    fun updateHint(hintText: String) {
        dialog?.findViewById<TextView>(R.id.tvListeningHint)?.text = hintText
    }

    /**
     * Dismiss the dialog
     */
    fun dismiss() {
        cancelAutoDismiss()
        try {
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
            }
        } catch (e: Exception) {
            // Dialog may have already been dismissed
        }
        dialog = null
    }

    /**
     * Check if dialog is currently showing
     */
    fun isShowing(): Boolean = dialog?.isShowing == true

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        autoDismissHandler = Handler(Looper.getMainLooper())
        autoDismissRunnable = Runnable { dismiss() }
        autoDismissHandler?.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_MS)
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { autoDismissHandler?.removeCallbacks(it) }
        autoDismissHandler = null
        autoDismissRunnable = null
    }
}
