package org.jellyfin.androidtv.updater

/**
 * Derives version codes from release tags the same way the app build does.
 *
 * This mirrors getVersionCode() in buildSrc/src/main/kotlin/VersionUtils.kt. The two
 * must stay in sync: if the formula there changes, comparing a release tag against the
 * installed version code silently starts producing wrong answers.
 */
object VersionCodes {
	private const val DEFAULT_PRE_RELEASE = 99

	/**
	 * Convert a version name ("0.19.10-fork.3", with or without a "v" prefix) into the
	 * version code the build would have produced, or null when it cannot be parsed.
	 */
	fun fromVersionName(versionName: String): Int? {
		val name = versionName.removePrefix("v")

		val separator = name.indexOf('-')
		val core = if (separator == -1) name else name.substring(0, separator)
		val preRelease = if (separator == -1) null else name.substring(separator + 1)

		val parts = core.split('.').mapNotNull(String::toIntOrNull)
		if (parts.size < 3) return null
		val (major, minor, patch) = parts

		// Only the number of the pre-release part matters ("fork.3" -> 3)
		val build = preRelease?.substringAfter('.')?.toIntOrNull() ?: DEFAULT_PRE_RELEASE

		return major * 1_000_000 + minor * 10_000 + patch * 100 + build
	}
}
