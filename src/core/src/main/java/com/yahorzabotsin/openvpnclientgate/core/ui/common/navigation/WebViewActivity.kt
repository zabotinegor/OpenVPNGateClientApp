package com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation

import android.annotation.SuppressLint
import android.net.Uri
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentWebviewBinding

class WebViewActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_HTML = "extra_html"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var bindingContent: ContentWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.web_title, null)
        bindingContent = ContentWebviewBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) {
            templateBinding.toolbarTitle.text = title
        }

        val rawHtml = intent.getStringExtra(EXTRA_HTML)
        if (!rawHtml.isNullOrBlank()) {
            val wv = bindingContent.webview
            configureWebView(wv, enableJavaScript = false)
            wv.webViewClient = createSafeWebViewClient()
            wv.loadDataWithBaseURL(null, rawHtml, "text/html", "utf-8", null)
            return
        }

        val rawUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return finish()
        val scheme = uri.scheme?.lowercase() ?: return finish()
        if (scheme != "http" && scheme != "https") return finish()
        val url = uri.toString()

        val host = uri.host
        if (!host.isNullOrBlank()) {
            templateBinding.toolbarTitle.text = host
        }

        val wv = bindingContent.webview
        configureWebView(wv, enableJavaScript = true)
        wv.webViewClient = createSafeWebViewClient()
        wv.loadUrl(url)
    }

    private fun configureWebView(webView: WebView, enableJavaScript: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = enableJavaScript
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            if (enableJavaScript) {
                blockNetworkImage = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                val isTv = getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
                if (isTv) {
                    userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
                }
            }
        }
    }

    private fun createSafeWebViewClient(): WebViewClient =
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                    } catch (_: android.content.ActivityNotFoundException) { }
                    true
                }
            }
        }

    override fun onDestroy() {
        if (::bindingContent.isInitialized) {
            bindingContent.webview.apply {
                (parent as? ViewGroup)?.removeView(this)
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
        }
        super.onDestroy()
    }
}


