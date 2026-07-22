package com.pedrolopes.ttsing.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.library.LibraryScreen
import com.pedrolopes.ttsing.ui.reader.ReaderScreen

object Routes {
    const val LIBRARY = "library"
    const val READER = "reader"
    const val ARG_BOOK_ID = "bookId"
    fun reader(bookId: String) = "$READER/$bookId"
}

@Composable
fun TTSingNavHost(
    controller: ReadingController,
    navController: NavHostController = rememberNavController(),
    startBookId: String? = null,
) {
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) })
        }
        composable("${Routes.READER}/{${Routes.ARG_BOOK_ID}}") { entry ->
            val bookId = entry.arguments?.getString(Routes.ARG_BOOK_ID) ?: return@composable
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
                controller = controller,
            )
        }
    }

    // Deep link from the media notification: jump straight into the playing book.
    androidx.compose.runtime.LaunchedEffect(startBookId) {
        if (startBookId != null) {
            navController.navigate(Routes.reader(startBookId)) {
                launchSingleTop = true
            }
        }
    }
}
