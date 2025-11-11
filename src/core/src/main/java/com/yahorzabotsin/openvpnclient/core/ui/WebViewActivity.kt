package com.yahorzabotsin.openvpnclient.core.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentWebviewBinding

class WebViewActivity : BaseTemplateActivity(R.string.web_title) {
    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var bindingContent: ContentWebviewBinding

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        bindingContent = ContentWebviewBinding.inflate(inflater, container, true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()

        val host = try { Uri.parse(url).host } catch (_: Exception) { null }
        if (!host.isNullOrBlank()) {
            templateBinding.toolbarTitle.text = host
        }

        val wv = bindingContent.webview
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            val isTv = (getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION)
            if (isTv) {
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
            }
        }
        wv.webViewClient = object : WebViewClient() {
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
        wv.loadUrl(url)
    }
}
