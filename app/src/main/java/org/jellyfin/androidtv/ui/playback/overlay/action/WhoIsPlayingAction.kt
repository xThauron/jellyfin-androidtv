package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.View
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.whoson.WhoIsPlaying
import org.jellyfin.androidtv.whoson.R as WhoSonR

/**
 * Overlay button that asks Home Assistant who is on screen right now.
 *
 * Only the wiring lives here, the behaviour is in the :whoson module. Keeping it that way
 * means merging upstream cannot conflict with anything but this one small file.
 */
class WhoIsPlayingAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	init {
		initializeWithIcon(R.drawable.ic_users)
		setLabels(arrayOf(context.getString(WhoSonR.string.whoson_action_label)))
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) = WhoIsPlaying.start(context)
}
