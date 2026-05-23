package com.clearit

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private var selectedVideoUri: Uri? = null

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedVideoUri = uri
            findViewById<TextView>(R.id.selectedVideoLabel).text = getString(R.string.selected_video, uri.lastPathSegment)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.selectVideoButton).setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.enhanceButton).setOnClickListener {
            val uri = selectedVideoUri
            if (uri == null) {
                Toast.makeText(this, R.string.select_video_prompt, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enhanceVideo(uri)
        }
    }

    private fun enhanceVideo(uri: Uri) {
        val statusView = findViewById<TextView>(R.id.statusLabel)
        statusView.text = getString(R.string.processing)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VideoEnhancer().enhance(this@MainActivity, uri)
            }

            result.onSuccess { outputUri ->
                statusView.text = getString(R.string.output_ready, outputUri.path)
            }.onFailure { error ->
                statusView.text = getString(R.string.processing_failed, error.localizedMessage)
            }
        }
    }
}
