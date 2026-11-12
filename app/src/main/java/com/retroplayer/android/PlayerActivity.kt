package com.retroplayer.android

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import com.retroplayer.android.data.GameDatabase
import com.retroplayer.android.data.SettingsRepository
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

class PlayerActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var sessionStartedAt = 0L
    private var gameId = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gamePath = intent.getStringExtra(EXTRA_GAME_PATH).orEmpty()
        val systemId = intent.getStringExtra(EXTRA_SYSTEM_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        val romFile = File(gamePath)
        if (!romFile.isFile || systemId.isBlank()) {
            Toast.makeText(this, "This game file is not available on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val romRoot = File(filesDir, "roms").canonicalFile
        val relativeRom = runCatching { romFile.canonicalFile.relativeTo(romRoot).invariantSeparatorsPath }.getOrNull()
        if (relativeRom == null || relativeRom.startsWith("..")) {
            Toast.makeText(this, "The imported game is outside Retro Player storage.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val appOrigin = "https://" + WebViewAssetLoader.DEFAULT_DOMAIN
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/roms/", SafeFilePathHandler(romRoot))
            .addPathHandler("/bios/", SafeFilePathHandler(File(filesDir, "bios")))
            .build()

        val web = WebView(this)
        webView = web
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            safeBrowsingEnabled = true
            supportZoom = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val trusted = uri.scheme == "https" && (
                    uri.host == WebViewAssetLoader.DEFAULT_DOMAIN ||
                        uri.host == "cdn.emulatorjs.org"
                    )
                return !trusted
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                handler.cancel()
            }
        }

        val root = FrameLayout(this).apply {
            addView(
                web,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
        setContentView(root)

        val romUrl = appOrigin + "/roms/" +
            relativeRom.split("/").joinToString("/") { Uri.encode(it) }
        val biosPath = intent.getStringExtra(EXTRA_BIOS_PATH).orEmpty()
        val biosRoot = File(filesDir, "bios").canonicalFile
        val biosUrl = if (biosPath.isNotBlank()) {
            val relativeBios = runCatching {
                File(biosPath).canonicalFile.relativeTo(biosRoot).invariantSeparatorsPath
            }.getOrNull()
            if (relativeBios != null && !relativeBios.startsWith("..") && File(biosPath).isFile) {
                appOrigin + "/bios/" +
                    relativeBios.split("/").joinToString("/") { Uri.encode(it) }
            } else ""
        } else ""

        val preferences = SettingsRepository(this)
        val playerUri = Uri.parse(appOrigin + "/assets/emulator/player.html")
            .buildUpon()
            .appendQueryParameter("game", romUrl)
            .appendQueryParameter("system", systemId)
            .appendQueryParameter("title", title)
            .appendQueryParameter("bios", biosUrl)
            .appendQueryParameter("volume", preferences.volume.toString())
            .appendQueryParameter("haptics", preferences.haptics.toString())
            .build()
        web.loadUrl(playerUri.toString())
    }

    override fun onPause() {
        recordElapsedPlaytime()
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        sessionStartedAt = SystemClock.elapsedRealtime()
        webView?.onResume()
    }

    private fun recordElapsedPlaytime() {
        val started = sessionStartedAt
        sessionStartedAt = 0L
        if (started > 0L && gameId.isNotBlank()) {
            val seconds = ((SystemClock.elapsedRealtime() - started) / 1000L).coerceAtLeast(0L)
            GameDatabase(this).addPlaytime(gameId, seconds)
        }
    }

    override fun onDestroy() {
        recordElapsedPlaytime()
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private class SafeFilePathHandler(private val root: File) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val base = root.canonicalFile
            val file = File(base, path).canonicalFile
            if (!file.path.startsWith(base.path + File.separator) || !file.isFile) return null
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            return WebResourceResponse(mime, null, FileInputStream(file))
        }
    }

    companion object {
        const val EXTRA_GAME_PATH = "game_path"
        const val EXTRA_SYSTEM_ID = "system_id"
        const val EXTRA_TITLE = "game_title"
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_BIOS_PATH = "bios_path"
    }
}
