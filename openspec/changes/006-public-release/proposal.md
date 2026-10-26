# Change 006: Public release on Google Play and RuStore

## Why

The app was personal and sideloaded. To publish it, it needs store-ready builds, an About screen with legal links, a clean first run for reviewers who cannot sign in to someone else's Yandex account, correct OAuth settings for the store signing keys, and store listings.

## What Changes

- About screen: version, privacy policy link, open-source licenses, support contact.
- Versioning and builds: `versionName 1.0.0`, increasing `versionCode`, AAB for Google Play, APK for RuStore.
- The first run works fully without Yandex sign-in (local import, playback, bookmarks).
- Owner actions listed separately: store accounts, privacy policy hosting, Yandex OAuth publication and fingerprints, Data safety and foreground-service declarations, closed test, listings.
- Documentation no longer says "personal use only".

## Capabilities

### Modified Capabilities

- `app-shell`: About screen, first-run behaviour without an account.

## Impact

`app/build.gradle.kts`, `AndroidManifest.xml`, new `ui/about/`, `README.md`, `AGENTS.md`, store assets under `docs/store/`.
