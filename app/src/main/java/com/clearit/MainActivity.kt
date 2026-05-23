package com.clearit

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private enum class MediaType { IMAGE, VIDEO }

    private var selectedMediaUri: Uri? = null
    private var selectedMediaType: MediaType? = null
    private var pendingEnhancement: Pair<Uri, MediaType>? = null

    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingEnhancement
            pendingEnhancement = null
            if (granted && pending != null) {
                enhanceMedia(pending.first, pending.second)
            } else if (!granted) {
                Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_SHORT).show()
            }
        }

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedMediaUri = uri
            val type = contentResolver.getType(uri)
            selectedMediaType = when {
                type?.startsWith("image/") == true -> MediaType.IMAGE
                type?.startsWith("video/") == true -> MediaType.VIDEO
                else -> null
            }
            findViewById<TextView>(R.id.selectedMediaLabel).text = getString(R.string.selected_media, uri.lastPathSegment)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.selectImageButton).setOnClickListener {
            pickMediaLauncher.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.selectVideoButton).setOnClickListener {
            pickMediaLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.enhanceButton).setOnClickListener {
            val uri = selectedMediaUri
            val type = selectedMediaType
            if (uri == null) {
                Toast.makeText(this, R.string.select_media_prompt, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (type == null) {
                Toast.makeText(this, R.string.unsupported_media, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (requiresLegacyWritePermission() && !hasLegacyWritePermission()) {
                pendingEnhancement = uri to type
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return@setOnClickListener
            }
            enhanceMedia(uri, type)
        }
    }

    private fun requiresLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    private fun hasLegacyWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enhanceMedia(uri: Uri, type: MediaType) {
        val statusView = findViewById<TextView>(R.id.statusLabel)
        statusView.text = getString(R.string.processing, getMediaTypeLabel(type))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (type) {
                    MediaType.IMAGE -> ImageEnhancer().enhance(this@MainActivity, uri)
                    MediaType.VIDEO -> VideoEnhancer().enhance(this@MainActivity, uri)
                }
            }

            result.onSuccess {
                val albumName = when (type) {
                    MediaType.IMAGE -> getString(R.string.album_images)
                    MediaType.VIDEO -> getString(R.string.album_videos)
                }
                statusView.text = getString(R.string.output_ready, albumName)
            }.onFailure { error ->
                statusView.text = getString(R.string.processing_failed, error.localizedMessage)
            }
        }
    }

    private fun getMediaTypeLabel(type: MediaType): String {
        val labelRes = when (type) {
            MediaType.IMAGE -> R.string.media_type_image
            MediaType.VIDEO -> R.string.media_type_video
        }
        return getString(labelRes)
    }
}
