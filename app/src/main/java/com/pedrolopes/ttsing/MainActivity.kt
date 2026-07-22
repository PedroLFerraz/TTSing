package com.pedrolopes.ttsing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.TTSingNavHost
import com.pedrolopes.ttsing.ui.theme.TTSingTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: ReadingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ReadingController(this)
        enableEdgeToEdge()

        val initialBookId = intent?.getStringExtra(EXTRA_BOOK_ID)

        setContent {
            TTSingTheme {
                val startBookId by remember { mutableStateOf(initialBookId) }

                val notifPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* playback works regardless; the notification just won't show if denied */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                TTSingNavHost(controller = controller, startBookId = startBookId)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.bind()
    }

    override fun onStop() {
        controller.unbind()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }
}
