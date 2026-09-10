package org.jellyfin.androidtv.ui.requests

import org.jellyfin.androidtv.ngfx.NgfxCredentials
import org.jellyfin.sdk.api.client.ApiClient

/**
 * NGFX sits behind the Jellyfin plugin, so it reuses the address and access token the
 * SDK client already holds - there is no separate NGFX login. Null while no session is
 * active, which the client turns into a "not configured" error.
 */
fun ApiClient.ngfxCredentials(): NgfxCredentials? {
	val address = baseUrl ?: return null
	val token = accessToken ?: return null

	return NgfxCredentials(address, token)
}
