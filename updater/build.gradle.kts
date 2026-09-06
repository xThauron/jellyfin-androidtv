plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "org.jellyfin.androidtv.updater"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		applicationId = namespace
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		versionName = project.getVersionName()
		versionCode = getVersionCode(versionName!!)

		// Where to look for releases and which package they update
		buildConfigField("String", "FORK_REPOSITORY", "\"xThauron/jellyfin-androidtv\"")
		buildConfigField("String", "TARGET_PACKAGE", "\"org.jellyfin.androidtv\"")
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
	}

	signingConfigs {
		val keystoreFile = getProperty("keystore.file")
		val keystorePassword = getProperty("keystore.password")
		val signingKeyAlias = getProperty("signing.key.alias")
		val signingKeyPassword = getProperty("signing.key.password")

		if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
			create("release") {
				storeFile = file(keystoreFile)
				storePassword = keystorePassword
				keyAlias = signingKeyAlias
				keyPassword = signingKeyPassword
			}
		}
	}

	buildTypes {
		release {
			// Small enough that shrinking only buys us reflection surprises
			isMinifyEnabled = false

			signingConfig = signingConfigs.findByName("release")
		}

		debug {
			// Use a different application id to run release and debug at the same time
			applicationIdSuffix = ".debug"
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}
}

base.archivesName.set("jellyfin-updater-v${project.getVersionName()}")

dependencies {
	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.appcompat)

	// Kotlin
	implementation(libs.kotlinx.coroutines)
}
