package com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation

import android.annotation.SuppressLint
import android.net.Uri
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
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
    }

    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var bindingContent: ContentWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.web_title, null)
        bindingContent = ContentWebviewBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
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


