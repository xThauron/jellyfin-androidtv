package org.jellyfin.androidtv.ui.requests

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ngfx.NgfxClient
import org.jellyfin.androidtv.ngfx.NgfxError
import org.jellyfin.androidtv.ngfx.NgfxException
import org.jellyfin.androidtv.ngfx.NgfxFormat
import org.jellyfin.androidtv.ngfx.NgfxRequest
import org.jellyfin.androidtv.ngfx.NgfxStatus
import org.jellyfin.androidtv.ngfx.R as NgfxR
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

private sealed interface RequestsState {
	data object Loading : RequestsState
	data class Failed(val error: NgfxError) : RequestsState
	data class Loaded(val requests: List<NgfxRequest>) : RequestsState
}

/** What clicking a row does, which depends entirely on where the request got to. */
private enum class RowAction { NONE, CANCEL, OPEN }

private val NgfxRequest.rowAction: RowAction
	get() = when {
		// Withdrawing is allowed only while still PENDING; offering it later would
		// just produce a 409, and a button that fails is worse than no button
		status == NgfxStatus.PENDING -> RowAction.CANCEL
		// jellyfinItemId stays null until Jellyfin reported the title, so gating on it
		// means this can never link into a 404
		jellyfinItemId != null &&
			(status == NgfxStatus.AVAILABLE || status == NgfxStatus.PARTIALLY_AVAILABLE) -> RowAction.OPEN
		else -> RowAction.NONE
	}

// The status socket cannot be proxied through Jellyfin, so we poll. DOWNLOADING is the
// only state whose numbers move quickly, so it is the only one worth a tight interval.
private val IDLE_INTERVAL = 30.seconds
private val ACTIVE_INTERVAL = 5.seconds

private const val POSTER_ASPECT = 2f / 3f

@Composable
fun RequestsScreen() {
	val api = koinInject<ApiClient>()
	val navigationRepository = koinInject<NavigationRepository>()
	val client = remember(api) { NgfxClient { api.ngfxCredentials() } }

	val context = LocalContext.current
	val activity = LocalActivity.current
	val scope = rememberCoroutineScope()

	var state by remember { mutableStateOf<RequestsState>(RequestsState.Loading) }
	var refreshKey by remember { mutableIntStateOf(0) }

	LaunchedEffect(refreshKey) {
		while (true) {
			state = try {
				RequestsState.Loaded(client.myRequests())
			} catch (error: CancellationException) {
				throw error
			} catch (error: NgfxException) {
				RequestsState.Failed(error.error)
			} catch (error: Exception) {
				Timber.w(error, "ngfx: failed to load requests")
				RequestsState.Failed(NgfxError.MALFORMED)
			}

			val active = (state as? RequestsState.Loaded)?.requests.orEmpty().any {
				it.status == NgfxStatus.DOWNLOADING
			}
			delay(if (active) ACTIVE_INTERVAL else IDLE_INTERVAL)
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 48.dp, vertical = 32.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(stringResource(NgfxR.string.ngfx_requests_title))

		when (val current = state) {
			is RequestsState.Loading -> Text(stringResource(NgfxR.string.ngfx_requests_loading))
			is RequestsState.Failed -> Text(stringResource(current.error.messageRes))
			is RequestsState.Loaded -> if (current.requests.isEmpty()) {
				Text(stringResource(NgfxR.string.ngfx_requests_empty))
			} else {
				LazyColumn(
					verticalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.fillMaxWidth(),
				) {
					items(current.requests, key = { it.id }) { request ->
						RequestRow(request) {
							when (request.rowAction) {
								RowAction.CANCEL -> confirmCancel(context, scope, client, request) { refreshKey++ }
								RowAction.OPEN -> openInJellyfin(context, navigationRepository, activity, request)
								RowAction.NONE -> refreshKey++
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun RequestRow(request: NgfxRequest, onClick: () -> Unit) {
	val heading = buildString {
		append(request.title.orEmpty())
		// A season request carries its number; a movie or whole-show request does not
		request.seasonNumber?.let { append(" · S%02d".format(it)) }
	}

	val actionLabel = when (request.rowAction) {
		RowAction.CANCEL -> stringResource(NgfxR.string.ngfx_action_cancel)
		RowAction.OPEN -> stringResource(NgfxR.string.ngfx_action_open)
		RowAction.NONE -> null
	}

	// Spelled out rather than inlined: inferring a @Composable lambda type through let
	// is fragile, and an explicit type makes it unambiguous
	val trailing: (@Composable () -> Unit)? = actionLabel?.let { label ->
		{ Text(label) }
	}
	// The point of recording a rejection reason is that the requester reads it
	val footer: (@Composable () -> Unit)? = request.rejectionReason?.let { reason ->
		{ Text(stringResource(NgfxR.string.ngfx_rejection_reason, reason)) }
	}

	ListButton(
		onClick = onClick,
		leadingContent = {
			AsyncImage(
				url = request.posterUrl,
				aspectRatio = POSTER_ASPECT,
				modifier = Modifier.width(48.dp),
			)
		},
		overlineContent = { Text(stringResource(request.status.labelRes)) },
		headingContent = { Text(heading) },
		captionContent = { Text(progressLine(request)) },
		trailingContent = trailing,
		footerContent = footer,
		modifier = Modifier.focusKey("ngfx_request_${request.id}"),
	)
}

@Composable
private fun progressLine(request: NgfxRequest): String {
	val dash = stringResource(NgfxR.string.ngfx_not_moving)

	// Nothing in flight: no numbers to show at all rather than a row of dashes
	if (request.downloading.isEmpty() && request.progress == null) return ""

	val parts = listOf(
		NgfxFormat.progress(request.progress) ?: dash,
		NgfxFormat.speed(request.speedBytesPerSecond) ?: dash,
		NgfxFormat.eta(request.etaSeconds) ?: dash,
	)

	val labels = request.downloading.mapNotNull { it.label }
	val suffix = if (labels.size > 1) "  (${labels.joinToString(", ")})" else ""

	return parts.joinToString(" · ") + suffix
}

private fun confirmCancel(
	context: Context,
	scope: CoroutineScope,
	client: NgfxClient,
	request: NgfxRequest,
	onCancelled: () -> Unit,
) {
	AlertDialog.Builder(context)
		.setTitle(NgfxR.string.ngfx_cancel_confirm_title)
		.setMessage(context.getString(NgfxR.string.ngfx_cancel_confirm_message, request.title.orEmpty()))
		.setNegativeButton(android.R.string.cancel, null)
		.setPositiveButton(android.R.string.ok) { _, _ ->
			scope.launch {
				val message = try {
					client.cancel(request.id)
					onCancelled()
					context.getString(NgfxR.string.ngfx_cancel_done)
				} catch (error: CancellationException) {
					throw error
				} catch (error: NgfxException) {
					// A 409 here means it left PENDING between the poll and the click
					onCancelled()
					context.getString(error.error.messageRes)
				} catch (error: Exception) {
					Timber.w(error, "ngfx: failed to withdraw request")
					context.getString(NgfxError.MALFORMED.messageRes)
				}

				Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			}
		}
		.show()
}

private fun openInJellyfin(
	context: Context,
	navigationRepository: NavigationRepository,
	activity: Activity?,
	request: NgfxRequest,
) {
	val itemId = request.jellyfinItemId?.toUUIDOrNull()
	if (itemId == null) {
		Toast.makeText(context, NgfxR.string.ngfx_error_malformed, Toast.LENGTH_SHORT).show()
		return
	}

	// The main activity underneath owns the navigation host, so navigate there and
	// close this screen to reveal the details page
	navigationRepository.navigate(Destinations.itemDetails(itemId))
	activity?.finish()
}
