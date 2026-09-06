package org.jellyfin.androidtv.updater

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * An app APK published on a fork release.
 */
data class ReleaseInfo(
	val tagName: String,
	val versionName: String,
	val versionCode: Int,
	val assetName: String,
	val assetUrl: String,
	val assetSize: Long,
)

object GitHubReleases {
	private const val API_BASE = "https://api.github.com"
	private const val USER_AGENT = "jellyfin-androidtv-updater"
	private const val TIMEOUT_MS = 15_000
	private const val PAGE_SIZE = 20

	/**
	 * Find the newest published release that ships an APK for the app.
	 *
	 * Deliberately lists releases instead of using /releases/latest, which skips
	 * pre-releases and would hide any build published with --prerelease.
	 */
	fun findLatest(repository: String): ReleaseInfo? {
		val releases = JSONArray(get("$API_BASE/repos/$repository/releases?per_page=$PAGE_SIZE"))

		for (releaseIndex in 0 until releases.length()) {
			val release = releases.getJSONObject(releaseIndex)
			if (release.optBoolean("draft")) continue

			val tagName = release.optString("tag_name")
			if (tagName.isEmpty()) continue

			val versionName = tagName.removePrefix("v")
			val versionCode = VersionCodes.fromVersionName(versionName) ?: continue

			val assets = release.optJSONArray("assets") ?: continue
			for (assetIndex in 0 until assets.length()) {
				val asset = assets.getJSONObject(assetIndex)
				val assetName = asset.optString("name")
				if (!isAppApk(assetName)) continue

				val assetUrl = asset.optString("browser_download_url")
				if (assetUrl.isEmpty()) continue

				return ReleaseInfo(
					tagName = tagName,
					versionName = versionName,
					versionCode = versionCode,
					assetName = assetName,
					assetUrl = assetUrl,
					assetSize = asset.optLong("size"),
				)
			}
		}

		return null
	}

	/**
	 * Matches "jellyfin-androidtv-v0.19.10-fork.3-release.apk" while skipping the debug
	 * build and this updater, which is published as "jellyfin-updater-v...".
	 */
	private fun isAppApk(name: String) =
		name.startsWith("jellyfin-androidtv-v") && name.endsWith("-release.apk")

	private fun get(url: String): String {
		val connection = URL(url).openConnection() as HttpURLConnection

		return try {
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("Accept", "application/vnd.github+json")
			connection.setRequestProperty("User-Agent", USER_AGENT)

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				throw IOException("GitHub API returned HTTP ${connection.responseCode}")
			}

			connection.inputStream.bufferedReader().readText()
		} finally {
			connection.disconnect()
		}
	}
}
