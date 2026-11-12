# Retro Player Android

A native Android companion app for Retro Player, built with Kotlin and Jetpack Compose. The app targets Android phones and tablets; Android TV is intentionally out of scope for this first version.

## Included systems

The EmulatorJS runtime is configured for these system IDs:

| System | EmulatorJS core ID |
|---|---|
| PlayStation 1 | `psx` |
| NES | `nes` |
| Super Nintendo | `snes` |
| Game Boy | `gb` |
| Game Boy Advance | `gba` |
| Nintendo 64 | `n64` |
| Sega Genesis / Mega Drive | `segaMD` |
| Sega Master System | `segaMS` |
| Sega Game Gear | `segaGG` |

The runtime is loaded inside a restricted Android WebView from the pinned EmulatorJS 4.2.3 CDN path. The ROM itself is loaded from Retro Player's private app storage through AndroidX WebViewAssetLoader. The app does not enable broad file-scheme access or universal file access from file URLs.

## Features in this first implementation

- Native Compose library with search, console filters, favorites, recently played, playtime, and title/recent sort.
- Local game import through Android's Storage Access Framework. The game files are copied to app-private storage; they are not uploaded to Supabase.
- Ambiguous `.bin` imports prompt for PlayStation 1 or Sega Genesis.
- A dedicated EmulatorJS player screen with the system-specific core ID, mobile browser mode, touchscreen haptics, and optional user-provided PS1 BIOS.
- Account sign-in, sign-up, password reset, and sign-out using the same Supabase project and publishable key as the Retro Player website.
- Session tokens are encrypted with Android Keystore AES-GCM.
- Cloud library metadata sync through the existing `public.account_sync` table, plus recent-played records through `public.recent_games`. Row Level Security remains enabled; the app only sends the signed-in user's JWT.
- Android-specific save backup import/export and cloud backup. Backups are limited to 5 MB each.
- Optional six-hour background sync through WorkManager.
- Settings for dark theme, default volume, haptics, PS1 BIOS, and device diagnostics.
- CI workflow to assemble a debug APK and publish it as a GitHub Actions artifact.

## Important limitations to verify

This is an initial native app implementation, not a validated release build yet.

1. **No real-device benchmarks have been run.** EmulatorJS compatibility does not guarantee full-speed emulation on every Android phone. Test every core and game on the target device.
2. **Bluetooth controller input depends on the Android WebView Gamepad API and the controller.** It needs physical testing and button-mapping verification.
3. **EmulatorJS's own in-player save states use its browser storage/menu.** The app's cloud backup feature currently handles user-imported save and memory-card files separately. It does not claim that the website and Android can exchange live save-state files yet.
4. **The runtime is fetched from the pinned EmulatorJS CDN release.** The first emulator launch needs network access; library browsing and imported-file management are local. Offline emulator startup is not guaranteed until runtime caching is tested.
5. **Multi-file PlayStation CUE/BIN sets should be selected together** so the CUE file and its referenced track files are copied into the same import folder.
6. **BIOS files are not included.** Import only firmware files you are legally entitled to use.
7. **The Android APK has not been built or installed on a physical device by this repository change.** The included GitHub Actions workflow is intended to run the first build.

## Build

Open the project in Android Studio with JDK 17 and Android SDK Platform 36 installed, or use Gradle 8.13 from a local terminal:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The GitHub Actions workflow uploads the APK as an artifact when CI succeeds.

## Supabase configuration

The app uses the public publishable key already present in the Retro Player website. It is a client-side key, not a service-role key. Do not add a Supabase service-role or secret key to this app.

The app does not create, alter, or disable any database tables, policies, or functions. It uses the existing RLS-protected account tables. See the Supabase project configuration in `app/build.gradle.kts`.

## Legal files

Retro Player does not bundle games or BIOS files. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for EmulatorJS and dependency notices.
