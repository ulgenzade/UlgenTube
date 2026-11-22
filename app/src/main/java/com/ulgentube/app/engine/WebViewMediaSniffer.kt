package com.ulgentube.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.ImageItem
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.MediaFormatOption
import com.ulgentube.app.domain.model.PlatformType
import com.ulgentube.app.util.NetworkSecurityGuard
import com.ulgentube.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Snaptube tarzı Görünmez (Headless) WebView Medya Yakalayıcı & Ağ Dinleyici:
 * - Standart HTTP/GraphQL istekleri bot korumasına takıldığında gerçek Android Chromium WebView
 *   motorunu ve aktif WebView çerezlerini kullanarak sayfayı arka planda yükler.
 * - `shouldInterceptRequest` ile CDN üzerinden geçen doğrudan `.mp4` video akışlarını yakalar.
 * - `evaluateJavascript` ile DOM'daki `<video>`, `<img>` ve gömülü `video_url` / `display_url`
 *   verilerini çeker.
 */
object WebViewMediaSniffer {

    private const val SNIFF_TIMEOUT_MS = 11_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractInstagramWithWebView(
        context: Context,
        originalUrl: String,
        shortcode: String?
    ): ExtractedMedia? {
        val targetUrl = if (!shortcode.isNullOrBlank()) {
            "https://www.instagram.com/p/$shortcode/embed/captioned/"
        } else {
            originalUrl
        }
        if (!NetworkSecurityGuard.isSafePublicUrl(targetUrl)) return null

        return withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                var webView: WebView? = null
                try {
                    suspendCancellableCoroutine { cont ->
                        val interceptedVideos = Collections.synchronizedSet(linkedSetOf<String>())
                        val interceptedImages = Collections.synchronizedSet(linkedSetOf<String>())
                        var completed = false

                        val wv = WebView(context.applicationContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        }
                        webView = wv
                        CookieManager.getInstance().setAcceptCookie(true)

                        fun finishWith(result: ExtractedMedia?) {
                            if (!completed && cont.isActive) {
                                completed = true
                                runCatching {
                                    wv.stopLoading()
                                    wv.destroy()
                                }
                                cont.resume(result)
                            }
                        }

                        fun probeDomAndFinish(view: WebView) {
                            val js = """
                                (function() {
                                    var videos = [];
                                    var images = [];
                                    document.querySelectorAll('video, video source').forEach(function(v) {
                                        if (v.src && v.src.startsWith('http')) videos.push(v.src);
                                    });
                                    document.querySelectorAll('img.EmbeddedMediaImage, article img, main img').forEach(function(img) {
                                        if (img.src && img.src.startsWith('http') && !img.src.includes('s150x150') && !img.src.includes('profile_pic')) {
                                            images.push(img.src);
                                        }
                                    });
                                    var html = document.documentElement.innerHTML.replace(/\\\//g, '/').replace(/\\u0026/g, '&');
                                    var vMatches = html.match(/"video_url"\s*:\s*"(https?:[^"]+)"/g) || [];
                                    vMatches.forEach(function(m) {
                                        var u = m.split('"')[3];
                                        if (u) videos.push(u);
                                    });
                                    var iMatches = html.match(/"display_url"\s*:\s*"(https?:[^"]+)"/g) || [];
                                    iMatches.forEach(function(m) {
                                        var u = m.split('"')[3];
                                        if (u && !u.includes('s150x150')) images.push(u);
                                    });
                                    var titleEl = document.querySelector('.Caption') || document.querySelector('meta[property="og:title"]');
                                    var title = titleEl ? (titleEl.innerText || titleEl.content || '') : document.title;
                                    return JSON.stringify({title: title, videos: videos, images: images});
                                })();
                            """.trimIndent()

                            view.evaluateJavascript(js) { rawResult ->
                                val parsed = runCatching {
                                    val cleanJson = if (rawResult != null && rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                                        org.json.JSONTokener(rawResult).nextValue().toString()
                                    } else {
                                        rawResult.orEmpty()
                                    }
                                    JSONObject(cleanJson)
                                }.getOrNull()

                                val domVideos = mutableListOf<String>()
                                val domImages = mutableListOf<String>()
                                val title = parsed?.optString("title")?.takeIf { it.isNotBlank() }?.take(80)
                                    ?: "Instagram Gönderisi"

                                parsed?.optJSONArray("videos")?.let { arr ->
                                    for (i in 0 until arr.length()) {
                                        arr.optString(i)?.takeIf { NetworkSecurityGuard.isSafePublicUrl(it) }?.let { domVideos += it }
                                    }
                                }
                                parsed?.optJSONArray("images")?.let { arr ->
                                    for (i in 0 until arr.length()) {
                                        arr.optString(i)?.takeIf { NetworkSecurityGuard.isSafePublicUrl(it) }?.let { domImages += it }
                                    }
                                }

                                val allVideos = (domVideos + interceptedVideos).distinct()
                                    .filter { NetworkSecurityGuard.isSafePublicUrl(it) }
                                val allImages = (domImages + interceptedImages).distinct()
                                    .filter { NetworkSecurityGuard.isSafePublicUrl(it) }

                                if (allVideos.isEmpty() && allImages.isEmpty()) {
                                    finishWith(null)
                                    return@evaluateJavascript
                                }

                                val videoOptions = allVideos.mapIndexed { idx, vUrl ->
                                    MediaFormatOption(
                                        id = "ig_wv_video_${idx + 1}",
                                        label = "1080p Instagram Video",
                                        sublabel = "Instagram Doğrudan",
                                        category = MediaCategory.VIDEO,
                                        extension = "mp4",
                                        resolutionHeight = 1080,
                                        directUrl = vUrl
                                    )
                                }
                                val finalVideos = if (videoOptions.size == 1) {
                                    videoOptions + YtDlpEngine.buildDownscaleOptions(videoOptions.first(), 1080)
                                } else {
                                    videoOptions
                                }

                                val imageItems = allImages.mapIndexed { idx, iUrl ->
                                    ImageItem(
                                        id = "ig_wv_img_${idx + 1}",
                                        url = iUrl,
                                        label = if (idx == 0) "Orijinal Fotoğraf (HD)" else "Fotoğraf ${idx + 1}",
                                        extension = UrlSanitizer.guessImageExtension(iUrl)
                                    )
                                }

                                val audioOptions = if (finalVideos.isNotEmpty()) {
                                    YtDlpEngine.buildDefaultAudioFormats(
                                        formats = emptyList(),
                                        durationSeconds = null,
                                        directSourceUrl = finalVideos.first().directUrl
                                    )
                                } else {
                                    emptyList()
                                }

                                finishWith(
                                    ExtractedMedia(
                                        originalUrl = originalUrl,
                                        title = title,
                                        author = null,
                                        durationSeconds = null,
                                        thumbnailUrl = imageItems.firstOrNull()?.url,
                                        platform = PlatformType.INSTAGRAM,
                                        videoFormats = finalVideos,
                                        audioFormats = audioOptions,
                                        imageItems = imageItems
                                    )
                                )
                            }
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (NetworkSecurityGuard.isSafePublicUrl(reqUrl)) {
                                    val lower = reqUrl.lowercase()
                                    if ((lower.contains("cdninstagram.com") || lower.contains("fbcdn.net")) &&
                                        (lower.contains(".mp4") || lower.contains("bytestart"))
                                    ) {
                                        val cleanMp4 = reqUrl.replace(Regex("&bytestart=\\d+(&byteend=\\d+)?"), "")
                                        interceptedVideos.add(cleanMp4)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView, loadedUrl: String?) {
                                super.onPageFinished(view, loadedUrl)
                                view.postDelayed({
                                    if (!completed) probeDomAndFinish(view)
                                }, 900L)
                            }
                        }

                        cont.invokeOnCancellation {
                            runCatching {
                                wv.stopLoading()
                                wv.destroy()
                            }
                        }

                        wv.loadUrl(targetUrl)
                    }
                } finally {
                    runCatching {
                        webView?.stopLoading()
                        webView?.destroy()
                    }
                }
            }
        }
    }
}
