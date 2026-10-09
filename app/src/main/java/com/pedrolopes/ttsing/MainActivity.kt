package com.pedrolopes.ttsing

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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

        takeOpenedFile(intent)
        takeBookId(intent)

        setContent {
            TTSingTheme {
                val startBookId by bookToOpen.collectAsState()
                // The nav host acts on a book id once, when it changes. Cleared after it has
                // seen it (this effect starts first, but the host's closure already holds the
                // id), so tapping the notification for the same book again opens it again.
                LaunchedEffect(startBookId) { if (startBookId != null) bookToOpen.value = null }

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
        takeBookId(intent)
    }

    /** An EPUB or PDF another app asked us to open, waiting for the UI to add and open it. */
    private val openedFile = MutableStateFlow<Intent?>(null)

    /** The book the notification asked for, waiting for the UI to open its reader. */
    private val bookToOpen = MutableStateFlow<String?>(null)

    /**
     * The notification's tap, at launch or (singleTop) on the running app. The extra is taken
     * off the intent so an activity recreation, which replays it, doesn't open the book again.
     */
    private fun takeBookId(intent: Intent?) {
        val bookId = intent?.getStringExtra(EXTRA_BOOK_ID) ?: return
        intent.removeExtra(EXTRA_BOOK_ID)
        bookToOpen.value = bookId
    }

    private fun takeOpenedFile(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openedFile.value = intent
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }
}
