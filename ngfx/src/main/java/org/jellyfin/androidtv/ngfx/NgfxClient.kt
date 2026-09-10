package org.jellyfin.androidtv.ngfx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the NGFX user API.
 *
 * The REST surface is proxied by the Jellyfin plugin, so it is same-origin with the
 * Jellyfin server the app is already talking to and needs no extra host or port.
 * Authentication is the user's existing Jellyfin access token; there is no NGFX login.
 *
 * The status WebSocket cannot be proxied through Jellyfin, so this client polls
 * instead - which the API documentation states is a complete substitute.
 */
class NgfxClient(
	private val credentials: () -> NgfxCredentials?,
) {
	suspend fun search(query: String): List<NgfxSearchResult> = withContext(Dispatchers.IO) {
		val encoded = URLEncoder.encode(query, "UTF-8")
		val body = request("GET", "/search?query=$encoded&page=1")
		val results = JSONObject(body).optJSONArray("results") ?: JSONArray()

		(0 until results.length()).mapNotNull { index ->
			parseSearchResult(results.getJSONObject(index))
		}
	}

	suspend fun myRequests(): List<NgfxRequest> = withContext(Dispatchers.IO) {
		val body = request("GET", "/requests/mine")
		val array = JSONArray(body)

		(0 until array.length()).mapNotNull { index ->
			parseRequest(array.getJSONObject(index))
		}
	}

	/** Requesting a whole show tracks every episode and keeps picking up new ones as they air. */
	suspend fun request(result: NgfxSearchResult) = withContext(Dispatchers.IO) {
		val path = when (result.mediaType) {
			NgfxMediaType.MOVIE -> "/requests/movie/${result.tmdbId}"
			NgfxMediaType.TV -> "/requests/tv/${result.tmdbId}"
		}

		request("POST", path)
		Unit
	}

	/**
	 * Withdraw the caller's own request.
	 *
	 * Only allowed while the request is still PENDING - past that the download pipeline
	 * owns it and cancelling becomes an admin decision. The UI therefore offers this
	 * only on PENDING rows: a button that answers 409 is worse than no button.
	 */
	suspend fun cancel(requestId: Int) = withContext(Dispatchers.IO) {
		request("DELETE", "/requests/$requestId")
		Unit
	}

	private fun parseSearchResult(json: JSONObject): NgfxSearchResult? {
		val tmdbId = json.optInt("tmdbId", -1).takeIf { it >= 0 } ?: return null
		val mediaType = NgfxMediaType.from(json.optStringOrNull("mediaType")) ?: return null
		val title = json.optStringOrNull("title") ?: return null

		return NgfxSearchResult(
			tmdbId = tmdbId,
			mediaType = mediaType,
			title = title,
			year = json.optStringOrNull("year"),
			overview = json.optStringOrNull("overview"),
			posterUrl = json.optStringOrNull("posterUrl"),
			status = NgfxStatus.from(json.optStringOrNull("status")),
			canRequest = json.optBoolean("canRequest", false),
		)
	}

	private fun parseRequest(json: JSONObject): NgfxRequest? {
		val id = json.optInt("id", -1).takeIf { it >= 0 } ?: return null
		val downloads = json.optJSONArray("downloading") ?: JSONArray()

		return NgfxRequest(
			id = id,
			tmdbId = json.optInt("tmdbId", -1).takeIf { it >= 0 },
			mediaType = NgfxMediaType.from(json.optStringOrNull("mediaType")),
			title = json.optStringOrNull("title"),
			posterUrl = json.optStringOrNull("posterUrl"),
			seasonNumber = json.optIntOrNull("seasonNumber"),
			status = NgfxStatus.from(json.optStringOrNull("status")) ?: NgfxStatus.UNKNOWN,
			requestedAt = json.optStringOrNull("requestedAt"),
			progress = json.optDoubleOrNull("progress"),
			etaSeconds = json.optLongOrNull("etaSeconds"),
			speedBytesPerSecond = json.optLongOrNull("speedBytesPerSecond"),
			rejectionReason = json.optStringOrNull("rejectionReason"),
			jellyfinItemId = json.optStringOrNull("jellyfinItemId"),
			downloading = (0 until downloads.length()).map { index ->
				val download = downloads.getJSONObject(index)
				NgfxDownload(
					label = download.optStringOrNull("label"),
					progress = download.optDouble("progress", 0.0),
					speedBytesPerSecond = download.optLongOrNull("speedBytesPerSecond"),
					etaSeconds = download.optLongOrNull("etaSeconds"),
				)
			},
		)
	}

	private fun request(method: String, path: String): String {
		val credentials = credentials() ?: throw NgfxException(NgfxError.NOT_CONFIGURED)
		val url = credentials.serverAddress.trimEnd('/') + BASE_PATH + path

		val connection = try {
			URL(url).openConnection() as HttpURLConnection
		} catch (error: IOException) {
			throw NgfxException(NgfxError.NETWORK, cause = error)
		}

		try {
			connection.requestMethod = method
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("Accept", "application/json")

			// The same token twice, because two gates read it and they no longer read the
			// same header. Jellyfin authenticates at the proxy and, since the 12.x server
			// upgrade, takes the token only from Authorization; NGFX reads X-Emby-Token.
			// Sending only X-Emby-Token gets a 401 from Jellyfin that never reaches NGFX,
			// which looks exactly like a rejected token but is not one. Older Jellyfin
			// builds still accept the legacy header, so sending both works against either.
			connection.setRequestProperty("Authorization", "MediaBrowser Token=\"${credentials.accessToken}\"")
			connection.setRequestProperty("X-Emby-Token", credentials.accessToken)

			// A 302 on /Ngfx/** is NGFX redirecting to its own Vaadin login page. Following
			// it would hand us an HTML page to parse as JSON, so let it surface as a status
			// code instead - the plugin deliberately does not auto-redirect either.
			connection.instanceFollowRedirects = false

			// Creating a request takes no body; without this some servers wait for one
			if (method == "POST") connection.setRequestProperty("Content-Length", "0")

			val code = connection.responseCode
			if (code !in 200..299) throw NgfxException(errorFor(code), "HTTP $code for $path")

			// Creation answers 201 and withdrawal 204, both without a body
			if (method != "GET") return ""

			return connection.inputStream.bufferedReader().readText()
		} catch (error: IOException) {
			Timber.w(error, "ngfx: %s %s failed", method, path)
			throw NgfxException(NgfxError.NETWORK, cause = error)
		} finally {
			connection.disconnect()
		}
	}

	private fun errorFor(code: Int) = when (code) {
		// On /Ngfx/** the status says which layer rejected the call, and they need
		// different fixes: 401 is always Jellyfin's [Authorize], so the request never
		// left Jellyfin and the user's session is stale...
		HttpURLConnection.HTTP_UNAUTHORIZED -> NgfxError.UNAUTHORIZED
		// ...while 302 is NGFX redirecting to its own login, which means the call got
		// through Jellyfin and NGFX itself refused it - a server-side configuration
		// problem, not something re-authenticating on the TV will fix
		HttpURLConnection.HTTP_MOVED_TEMP -> NgfxError.REJECTED_BY_NGFX
		HttpURLConnection.HTTP_FORBIDDEN -> NgfxError.FORBIDDEN
		// 502: plugin could not reach NGFX. 503: plugin has no NGFX address configured.
		HttpURLConnection.HTTP_BAD_GATEWAY, HttpURLConnection.HTTP_UNAVAILABLE -> NgfxError.UNAVAILABLE
		// Withdrawing a request that already left PENDING
		HttpURLConnection.HTTP_CONFLICT -> NgfxError.CONFLICT
		else -> NgfxError.MALFORMED
	}

	private companion object {
		/** The plugin forwards /Ngfx/api/v1/<path> to <ngfx>/api/v1/<path> verbatim. */
		const val BASE_PATH = "/Ngfx/api/v1"
		const val TIMEOUT_MS = 10_000
	}
}

// org.json turns JSON null into the string "null" and missing keys into "", so the
// stock optString/optInt helpers cannot express "absent". These can.
private fun JSONObject.optStringOrNull(key: String): String? =
	if (isNull(key)) null else optString(key).takeUnless(String::isEmpty)

private fun JSONObject.optIntOrNull(key: String): Int? =
	if (isNull(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
	if (isNull(key)) null else optLong(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
	if (isNull(key)) null else optDouble(key).takeUnless(Double::isNaN)
