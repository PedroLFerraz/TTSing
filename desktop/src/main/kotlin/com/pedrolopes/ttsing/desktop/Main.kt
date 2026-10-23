package com.pedrolopes.ttsing.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pedrolopes.ttsing.desktop.library.Library
import com.pedrolopes.ttsing.desktop.tts.Narrator
import com.pedrolopes.ttsing.desktop.ui.library.LibraryScreen
import com.pedrolopes.ttsing.desktop.ui.reader.ReaderScreen
import com.pedrolopes.ttsing.desktop.ui.theme.TTSingTheme
import com.pedrolopes.ttsing.tts.piper.PiperDownloads
import com.pedrolopes.ttsing.tts.piper.PiperVoiceFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Everything that outlives a screen: the library, the settings, the voices and the narrator. */
class App {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val dirs = AppDirs.default()
    val settings = SettingsStore(dirs)
    val library = Library(dirs)
    val voiceFolder = PiperVoiceFolder(dirs.voices)
    val downloads = PiperDownloads(voiceFolder, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val narrator = Narrator(scope)

    val libraryRoot: File? get() = settings.value.libraryFolder?.let(::File)

    fun syncLibrary() {
        val root = libraryRoot ?: return
        scope.launch { library.sync(root) }
    }

    fun close() {
        narrator.shutdown()
        scope.cancel()
    }
}

private sealed interface Screen {
    data object Shelf : Screen
    data class Reading(val bookId: String) : Screen
}

/**
 * Names the window `ttsing` to the desktop (its X11 WM_CLASS), so the launcher entry and the
 * window are known to be the same app; AWT otherwise names it after a thread class. Needs
 * `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED`, which the build passes.
 */
private fun nameWindowClass() = runCatching {
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, "ttsing")
}

fun main(args: Array<String>) {
    nameWindowClass()
    val app = App()
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 880.dp)
        Window(
            onCloseRequest = {
                app.close()
                exitApplication()
            },
            state = windowState,
            title = "TTSing",
            icon = remember { App::class.java.getResourceAsStream("/icon.svg")?.use { loadSvgPainter(it, Density(1f)) } },
        ) {
            TTSingTheme {
                TTSing(app, opened = args.firstOrNull()?.let(::File))
            }
        }
    }
}

@Composable
private fun TTSing(app: App, opened: File?) {
    var screen by remember { mutableStateOf<Screen>(Screen.Shelf) }
    var openError by remember { mutableStateOf<String?>(null) }
    val settings by app.settings.state.collectAsState()

    LaunchedEffect(settings.libraryFolder) { app.syncLibrary() }

    // "Open with TTSing": the file joins the library and opens.
    LaunchedEffect(opened) {
        val file = opened ?: return@LaunchedEffect
        val root = app.libraryRoot
        if (root == null) {
            openError = "Choose a books folder first, then open ${file.name} again"
            return@LaunchedEffect
        }
        runCatching { app.library.add(file, root) }
            .onSuccess { screen = Screen.Reading(it) }
            .onFailure { openError = it.message ?: "Couldn't open ${file.name}" }
    }

    when (val current = screen) {
        Screen.Shelf -> LibraryScreen(
            app = app,
            message = openError,
            onDismissMessage = { openError = null },
            onOpen = { screen = Screen.Reading(it) },
        )
        is Screen.Reading -> ReaderScreen(
            app = app,
            bookId = current.bookId,
            onBack = { screen = Screen.Shelf },
        )
    }
}
