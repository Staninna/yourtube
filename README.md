# Tube

A lean, de-Googled YouTube client for Android: a native **Kotlin + Jetpack Compose** shell around a
WebView of `m.youtube.com`, with login-free local features. It is a from-scratch, minimal
alternative to the React Native NouTube fork, with faster builds, no React Native, and no Google
account.

## Design principles

- **No login, ever.** No Google account, no OAuth. Everything is local to the device or fetched
  anonymously (channel RSS feeds, public pages).
- **Native shell, web player.** Watching happens in a WebView (YouTube's own player, so playback,
  captions and quality come for free). Everything around it (library, history, feeds) is native
  Compose with local storage.
- **Injected content script** (`app/src/main/assets/inject.js`) handles the in-page concerns: ad
  hiding, resume position, and the background-playback guard.

## Status

v0 is the foundation. See **[docs/FEATURES.md](docs/FEATURES.md)** for the full backlog and what is
and is not built yet. That file is the source of truth, so nothing gets forgotten.

## Build

Needs a JDK 17 and the Android SDK (`ANDROID_HOME`). From this folder:

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:installDebug       # build and install on a connected device
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Package

`com.staninna.tube`, a separate app from the NouTube fork, so it installs side by side. Rename it
in `app/build.gradle.kts` (and the `namespace`) if you want a different id.
