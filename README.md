# Retro Player Android

A native Android companion app for Retro Player, built with Kotlin and Jetpack Compose. This first version targets Android phones and tablets. Android TV is intentionally out of scope.

## Supported systems

The player is configured for these EmulatorJS core IDs:

| System | Core ID |
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

## Included features

- Native Jetpack Compose library with search, console filters, favorites, recently played, playtime, and sorting.
- Local game import using Android's Storage Access Framework. ROM files are copied to app-private storage and are not uploaded to Supabase.
- Ambiguous `.bin` imports prompt for PlayStation 1 or Sega Genesis.
- EmulatorJS player inside a restricted Android WebView, with mobile mode, hardware acceleration, volume, touch haptics, and optional user-provided PS1 BIOS.
- Existing Retro Player account sign-in, sign-up, password recovery, and sign-out using Supabase.
- Session tokens encrypted with Android Keystore AES-GCM.
- Cloud library metadata and recent-played sync using the existing Supabase tables and Row Level Security.
- Import/export of emulator save and memory-card backup files, with optional account cloud backup.
- Optional periodic background sync using WorkManager.
- Settings for theme, volume, haptics, automatic sync, PS1 BIOS, and diagnostics.
- GitHub Actions workflow to assemble a debug APK and upload it as an artifact.

## Important limitations

1. No Android device benchmarks have been run. EmulatorJS support does not guarantee full-speed emulation on every device; test all nine systems on real hardware.
2. Bluetooth controller support depends on Android WebView's Gamepad API and the connected controller. Button mapping and latency still need physical testing.
3. The save-backup screen handles files imported or exported by an emulator. It is separate from EmulatorJS's live in-player browser save storage; automatic capture of live EmulatorJS saves is not yet verified or claimed.
4. The EmulatorJS 4.2.3 runtime is downloaded from its pinned CDN path. The first player launch requires internet access; offline emulator startup is not yet guaranteed.
5. For multi-file PlayStation CUE/BIN images, select the CUE and all referenced track files together.
6. BIOS files are not included. Import only firmware files you are legally entitled to use.
7. Cloud backup data is protected by HTTPS, account authentication, and database Row Level Security, but is not client-side end-to-end encrypted.
8. The project has not yet been validated on a physical Android device. The CI workflow must pass before the debug APK can be considered build-verified.

## Build

Open the project in Android Studio with JDK 17 and Android SDK Platform 36 installed, or use Gradle 8.13:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions uploads it as the `retro-player-android-debug` artifact after a successful build.

## Supabase configuration

The app uses the existing Supabase project URL and public publishable client key. The publishable key is designed for client-side use; never add a service-role key or other secret to the Android app.

The app does not create, alter, or disable Supabase tables, policies, or functions. It uses the existing RLS-protected account tables.

## Legal

Retro Player does not bundle games or BIOS files. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party notices.
