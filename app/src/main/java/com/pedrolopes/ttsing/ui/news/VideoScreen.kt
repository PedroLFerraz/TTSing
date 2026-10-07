package com.pedrolopes.ttsing.ui.news

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.data.news.YouTube
import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import com.pedrolopes.ttsing.tts.VideoPlaybackService
import com.pedrolopes.ttsing.ui.common.HeaderIcon
import com.pedrolopes.ttsing.ui.common.PillButton
import com.pedrolopes.ttsing.ui.common.ScreenHeader
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink

/**
 * A video from a subscribed YouTube channel: YouTube's own embedded player, the title and
 * description, and a way out to the YouTube app. Opening it counts as having seen it.
 */
@Composable
fun VideoScreen(articleId: String, onBack: () -> Unit) {
    val news = TTSingApp.instance.news
    val article by produceState<ArticleEntity?>(null, articleId) { value = news.article(articleId) }
    LaunchedEffect(articleId) { news.savePosition(articleId, 0, 0) }
    val uriHandler = LocalUriHandler.current

    Scaffold(containerColor = Ink.Surface) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = "VIDEO",
                leading = { HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink.Text, onClick = onBack) },
            )
            val video = article ?: return@Column
            YouTube.videoId(video.link)?.let { id ->
                YouTubePlayer(id, video.title, Modifier.padding(top = 14.dp).fillMaxWidth().aspectRatio(16f / 9f))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding, vertical = 16.dp),
            ) {
                Text(
                    video.title,
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.Text,
                )
                video.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Ink.Muted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            PillButton(
                text = "Open in YouTube",
                onClick = { uriHandler.openUri(video.link) },
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp),
            )
        }
    }
}

/**
 * YouTube's embed player, through its IFrame API, in a small page of our own. YouTube refuses
 * embeds that don't say who is embedding them ("Error 153"), so the page is given an https
 * origin named after the app. Links out of the player go to the YouTube app or browser.
 *
 * Not the embed loaded as the page itself: a video filling a WebView's own page is drawn
 * differently, and came out as a white box with the sound playing.
 *
 * Playing on with the screen off: a WebView pauses its video the moment its window is hidden,
 * so while a video plays this one isn't told; [VideoPlaybackService] then keeps the app from being
 * frozen, and gets the play/pause from the page to show on the lock screen.
 *
 * Fullscreen: YouTube hands over its player view through [WebChromeClient.onShowCustomView],
 * which is laid over the whole window in landscape until the user leaves fullscreen — by
 * YouTube's own button or by Back, which otherwise would leave the screen underneath.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun YouTubePlayer(videoId: String, title: String, modifier: Modifier) {
    val activity = LocalActivity.current
    var exitFullscreen by remember { mutableStateOf<(() -> Unit)?>(null) }
    BackHandler(enabled = exitFullscreen != null) { exitFullscreen?.invoke() }
    AndroidView(
        modifier = modifier.background(Ink.Raised),
        factory = { context ->
            object : WebView(context) {
                // Hiding is swallowed only mid-video, as the app leaves the screen. Anything
                // more — even dropping the hide that comes before the window first shows —
                // leaves the player playing but never drawing.
                override fun onWindowVisibilityChanged(visibility: Int) {
                    if (visibility == View.VISIBLE || !VideoPlaybackService.isPlaying) super.onWindowVisibilityChanged(visibility)
                }
            }.apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = object : WebChromeClient() {
                    private var fullscreen: View? = null

                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        val window = activity?.window ?: return callback.onCustomViewHidden()
                        fullscreen = view
                        exitFullscreen = {
                            callback.onCustomViewHidden()
                            onHideCustomView()
                        }
                        (window.decorView as FrameLayout).addView(
                            view,
                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                        )
                        WindowCompat.getInsetsController(window, view).apply {
                            hide(WindowInsetsCompat.Type.systemBars())
                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }

                    override fun onHideCustomView() {
                        val window = activity?.window ?: return
                        val view = fullscreen ?: return
                        (window.decorView as FrameLayout).removeView(view)
                        fullscreen = null
                        exitFullscreen = null
                        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
                // Called on a WebView thread; the service is driven from the main one.
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun state(playing: Boolean) {
                            post { VideoPlaybackService.onPlayerState(context, playing) }
                        }
                    },
                    "TTSingVideo",
                )
                VideoPlaybackService.attach(this, title)
                loadDataWithBaseURL("https://${context.packageName}", playerHtml(videoId), "text/html", "utf-8", null)
            }
        },
        // Leaving the screen ends the video: no player left behind over the app, or playing
        // on unseen with the service gone.
        onRelease = { webView ->
            webView.webChromeClient?.onHideCustomView()
            VideoPlaybackService.stop(webView.context)
            webView.destroy()
        },
    )
}

/**
 * The player page. State is reported as YouTube's player gives it: playing or buffering count
 * as playing (a seek mid-video shouldn't let the app be frozen), paused or ended as stopped.
 * The service pauses and resumes through the global `player`.
 */
private fun playerHtml(videoId: String) = """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>body{margin:0;background:#000}iframe{position:fixed;inset:0;border:0;width:100%;height:100%}</style>
    </head><body><div id="player"></div>
    <script src="https://www.youtube.com/iframe_api"></script>
    <script>
      var player;
      function onYouTubeIframeAPIReady() {
        player = new YT.Player('player', {
          videoId: '$videoId',
          playerVars: { playsinline: 1, rel: 0 },
          events: {
            onStateChange: function (e) {
              if (e.data === 1 || e.data === 3) TTSingVideo.state(true);
              else if (e.data === 2 || e.data === 0) TTSingVideo.state(false);
            }
          }
        });
      }
    </script></body></html>
""".trimIndent()
