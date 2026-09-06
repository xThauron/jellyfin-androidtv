package org.jellyfin.androidtv.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

class ApkInstaller(private val context: Context) {
	/**
	 * Hand [apk] to the system package installer as an update for [packageName].
	 *
	 * The outcome is not known when this returns: the installer reports it by broadcasting
	 * [statusAction], including the confirmation dialog the user still has to accept.
	 */
	fun install(apk: File, packageName: String, statusAction: String) {
		val installer = context.packageManager.packageInstaller

		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
		params.setAppPackageName(packageName)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			// Only honoured once this app is the installer of record for the target,
			// so the first update still shows the system confirmation dialog.
			params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
		}

		val sessionId = installer.createSession(params)

		installer.openSession(sessionId).use { session ->
			session.openWrite("base.apk", 0, apk.length()).use { output ->
				apk.inputStream().use { input -> input.copyTo(output) }
				session.fsync(output)
			}

			val intent = Intent(statusAction).setPackage(context.packageName)
			var flags = PendingIntent.FLAG_UPDATE_CURRENT
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE

			val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
			session.commit(pendingIntent.intentSender)
		}
	}
}
