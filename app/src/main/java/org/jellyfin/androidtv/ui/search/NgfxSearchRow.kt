package org.jellyfin.androidtv.ui.search

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ngfx.NgfxClient
import org.jellyfin.androidtv.ngfx.NgfxException
import org.jellyfin.androidtv.ngfx.NgfxSearchResult
import org.jellyfin.androidtv.ngfx.R as NgfxR
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.requests.ngfxCredentials
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Adds a "requestable" row to the search screen when Jellyfin itself found nothing,
 * mirroring what the web client does.
 *
 * Kept out of the existing search pipeline on purpose: SearchResultGroup carries
 * Jellyfin's own BaseItemDto, and NGFX results are a different shape. Appending a row
 * with its own adapter and presenter avoids touching either.
 */
class NgfxSearchRow(
	private val context: Context,
	private val rowsAdapter: MutableObjectAdapter<Row>,
) {
	private val client = NgfxClient {
		KoinJavaComponent.get<ApiClient>(ApiClient::class.java).ngfxCredentials()
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var searchJob: Job? = null

	/**
	 * Called after the Jellyfin results have been rendered. Does nothing unless every
	 * group came back empty, so the row only ever appears as a fallback.
	 */
	fun showIfNothingFound(groups: Collection<SearchResultGroup>, query: String) {
		searchJob?.cancel()

		if (query.isBlank()) return
		if (groups.any { it.items.isNotEmpty() }) return

		searchJob = scope.launch {
			val results = try {
				client.search(query)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "ngfx: search failed")
				return@launch
			}

			if (results.isEmpty()) return@launch

			val adapter = ArrayObjectAdapter(NgfxCardPresenter())
			results.forEach(adapter::add)

			rowsAdapter.add(
				ListRow(HeaderItem(context.getString(NgfxR.string.ngfx_search_row)), adapter)
			)
		}
	}

	/**
	 * Returns true when the click belonged to this row, so the caller can stop.
	 */
	fun handleClick(item: Any?): Boolean {
		if (item !is NgfxSearchResult) return false

		if (!item.canRequest) {
			// Already requested or the role does not allow it; status says which
			val status = item.status?.labelRes?.let(context::getString)
			Toast.makeText(context, status ?: context.getString(NgfxR.string.ngfx_request_failed), Toast.LENGTH_SHORT).show()
			return true
		}

		val label = listOfNotNull(item.title, item.year?.let { "($it)" }).joinToString(" ")

		AlertDialog.Builder(context)
			.setTitle(NgfxR.string.ngfx_request_confirm_title)
			.setMessage(context.getString(NgfxR.string.ngfx_request_confirm_message, label))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ -> submit(item, label) }
			.show()

		return true
	}

	private fun submit(item: NgfxSearchResult, label: String) {
		scope.launch {
			val message = try {
				client.request(item)
				context.getString(NgfxR.string.ngfx_request_sent, label)
			} catch (error: CancellationException) {
				throw error
			} catch (error: NgfxException) {
				context.getString(error.error.messageRes)
			} catch (error: Exception) {
				Timber.w(error, "ngfx: request failed")
				context.getString(NgfxR.string.ngfx_request_failed)
			}

			Toast.makeText(context, message, Toast.LENGTH_LONG).show()
		}
	}
}

/**
 * Card for an NGFX result. Uses a ComposeView the same way CardPresenter does, so the
 * cards match the rest of the rows instead of looking like a bolted-on list.
 */
private class NgfxCardPresenter : Presenter() {
	override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true
		}

		return NgfxCardViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is NgfxCardViewHolder) return
		if (item !is NgfxSearchResult) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is NgfxCardViewHolder) return

		viewHolder.unbind()
	}

	private class NgfxCardViewHolder(private val composeView: ComposeView) : ViewHolder(composeView) {
		private val item = MutableStateFlow<NgfxSearchResult?>(null)

		init {
			composeView.setContent {
				JellyfinTheme {
					val current by item.collectAsState()

					if (current != null) {
						Column(
							verticalArrangement = Arrangement.spacedBy(4.dp),
							modifier = Modifier.width(CARD_WIDTH.dp),
						) {
							AsyncImage(
								url = current?.posterUrl,
								aspectRatio = POSTER_ASPECT,
								modifier = Modifier.width(CARD_WIDTH.dp),
							)
							Text(
								text = current?.title.orEmpty(),
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
							current?.year?.let { Text(it) }
						}
					}
				}
			}
		}

		fun bind(result: NgfxSearchResult) {
			item.value = result
		}

		fun unbind() {
			item.value = null
		}
	}

}

private const val CARD_WIDTH = 116
private const val POSTER_ASPECT = 2f / 3f
