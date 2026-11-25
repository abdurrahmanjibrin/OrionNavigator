package com.orion.app.ui

import android.app.AlertDialog
import android.content.Context
import com.orion.app.data.LocationDetail

/**
 * Dialog untuk menampilkan informasi detail tempat (deskripsi & fasilitas)
 * bagi pengguna low-vision atau pendamping saat tombol detail tempat ditekan.
 */
object LocationDetailDialog {

    fun show(context: Context, detail: LocationDetail, onDismiss: (() -> Unit)? = null) {
        val facilitiesText = detail.facilities.joinToString("\n• ", prefix = "• ")
        val message = "${detail.shortDescription}\n\n" +
                "${detail.detailedDescription}\n\n" +
                "Fasilitas:\n$facilitiesText"

        AlertDialog.Builder(context)
            .setTitle("ℹ️ ${detail.locationName}")
            .setMessage(message)
            .setPositiveButton("Tutup") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setOnDismissListener {
                onDismiss?.invoke()
            }
            .show()
    }
}

