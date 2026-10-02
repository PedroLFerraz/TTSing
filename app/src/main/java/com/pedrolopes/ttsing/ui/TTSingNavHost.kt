package com.pedrolopes.ttsing.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.library.LibraryScreen
import com.pedrolopes.ttsing.ui.news.ArticlesScreen
import com.pedrolopes.ttsing.ui.news.DiscoverScreen
import com.pedrolopes.ttsing.ui.news.FeedsScreen
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
    const val ARG_BOOK_ID = "bookId"
    const val ARG_FEED_URL = "feedUrl"

    /** Also used for news articles, whose ids carry an `article:` prefix. */
    fun reader(bookId: String) = "$READER/${Uri.encode(bookId)}"

    fun articles(feedUrl: String) = "$ARTICLES/${Uri.encode(feedUrl)}"
}

@Composable
fun TTSingNavHost(
    controller: ReadingController,
    navController: NavHostController = rememberNavController(),
    startBookId: String? = null,
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
                onManageFeeds = { navController.navigate(Routes.FEEDS) },
                onDiscover = { navController.navigate(Routes.DISCOVER) },
            )
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

    // Deep link from the media notification: jump straight into what's playing.
    androidx.compose.runtime.LaunchedEffect(startBookId) {
        if (startBookId != null) {
            navController.navigate(Routes.reader(startBookId)) {
                launchSingleTop = true
            }
        }
    }
}
