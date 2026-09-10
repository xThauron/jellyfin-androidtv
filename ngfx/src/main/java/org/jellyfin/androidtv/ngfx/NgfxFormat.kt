package org.jellyfin.androidtv.ngfx

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formatting for the three download numbers. Each returns null when there is nothing to
 * show, which the UI renders as a dash - a stalled, paused or seeding download reports
 * null speed and null ETA, and that is not the same as moving at zero.
 */
object NgfxFormat {
	fun progress(value: Double?): String? {
		if (value == null) return null
		return "${(value.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
	}

	fun speed(bytesPerSecond: Long?): String? {
		if (bytesPerSecond == null || bytesPerSecond <= 0) return null

		val megabytes = bytesPerSecond / 1_000_000.0
		return if (megabytes >= 1.0) {
			String.format(Locale.getDefault(), "%.1f MB/s", megabytes)
		} else {
			String.format(Locale.getDefault(), "%.0f kB/s", bytesPerSecond / 1_000.0)
		}
	}

	fun eta(seconds: Long?): String? {
		if (seconds == null || seconds <= 0) return null

		val hours = seconds / 3600
		val minutes = (seconds % 3600) / 60
		val remaining = seconds % 60

		return if (hours > 0) {
			String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remaining)
		} else {
			String.format(Locale.getDefault(), "%d:%02d", minutes, remaining)
		}
	}
}
