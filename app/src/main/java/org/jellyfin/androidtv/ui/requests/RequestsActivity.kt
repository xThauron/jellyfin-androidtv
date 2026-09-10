package org.jellyfin.androidtv.ui.requests

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.jellyfin.androidtv.ui.base.JellyfinTheme

/**
 * Hosts the "my requests" screen.
 *
 * Declared in the :ngfx module manifest rather than the app manifest, so enabling this
 * feature does not modify an upstream file.
 */
class RequestsActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setContent {
			JellyfinTheme {
				RequestsScreen()
			}
		}
	}
}
