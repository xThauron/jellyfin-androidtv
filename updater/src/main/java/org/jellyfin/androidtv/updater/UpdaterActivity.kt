package org.jellyfin.androidtv.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.updater.databinding.ActivityUpdaterBinding
import java.io.File

/**
 * Single screen that compares the installed app against the newest fork release and, on
 * request, downloads and installs it.
 */
class UpdaterActivity : AppCompatActivity() {
	private companion object {
		const val ACTION_INSTALL_STATUS = "org.jellyfin.androidtv.updater.INSTALL_STATUS"
		const val APK_NAME = "update.apk"
		const val BYTES_PER_MB = 1024f * 1024f
	}

	private data class InstalledVersion(val name: String, val code: Int)

	private lateinit var binding: ActivityUpdaterBinding

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val installer by lazy { ApkInstaller(this) }

	private var installedVersion: InstalledVersion? = null
	private var latestRelease: ReleaseInfo? = null
	private var busy = false
	private var publishedPercent = -1

	private val installStatusReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) = onInstallStatus(intent)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		binding = ActivityUpdaterBinding.inflate(layoutInflater)
		setContentView(binding.root)

		binding.action.setOnClickListener { startInstall() }
		binding.refresh.setOnClickListener { checkForUpdates() }

		ContextCompat.registerReceiver(
			this,
			installStatusReceiver,
			IntentFilter(ACTION_INSTALL_STATUS),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)

		refreshInstalledVersion()
		checkForUpdates()
	}

	override fun onDestroy() {
		unregisterReceiver(installStatusReceiver)
		scope.cancel()
		super.onDestroy()
	}

	private fun checkForUpdates() {
		if (busy) return

		setBusy(true)
		setStatus(getString(R.string.status_checking))
		binding.progress.isVisible = false
		binding.progressText.text = ""

		scope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { GitHubReleases.findLatest(BuildConfig.FORK_REPOSITORY) }
			}

			refreshInstalledVersion()
			setBusy(false)

			result.fold(
				onSuccess = { release ->
					latestRelease = release
					renderAvailability()
				},
				onFailure = { error -> showError(error) },
			)
		}
	}

	private fun startInstall() {
		val release = latestRelease ?: return
		if (busy) return

		setBusy(true)
		setStatus(getString(R.string.status_downloading))
		binding.progress.isVisible = true
		binding.progress.progress = 0
		binding.progressText.text = ""
		publishedPercent = -1

		scope.launch {
			val result = runCatching {
				val target = cachedApk()

				withContext(Dispatchers.IO) {
					ApkDownloader.download(release.assetUrl, target) { read, total ->
						publishProgress(read, if (total > 0) total else release.assetSize)
					}
				}

				target
			}

			result.fold(
				onSuccess = { apk -> handOffToInstaller(apk) },
				onFailure = { error -> showError(error) },
			)
		}
	}

	private suspend fun handOffToInstaller(apk: File) {
		setStatus(getString(R.string.status_installing))
		binding.progress.progress = 100

		// Streaming the APK into the install session is disk bound, keep it off the main thread
		runCatching {
			withContext(Dispatchers.IO) {
				installer.install(apk, BuildConfig.TARGET_PACKAGE, ACTION_INSTALL_STATUS)
			}
		}.onFailure { error -> showError(error) }
	}

	/**
	 * Called from the download thread, so hop back to the main thread and only report
	 * whole percentages to avoid flooding it.
	 */
	private fun publishProgress(read: Long, total: Long) {
		val percent = if (total > 0) ((read * 100) / total).toInt() else 0
		if (percent == publishedPercent) return
		publishedPercent = percent

		runOnUiThread {
			binding.progress.progress = percent
			binding.progressText.text = getString(
				R.string.progress_bytes,
				read / BYTES_PER_MB,
				total / BYTES_PER_MB,
			)
		}
	}

	private fun onInstallStatus(intent: Intent) {
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

		when (status) {
			// The system wants the user to confirm, it hands us the dialog to launch
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)

				if (confirmation == null) {
					showError(IllegalStateException("Installer did not provide a confirmation dialog"))
				} else {
					startActivity(confirmation)
				}
			}

			PackageInstaller.STATUS_SUCCESS -> {
				cachedApk().delete()
				binding.progress.isVisible = false
				binding.progressText.text = ""
				setBusy(false)
				refreshInstalledVersion()
				renderAvailability()
				setStatus(getString(R.string.status_install_success))
			}

			PackageInstaller.STATUS_FAILURE_ABORTED -> {
				binding.progress.isVisible = false
				binding.progressText.text = ""
				setBusy(false)
				renderAvailability()
				setStatus(getString(R.string.status_install_cancelled))
			}

			else -> {
				val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
				showError(IllegalStateException(message ?: "Installation failed with status $status"))
			}
		}
	}

	private fun renderAvailability() {
		val release = latestRelease

		if (release == null) {
			setStatus(getString(R.string.status_no_release))
			binding.action.setText(R.string.action_unavailable)
			binding.action.isEnabled = false
			binding.refresh.requestFocus()
			return
		}

		val installed = installedVersion
		val label = "v${release.versionName}"

		binding.action.text = when {
			installed == null -> getString(R.string.action_install, label)
			release.versionCode > installed.code -> getString(R.string.action_update, label)
			else -> getString(R.string.action_reinstall, label)
		}

		setStatus(
			when {
				installed == null -> getString(R.string.status_update_available, label, release.versionCode)
				release.versionCode == installed.code -> getString(R.string.status_up_to_date)
				release.versionCode < installed.code -> getString(R.string.status_older_release)
				else -> getString(R.string.status_update_available, label, release.versionCode)
			}
		)

		if (!busy) {
			binding.action.isEnabled = true
			binding.action.requestFocus()
		}
	}

	@Suppress("SwallowedException")
	private fun refreshInstalledVersion() {
		installedVersion = try {
			val info = packageManager.getPackageInfo(BuildConfig.TARGET_PACKAGE, 0)
			InstalledVersion(
				name = info.versionName ?: "?",
				code = PackageInfoCompat.getLongVersionCode(info).toInt(),
			)
		} catch (error: PackageManager.NameNotFoundException) {
			// The app simply is not installed yet, which is a valid state here
			null
		}

		val installed = installedVersion
		binding.installed.text = when (installed) {
			null -> getString(R.string.installed_missing)
			else -> getString(R.string.installed_version, installed.name, installed.code)
		}
	}

	private fun setBusy(value: Boolean) {
		busy = value
		binding.refresh.isEnabled = !value
		binding.action.isEnabled = !value && latestRelease != null
	}

	private fun setStatus(text: CharSequence, isError: Boolean = false) {
		binding.status.text = text
		binding.status.setTextColor(
			ContextCompat.getColor(this, if (isError) R.color.error else R.color.text_primary)
		)
	}

	private fun showError(error: Throwable) {
		setStatus(getString(R.string.status_error, error.message ?: error.javaClass.simpleName), isError = true)
		binding.progress.isVisible = false
		binding.progressText.text = ""
		setBusy(false)
	}

	private fun cachedApk() = File(cacheDir, APK_NAME)
}
