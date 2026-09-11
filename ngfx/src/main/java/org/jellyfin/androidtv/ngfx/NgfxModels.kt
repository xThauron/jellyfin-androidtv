package org.jellyfin.androidtv.ngfx

// The resource id properties below are deliberately plain Int rather than @StringRes:
// this module depends only on coroutines and timber, and androidx.annotation is not on
// its classpath. Other modules get it for free by enabling viewBinding, which this one
// has no use for. Adding the annotation back means adding a dependency for it.

/** Server address and Jellyfin access token, supplied by the app module. */
data class NgfxCredentials(
	val serverAddress: String,
	val accessToken: String,
)

enum class NgfxMediaType(val wireName: String) {
	MOVIE("movie"),
	TV("tv");

	companion object {
		fun from(value: String?) = entries.firstOrNull { it.wireName == value }
	}
}

/**
 * Request lifecycle. Two of these mislead if taken at face value, so the labels are
 * chosen deliberately:
 *
 * - [AWAITING_JELLYFIN] is not "done". The file is in place but Jellyfin has not
 *   reported it in a library scan yet, so the title will not play.
 * - A request stays [DOWNLOADING] until every parallel download is imported.
 */
enum class NgfxStatus(val wireName: String?, val labelRes: Int) {
	PENDING("PENDING", R.string.ngfx_status_pending),
	APPROVED("APPROVED", R.string.ngfx_status_approved),
	REJECTED("REJECTED", R.string.ngfx_status_rejected),
	DOWNLOADING("DOWNLOADING", R.string.ngfx_status_downloading),
	IMPORTING("IMPORTING", R.string.ngfx_status_importing),
	AWAITING_JELLYFIN("AWAITING_JELLYFIN", R.string.ngfx_status_awaiting_jellyfin),
	PARTIALLY_AVAILABLE("PARTIALLY_AVAILABLE", R.string.ngfx_status_partially_available),
	AVAILABLE("AVAILABLE", R.string.ngfx_status_available),

	/** Anything the server adds later, so a new value never crashes the client. */
	UNKNOWN(null, R.string.ngfx_status_unknown);

	companion object {
		fun from(value: String?): NgfxStatus? {
			if (value == null) return null
			return entries.firstOrNull { it.wireName == value } ?: UNKNOWN
		}
	}
}

data class NgfxSearchResult(
	val tmdbId: Int,
	val mediaType: NgfxMediaType,
	val title: String,
	val year: String?,
	val overview: String?,
	val posterUrl: String?,
	val status: NgfxStatus?,
	val canRequest: Boolean,
)

data class NgfxDownload(
	val label: String?,
	val progress: Double,
	/** Null means not moving: stalled, paused or seeding. Not the same as zero. */
	val speedBytesPerSecond: Long?,
	val etaSeconds: Long?,
)

data class NgfxRequest(
	val id: Int,
	val tmdbId: Int?,
	val mediaType: NgfxMediaType?,
	val title: String?,
	val posterUrl: String?,
	val seasonNumber: Int?,
	val status: NgfxStatus,
	val requestedAt: String?,
	/** 0.0-1.0, averaged across every in-flight download. Null when nothing runs. */
	val progress: Double?,
	/** The longest of the downloads: the request is not done until its slowest part is. */
	val etaSeconds: Long?,
	/** The sum of the downloads: parallel downloads really do consume both streams. */
	val speedBytesPerSecond: Long?,
	val downloading: List<NgfxDownload>,
	/** What the admin wrote when turning the request down. Only ever set on REJECTED. */
	val rejectionReason: String?,
	/**
	 * The Jellyfin item this request became. Null until Jellyfin has actually reported
	 * the title in a library scan, so an action gated on it can never link into a 404.
	 */
	val jellyfinItemId: String?,
)

enum class NgfxError(val messageRes: Int) {
	NOT_CONFIGURED(R.string.ngfx_error_not_configured),
	UNAUTHORIZED(R.string.ngfx_error_unauthorized),
	REJECTED_BY_NGFX(R.string.ngfx_error_rejected_by_ngfx),
	FORBIDDEN(R.string.ngfx_error_forbidden),
	CONFLICT(R.string.ngfx_error_conflict),
	UNAVAILABLE(R.string.ngfx_error_unavailable),
	NETWORK(R.string.ngfx_error_network),
	MALFORMED(R.string.ngfx_error_malformed),
}

class NgfxException(
	val error: NgfxError,
	message: String? = null,
	cause: Throwable? = null,
) : Exception(message ?: error.name, cause)
