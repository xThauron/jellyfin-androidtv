package org.jellyfin.androidtv.updater

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ApkDownloader {
	private const val USER_AGENT = "jellyfin-androidtv-updater"
	private const val TIMEOUT_MS = 30_000
	private const val BUFFER_SIZE = 64 * 1024

	/**
	 * Download [url] into [target], overwriting whatever was there before.
	 *
	 * [onProgress] is called from the calling thread with the bytes written so far and the
	 * total size, which is -1 when the server does not report a content length.
	 */
	fun download(url: String, target: File, onProgress: (read: Long, total: Long) -> Unit) {
		val connection = URL(url).openConnection() as HttpURLConnection

		try {
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("User-Agent", USER_AGENT)
			connection.instanceFollowRedirects = true

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				throw IOException("Download failed with HTTP ${connection.responseCode}")
			}

			// contentLengthLong needs API 24, and an APK always fits in an Int anyway
			val total = connection.contentLength.toLong()

			connection.inputStream.use { input ->
				target.outputStream().use { output ->
					val buffer = ByteArray(BUFFER_SIZE)
					var read = 0L

					while (true) {
						val count = input.read(buffer)
						if (count == -1) break

						output.write(buffer, 0, count)
						read += count
						onProgress(read, total)
					}

					output.flush()
				}
			}
		} finally {
			connection.disconnect()
		}
	}
}
