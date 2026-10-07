package com.pedrolopes.ttsing.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.library.LibraryScreen
import com.pedrolopes.ttsing.ui.news.ArticlesScreen
import com.pedrolopes.ttsing.ui.news.DiscoverScreen
import com.pedrolopes.ttsing.ui.news.FeedsScreen
import com.pedrolopes.ttsing.ui.news.VideoScreen
import com.pedrolopes.ttsing.ui.reader.ReaderScreen

object Routes {
    const val LIBRARY = "library"
    const val READER = "reader"
    const val FEEDS = "feeds"
    /** All feeds' stories together — where the library's News button goes directly. */
    const val NEWS = "news"
    /** One feed's own stories, reached from "Manage feeds". */
    const val ARTICLES = "articles"
    /** The topic catalogue: the biggest sources per topic, to subscribe in a tap. */
    const val DISCOVER = "discover"
    /** A video from a subscribed YouTube channel, by article id. */
    const val VIDEO = "video"
    const val ARG_BOOK_ID = "bookId"
    const val ARG_FEED_URL = "feedUrl"

    /** Also used for news articles, whose ids carry an `article:` prefix. */
    fun reader(bookId: String) = "$READER/${Uri.encode(bookId)}"

    fun video(articleId: String) = "$VIDEO/${Uri.encode(articleId)}"

    fun articles(feedUrl: String) = "$ARTICLES/${Uri.encode(feedUrl)}"
}

@Composable
fun TTSingNavHost(
    controller: ReadingController,
    navController: NavHostController = rememberNavController(),
    startBookId: String? = null,
    openedFile: Intent? = null,
    onOpenedFileHandled: () -> Unit = {},
) {
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                onOpenNews = { navController.navigate(Routes.NEWS) },
            )
        }
        composable(Routes.NEWS) {
            ArticlesScreen(
                feedUrl = null,
                onBack = { navController.popBackStack() },
                onOpenArticle = { articleId -> navController.navigate(Routes.reader(articleId)) },
                // Two voices at once is noise: the video takes over from whatever was being read.
                onOpenVideo = { articleId ->
                    controller.pause()
                    navController.navigate(Routes.video(articleId))
                },
                onManageFeeds = { navController.navigate(Routes.FEEDS) },
                onDiscover = { navController.navigate(Routes.DISCOVER) },
            )
        }
        composable(Routes.FEEDS) {
            FeedsScreen(
                onBack = { navController.popBackStack() },
                onOpenFeed = { feedUrl -> navController.navigate(Routes.articles(feedUrl)) },
                onDiscover = { navController.navigate(Routes.DISCOVER) },
            )
        }
        composable(Routes.DISCOVER) {
            DiscoverScreen(onBack = { navController.popBackStack() })
        }
        composable("${Routes.ARTICLES}/{${Routes.ARG_FEED_URL}}") { entry ->
            val feedUrl = entry.arguments?.getString(Routes.ARG_FEED_URL)?.let(Uri::decode)
                ?: return@composable
            ArticlesScreen(
                feedUrl = feedUrl,
                onBack = { navController.popBackStack() },
                onOpenArticle = { articleId -> navController.navigate(Routes.reader(articleId)) },
                // Two voices at once is noise: the video takes over from whatever was being read.
                onOpenVideo = { articleId ->
                    controller.pause()
                    navController.navigate(Routes.video(articleId))
                },
                onManageFeeds = { navController.navigate(Routes.FEEDS) },
                onDiscover = { navController.navigate(Routes.DISCOVER) },
            )
        }
        composable("${Routes.VIDEO}/{${Routes.ARG_BOOK_ID}}") { entry ->
            val articleId = entry.arguments?.getString(Routes.ARG_BOOK_ID)?.let(Uri::decode)
                ?: return@composable
            VideoScreen(articleId = articleId, onBack = { navController.popBackStack() })
        }
        composable("${Routes.READER}/{${Routes.ARG_BOOK_ID}}") { entry ->
            val bookId = entry.arguments?.getString(Routes.ARG_BOOK_ID)?.let(Uri::decode)
                ?: return@composable
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
                controller = controller,
                onFollowPlayback = { nextId ->
                    // Replace rather than stack: Back still returns to the story list, not
                    // through every story that played since.
                    navController.navigate(Routes.reader(nextId)) {
                        popUpTo(entry.destination.id) { inclusive = true }
                    }
                },
            )
        }
    }

    // An EPUB or PDF opened from another app: into the library, then straight to reading it.
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(openedFile) {
        val file = openedFile ?: return@LaunchedEffect
        runCatching { TTSingApp.instance.books.addOpenedFile(file.data!!, file.type) }
            // A fresh reader over the library: reusing one already open would keep its book.
            .onSuccess { bookId -> navController.navigate(Routes.reader(bookId)) { popUpTo(Routes.LIBRARY) } }
            .onFailure { Toast.makeText(context, it.message ?: "Couldn't open that file", Toast.LENGTH_LONG).show() }
        onOpenedFileHandled()
    }

    // Deep link from the media notification: jump straight into what's playing.
    androidx.compose.runtime.LaunchedEffect(startBookId) {
        if (startBookId != null) {
            navController.navigate(Routes.reader(startBookId)) {
                launchSingleTop = true
            }
        }
    }
}
