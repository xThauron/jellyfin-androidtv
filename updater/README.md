# Jellyfin Updater (fork only)

Standalone Android TV app that installs the newest fork release of
`org.jellyfin.androidtv` from this repository's GitHub releases, so the box does not
have to be updated over `adb` by hand.

It is a separate app on purpose: nothing in `:app` is touched, so merging upstream
never conflicts with it.

## How it works

1. Lists `https://api.github.com/repos/<FORK_REPOSITORY>/releases` and picks the newest
   non-draft release that ships a `jellyfin-androidtv-v*-release.apk` asset.
2. Derives a version code from the release tag with the same formula the build uses
   (see `VersionCodes`) and compares it to the installed app.
3. Downloads the APK to the cache directory and hands it to `PackageInstaller`.

Both the repository and the package it updates are set in `build.gradle.kts` as
`FORK_REPOSITORY` and `TARGET_PACKAGE`.

## Requirements on the device

- The installed Jellyfin app must be a fork build, signed with the same keystore as the
  releases. An official build cannot be updated by this app: Android rejects an APK whose
  signature differs. Uninstall the official app once and sideload a fork build first.
- The updater needs "install unknown apps" permission:
  Settings > Apps > Security & restrictions > Install unknown apps > Jellyfin Updater.

On Android 12 and newer the system may skip the confirmation dialog once this app is the
installer of record for Jellyfin, which happens after the first update it performs.

## Keeping it in sync

`VersionCodes.fromVersionName` mirrors `getVersionCode` in
`buildSrc/src/main/kotlin/VersionUtils.kt`. If the version code formula upstream changes,
update both.
