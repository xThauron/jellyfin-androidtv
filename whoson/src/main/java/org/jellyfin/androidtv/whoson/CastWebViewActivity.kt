package org.jellyfin.androidtv.whoson

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** Fullscreen WebView for the cast page, which renders itself in JavaScript. */
class CastWebViewActivity : Activity() {
	private var webView: WebView? = null

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val view = WebView(this).apply {
			setBackgroundColor(Color.parseColor(BACKGROUND_COLOR))
			settings.javaScriptEnabled = true
			settings.domStorageEnabled = true
			// Home Assistant serves /local/ with a 31 day cache, no need for another
			// layer of it on top
			settings.cacheMode = WebSettings.LOAD_NO_CACHE
			// Keep navigation inside the app so BACK returns to the player
			webViewClient = WebViewClient()
			isFocusable = true
			isFocusableInTouchMode = true
		}

		webView = view
		setContentView(view)

		intent.getStringExtra(EXTRA_URL)?.let(view::loadUrl)
		view.requestFocus()
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		val view = webView

		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (view != null && view.canGoBack()) view.goBack() else finish()
			return true
		}

		return super.onKeyDown(keyCode, event)
	}

	override fun onDestroy() {
		webView?.destroy()
		webView = null
		super.onDestroy()
	}

	companion object {
		const val EXTRA_URL = "url"

		private const val BACKGROUND_COLOR = "#0e1116"
	}
}
