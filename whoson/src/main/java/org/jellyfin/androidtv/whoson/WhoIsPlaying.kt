package org.jellyfin.androidtv.whoson

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Triggers the cast recognition flow in Home Assistant and opens the result page.
 *
 * All of the fork specific behaviour lives here rather than in the app module, so the
 * only thing the player code needs is a one line call into [start].
 */
object WhoIsPlaying {
	private const val WEBHOOK_URL =
		"http://192.168.0.147:8123/api/webhook/whoson_rPF1MDfvg3GPaTIv"
	private const val PAGE_URL =
		"http://192.168.0.147:8123/local/whoson-848ea8579655/index.html"

	/**
	 * The frame is captured by an HDMI grabber on the TV output, not by Jellyfin, so the
	 * flow photographs whatever is on screen. Opening the result page immediately would
	 * hand the recognition a screenshot of that page instead of the film.
	 *
	 * Capturing is the first thing the flow does and takes around 1.2s, so this is the
	 * safety margin. If the thumbnail on the result page ever shows this page or a black
	 * screen, raise it.
	 */
	private const val SCREENSHOT_GRACE_MS = 2000L

	private const val TIMEOUT_MS = 3000

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	fun start(context: Context) {
		// Taken before firing: the page waits for a result NEWER than this, which is what
		// stops it from showing the previous recognition.
		val requestedAt = System.currentTimeMillis()

		Toast.makeText(context, R.string.whoson_checking, Toast.LENGTH_SHORT).show()

		scope.launch {
			if (!fireWebhook()) {
				withContext(Dispatchers.Main) {
					Toast.makeText(context, R.string.whoson_failed, Toast.LENGTH_LONG).show()
				}
				return@launch
			}

			delay(SCREENSHOT_GRACE_MS)

			withContext(Dispatchers.Main) {
				context.startActivity(
					Intent(context, CastWebViewActivity::class.java)
						.putExtra(CastWebViewActivity.EXTRA_URL, "$PAGE_URL?tv=1&after=$requestedAt")
				)
			}
		}
	}

	private fun fireWebhook(): Boolean = runCatching {
		val connection = URL(WEBHOOK_URL).openConnection() as HttpURLConnection

		try {
			connection.requestMethod = "GET"
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.responseCode in 200..299
		} finally {
			connection.disconnect()
		}
	}.onFailure { error ->
		Timber.w(error, "whoson: webhook failed")
	}.getOrDefault(false)
}
