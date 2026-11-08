package br.tv.fire

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Abre o player OFICIAL do Globoplay. O login é feito pelo próprio usuário na tela do site
 * (a sessão fica salva em cookies) e o player renova os tokens sozinho.
 */
class GloboActivity : Activity() {
    private val url = "https://globoplay.globo.com/tv-globo/ao-vivo/6120663/"
    private lateinit var web: WebView
    private var custom: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.keepScreenOn = true
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true); setAcceptThirdPartyCookies(web, true)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                custom = view; customCb = callback; setContentView(view)
            }
            override fun onHideCustomView() { leaveFullscreen() }
        }
        // Se a página cair (rede, erro), tenta abrir de novo sozinha
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) handler.postDelayed({ web.loadUrl(url) }, 8000)
            }
        }
        web.loadUrl(url)
    }

    private fun leaveFullscreen() {
        if (custom != null) {
            setContentView(web); custom = null; customCb?.onCustomViewHidden(); customCb = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (custom != null) leaveFullscreen() else finish() }

    override fun onPause() { super.onPause(); web.onPause(); CookieManager.getInstance().flush() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
