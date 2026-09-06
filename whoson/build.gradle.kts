plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "org.jellyfin.androidtv.whoson"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}
}

dependencies {
	// Kotlin
	implementation(libs.kotlinx.coroutines)

	// Logging
	implementation(libs.timber)
}
