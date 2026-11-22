package com.ulgentube.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ulgentube.app.data.local.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class PoTokenPair(
    val poToken: String,
    val visitorData: String,
    val generatedAtMs: Long
)

/**
 * YouTube BotGuard / PoToken ve `visitorData` sağlayıcısı.
 * Önbellekte geçerli (12 saat TTL) token varsa doğrudan onu döndürür; yoksa gizli headless
 * Android WebView üzerinden `https://m.youtube.com` yükleyip `ytcfg` / cookie / BotGuard
 * verilerinden token çifti çıkarmayı dener. Hata durumunda asla çökmez, `null` döner.
 */
class YouTubePoTokenProvider(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val fetchMutex = Mutex()

    @Volatile
    private var inMemoryCache: PoTokenPair? = null

    suspend fun getOrFetchPoToken(forceRefresh: Boolean = false): PoTokenPair? {
        return runCatching {
            val settings = settingsRepository.settingsFlow.first()
            if (!settings.poTokenEnabled) {
                return null
            }

            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = inMemoryCache
                if (cached != null && (now - cached.generatedAtMs) < TOKEN_TTL_MS) {
                    return cached
                }

                val savedPoToken = settings.cachedPoToken?.trim().orEmpty()
                val savedVisitorData = settings.cachedVisitorData?.trim().orEmpty()
                if (savedPoToken.isNotBlank() || savedVisitorData.isNotBlank()) {
                    val restored = PoTokenPair(
                        poToken = savedPoToken,
                        visitorData = savedVisitorData,
                        generatedAtMs = now
                    )
                    inMemoryCache = restored
                    return restored
                }
            }

            fetchMutex.withLock {
                val currentTime = System.currentTimeMillis()
                if (!forceRefresh) {
                    val doubleChecked = inMemoryCache
                    if (doubleChecked != null && (currentTime - doubleChecked.generatedAtMs) < TOKEN_TTL_MS) {
                        return@withLock doubleChecked
                    }
                }

                val fetched = fetchViaHeadlessWebView() ?: return@withLock null
                inMemoryCache = fetched
                runCatching {
                    settingsRepository.setCachedPoToken(
                        poToken = fetched.poToken.takeIf { it.isNotBlank() },
                        visitorData = fetched.visitorData.takeIf { it.isNotBlank() }
                    )
                }
                fetched
            }
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchViaHeadlessWebView(): PoTokenPair? {
        return withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                var webView: WebView? = null
                try {
                    val pageFinished = CompletableDeferred<Unit>()
                    val potCaptured = CompletableDeferred<String>()
                    val wv = WebView(context.applicationContext)
                    webView = wv

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = MOBILE_WEB_USER_AGENT
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val reqUrl = request?.url
                            if (reqUrl != null && reqUrl.host?.contains("googlevideo.com") == true) {
                                val pot = reqUrl.getQueryParameter("pot")?.trim()
                                if (!pot.isNullOrBlank() && !potCaptured.isCompleted) {
                                    potCaptured.complete(pot)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!pageFinished.isCompleted) {
                                pageFinished.complete(Unit)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && !pageFinished.isCompleted) {
                                pageFinished.complete(Unit)
                            }
                        }
                    }

                    wv.loadUrl(YOUTUBE_MOBILE_WATCH_URL)
                    withTimeoutOrNull(PAGE_LOAD_WAIT_MS) {
                        pageFinished.await()
                    }
                    // Sessiz oynatmayı tetikle ki YouTube BotGuard gerçek GVS pot= isteğini atsın
                    wv.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play().catch(function(){});}})();",
                        null
                    )
                    val interceptedPot = withTimeoutOrNull(POT_WAIT_MS) {
                        potCaptured.await()
                    }
                    if (interceptedPot.isNullOrBlank()) {
                        delay(JS_SETTLE_DELAY_MS)
                    }

                    val jsResult = evaluateJsSuspend(wv, EXTRACT_TOKEN_JS)
                    val parsed = parseWebViewExtractionResult(jsResult)
                    if (!interceptedPot.isNullOrBlank()) {
                        PoTokenPair(
                            poToken = interceptedPot,
                            visitorData = parsed?.visitorData.orEmpty(),
                            generatedAtMs = System.currentTimeMillis()
                        )
                    } else {
                        parsed
                    }
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.clearHistory()
                        webView?.removeAllViews()
                        webView?.destroy()
                    }
                }
            }
        }
    }

    /**
     * Misafir oturumunda bile YouTube'un kendi mobil oynatıcısından (`m.youtube.com`)
     * aynı oturuma ait `c=MWEB` dublaj ses URL'sini (`ytInitialPlayerResponse`, `itag=140`)
     * ve aynı `MWEB` BotGuard `pot=` tokenını (SABR/videoplayback ağ isteğinden) eşleştirip döndürür.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureSignedAudioUrlViaWebView(
        videoId: String,
        langCode: String = "tr"
    ): String? {
        val safeLang = langCode.trim().substringBefore('-').lowercase()
            .takeIf { it.matches(Regex("""^[a-z]{2,3}$""")) } ?: "tr"

        return withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                var webView: WebView? = null
                try {
                    val potCaptured = CompletableDeferred<String>()
                    val pageFinished = CompletableDeferred<Unit>()
                    val wv = WebView(context.applicationContext)
                    webView = wv

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = MOBILE_WEB_USER_AGENT
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val reqUrl = request?.url
                            if (reqUrl != null && reqUrl.host?.contains("googlevideo.com") == true) {
                                // SABR (ump=1) dahil her googlevideo.com isteğinden gerçek MWEB pot= tokenını yakala
                                val pot = reqUrl.getQueryParameter("pot")?.trim()
                                if (!pot.isNullOrBlank() && !potCaptured.isCompleted) {
                                    potCaptured.complete(pot)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!pageFinished.isCompleted) {
                                pageFinished.complete(Unit)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && !pageFinished.isCompleted) {
                                pageFinished.complete(Unit)
                            }
                        }
                    }

                    val headers = mapOf("Accept-Language" to "$safeLang,en;q=0.5")
                    wv.loadUrl("https://m.youtube.com/watch?v=$videoId&hl=$safeLang", headers)
                    withTimeoutOrNull(PAGE_LOAD_WAIT_MS) {
                        pageFinished.await()
                    }
                    // Oynatıcıyı sessiz başlat ki BotGuard gerçek MWEB pot= isteğini atsın
                    wv.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play().catch(function(){});}})();",
                        null
                    )
                    val capturedPot = withTimeoutOrNull(6_000L) {
                        potCaptured.await()
                    }

                    // Aynı MWEB sayfasındaki ytInitialPlayerResponse'tan hedef dilin itag=140 MWEB URL'sini çek
                    val extractAudioJs = """
                        (function() {
                          try {
                            var resp = window.ytInitialPlayerResponse;
                            if (!resp || !resp.streamingData || !resp.streamingData.adaptiveFormats) return '';
                            var af = resp.streamingData.adaptiveFormats;
                            var target = '$safeLang';
                            var best140 = null;
                            var bestM4a = null;
                            for (var i = 0; i < af.length; i++) {
                              var f = af[i];
                              if (!f || !f.url || !f.mimeType || f.mimeType.indexOf('audio/mp4') !== 0) continue;
                              var tid = (f.audioTrack && f.audioTrack.id) ? String(f.audioTrack.id).toLowerCase() : '';
                              if (tid.indexOf(target) === 0) {
                                if (f.itag === 140) { best140 = f.url; break; }
                                if (!bestM4a) bestM4a = f.url;
                              }
                            }
                            return best140 || bestM4a || '';
                          } catch (e) { return ''; }
                        })();
                    """.trimIndent()

                    val rawJs = evaluateJsSuspend(wv, extractAudioJs)
                    val mwebAudioUrl = rawJs?.let { raw ->
                        runCatching { JSONObject("{\"v\":$raw}").optString("v") }
                            .getOrDefault(raw.trim('"'))
                    }?.trim()?.takeIf { it.startsWith("http") }

                    if (mwebAudioUrl.isNullOrBlank()) {
                        return@withContext null
                    }
                    if (!capturedPot.isNullOrBlank() && !mwebAudioUrl.contains("pot=")) {
                        val sep = if (mwebAudioUrl.contains('?')) '&' else '?'
                        "$mwebAudioUrl${sep}pot=${java.net.URLEncoder.encode(capturedPot, "UTF-8")}"
                    } else {
                        mwebAudioUrl
                    }
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching {
                        webView?.stopLoading()
                        webView?.clearHistory()
                        webView?.removeAllViews()
                        webView?.destroy()
                    }
                }
            }
        }
    }

    private fun stripRangeParams(rawUrl: String): String {
        val uri = android.net.Uri.parse(rawUrl)
        val builder = uri.buildUpon().clearQuery()
        val dropKeys = setOf("range", "rn", "rbuf", "ump", "srfvp")
        uri.queryParameterNames.forEach { key ->
            if (key.lowercase() !in dropKeys) {
                uri.getQueryParameters(key).forEach { value ->
                    builder.appendQueryParameter(key, value)
                }
            }
        }
        return builder.build().toString()
    }

    private suspend fun evaluateJsSuspend(webView: WebView, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        webView.evaluateJavascript(script) { value ->
            if (!deferred.isCompleted) {
                deferred.complete(value)
            }
        }
        return withTimeoutOrNull(JS_EVAL_TIMEOUT_MS) {
            deferred.await()
        }
    }

    private fun parseWebViewExtractionResult(rawJsValue: String?): PoTokenPair? {
        if (rawJsValue.isNullOrBlank() || rawJsValue == "null") return null
        val unquoted = runCatching {
            // evaluateJavascript JSON string'i çift tırnak ve escape ile döndürür
            JSONObject("{\"v\":$rawJsValue}").optString("v")
        }.getOrDefault(rawJsValue.trim('"'))

        if (unquoted.isBlank()) return null

        return runCatching {
            val obj = JSONObject(unquoted)
            val poToken = obj.optString("poToken").trim()
            var visitorData = obj.optString("visitorData").trim()
            if (visitorData.isBlank()) {
                val htmlSnippet = obj.optString("html")
                if (htmlSnippet.isNotBlank()) {
                    visitorData = parseVisitorDataFromHtml(htmlSnippet).orEmpty()
                }
            }
            if (poToken.isBlank() && visitorData.isBlank()) {
                return null
            }
            PoTokenPair(
                poToken = poToken,
                visitorData = visitorData,
                generatedAtMs = System.currentTimeMillis()
            )
        }.getOrNull()
    }

    companion object {
        const val TOKEN_TTL_MS = 12L * 60L * 60L * 1000L // 12 saat
        private const val WEBVIEW_TIMEOUT_MS = 15_000L
        private const val PAGE_LOAD_WAIT_MS = 7_500L
        private const val POT_WAIT_MS = 4_500L
        private const val JS_SETTLE_DELAY_MS = 1_200L
        private const val JS_EVAL_TIMEOUT_MS = 2_500L

        private const val YOUTUBE_MOBILE_WATCH_URL = "https://m.youtube.com/watch?v=jNQXAC9IVRw"
        const val MOBILE_WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val VISITOR_DATA_JSON_REGEX =
            Regex("""\\?"(?:visitorData|VISITOR_DATA)\\?"\s*:\s*\\?"([A-Za-z0-9%_\-+/=]{8,128})\\?"""")
        private val VISITOR_COOKIE_REGEX =
            Regex("""VISITOR_INFO1_LIVE=([A-Za-z0-9%_\-+/=]{6,128})""")

        private val EXTRACT_TOKEN_JS = """
            (function() {
              try {
                var cfg = window.ytcfg || null;
                var visitorData = '';
                var poToken = '';
                if (cfg && typeof cfg.get === 'function') {
                  visitorData = cfg.get('VISITOR_DATA') || '';
                  var ctx = cfg.get('INNERTUBE_CONTEXT');
                  if (!visitorData && ctx && ctx.client && ctx.client.visitorData) {
                    visitorData = ctx.client.visitorData;
                  }
                  poToken = cfg.get('PO_TOKEN') || cfg.get('BOTGUARD_PO_TOKEN') || '';
                  if (!poToken && cfg.data_ && cfg.data_.SERVICE_INTEGRITY_DIMENSIONS && cfg.data_.SERVICE_INTEGRITY_DIMENSIONS.poToken) {
                    poToken = cfg.data_.SERVICE_INTEGRITY_DIMENSIONS.poToken;
                  }
                }
                if (!visitorData && document.cookie) {
                  var m = document.cookie.match(/VISITOR_INFO1_LIVE=([^;]+)/);
                  if (m && m[1]) visitorData = decodeURIComponent(m[1]);
                }
                var htmlSample = '';
                if (!visitorData && document.documentElement) {
                  htmlSample = document.documentElement.innerHTML.slice(0, 60000);
                }
                return JSON.stringify({
                  poToken: String(poToken || ''),
                  visitorData: String(visitorData || ''),
                  html: htmlSample
                });
              } catch (e) {
                return JSON.stringify({ poToken: '', visitorData: '', html: '' });
              }
            })();
        """.trimIndent()

        /**
         * YouTube HTML / ytcfg / cookie metninden `visitorData` veya `VISITOR_INFO1_LIVE`
         * değerini saf/statik olarak ayrıştırır.
         */
        fun parseVisitorDataFromHtml(html: String): String? {
            if (html.isBlank()) return null

            val jsonMatch = VISITOR_DATA_JSON_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!jsonMatch.isNullOrBlank()) {
                return jsonMatch
            }

            val cookieMatch = VISITOR_COOKIE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!cookieMatch.isNullOrBlank()) {
                return cookieMatch
            }

            return null
        }
    }
}
