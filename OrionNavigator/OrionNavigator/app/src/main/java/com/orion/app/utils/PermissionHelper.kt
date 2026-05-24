package com.orion.app.utils

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Helper class for managing camera permissions
 */
class PermissionHelper(
    private val activity: ComponentActivity,
    private val onPermissionResult: (Boolean) -> Unit
) {
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    init {
        registerPermissionLauncher()
    }

    private fun registerPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            onPermissionResult(isGranted)
        }
    }

    /**
     * Check if camera permission is granted
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request camera permission
     */
    fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    companion object {
        const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }
}
