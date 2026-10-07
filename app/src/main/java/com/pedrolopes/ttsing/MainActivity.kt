package com.pedrolopes.ttsing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.TTSingNavHost
import com.pedrolopes.ttsing.ui.theme.TTSingTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var controller: ReadingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ReadingController(this)
        // While the app is on screen the screen stays on and unlocked, so a page being read
        // along doesn't go dark mid-chapter. The flag only holds while this window is visible:
        // leave the app and the phone sleeps as usual, with playback carrying on in the service.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The app is black under both system settings, so the bars are told to draw their
        // icons light rather than left to follow the device's day/night state.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val initialBookId = intent?.getStringExtra(EXTRA_BOOK_ID)
        takeOpenedFile(intent)

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

                val file by openedFile.collectAsState()
                TTSingNavHost(
                    controller = controller,
                    startBookId = startBookId,
                    openedFile = file,
                    onOpenedFileHandled = { openedFile.value = null },
                )
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
        takeOpenedFile(intent)
    }

    /** An EPUB or PDF another app asked us to open, waiting for the UI to add and open it. */
    private val openedFile = MutableStateFlow<Intent?>(null)

    private fun takeOpenedFile(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openedFile.value = intent
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }
}
