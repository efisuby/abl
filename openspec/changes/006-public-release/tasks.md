# Tasks

## 1. App

- [ ] 1.1 Add the About screen with version, policy link, licenses and contact; verify with an instrumented test that all four elements are visible and the licenses list opens
- [ ] 1.2 Set `versionName 1.0.0` and document `bundleRelease`; verify `./gradlew :app:bundleRelease :app:assembleRelease` succeed and the AAB/APK are signed with the upload key
- [ ] 1.3 Verify the first run without sign-in: import a fixture book, play, add a bookmark; verify with an instrumented test
- [ ] 1.4 Smoke-test the release build on API 24 and API 36 emulators (R8, serialization, media session); verify the instrumented smoke suite passes on both
- [ ] 1.5 Update README and AGENTS.md ("personal use", "one user"); verify `openspec validate 006-public-release --strict`

## 2. Store materials (prepared in the repository)

- [ ] 2.1 Draft the privacy policy text and the Data safety answers in `docs/store/`; verify they match the actual data flows in design D2
- [ ] 2.2 Generate screenshots (ru/en, light/dark) on the emulator, a 512 px icon and a 1024×500 feature graphic into `docs/store/`; verify file sizes and aspect ratios against the store requirements

## 3. Owner actions (cannot be done by the agent)

- [ ] 3.1 Create the Google Play and RuStore developer accounts and complete verification; verify by access to both consoles
- [ ] 3.2 Host the privacy policy at a public URL; verify the URL opens without sign-in
- [ ] 3.3 Check in the Yandex OAuth console whether publishing or moderation is required for `cloud_api:disk.*`, and add the SHA-256 fingerprints of the Play app signing key, the upload key and the RuStore key; verify sign-in works in an internal-test build from each store
- [ ] 3.4 Fill in Data safety, content rating, target audience and foreground-service declarations (`mediaPlayback`, `dataSync`); verify the consoles show no policy warnings
- [ ] 3.5 Run the Play closed test with the required number of testers for the required period (recheck current numbers) and submit to production and RuStore; verify the listings are live
