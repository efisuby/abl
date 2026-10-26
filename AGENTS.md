# AGENTS.md — ABL (AudioBook Player) Project State

**Last updated:** 2026-10-26

---

## Project Overview

**ABL** — Android audiobook player with Yandex.Disk cloud library.
- Package: `com.efisuby.abl`
- Architecture: single `:app` module, `data/domain/ui/playback` packages, Room as source of truth, manual DI via `AppContainer`
  - Existing today: `data/` (db, repository, settings, importer), `domain/` (model, repository interfaces), `di/`, `ui/` (common, library, importer, book, player), `playback/` (service, queue, timer).
  - Screens are separate Activities (no fragments/navigation lib yet); ViewModels via `viewModelFactory { initializer { … } }` reading `appContainer`. Edge-to-edge is enforced at target 36 → call `enableEdgeToEdge()` + `applySystemBarInsets(view)` (`ui/common/UiKit.kt`).
- UI toolkit: **Views + ViewBinding** (AppCompat, Material, RecyclerView, ConstraintLayout). **No Jetpack Compose** — don't add it without a design change.
- Toolchain: JDK 21 to run Gradle; bytecode target Java/JVM 17. KSP 2.3.12 (Room compiler).
- Distribution: sideload / personal use (no Play Store)

---

## Yandex OAuth

- App registered as **Android application** (type "Для авторизации пользователей")
- Client ID: `4a94a7008ebc4ad2b5c0800dd28aed62` (public, in manifestPlaceholders)
- SDK: `com.yandex.android:authsdk:3.1.3` (Maven Central)
- Scopes: `cloud_api:disk.read`, `cloud_api:disk.write`, `cloud_api:disk.info`
- Release keystore: `~/keystores/abl-release.jks` (alias `abl`, RSA 4096, valid to 2056)
- SHA-256 fingerprint (add to OAuth console):
  ```
  7B:F2:E4:8A:08:37:E8:38:9E:AA:8F:95:3F:E2:7D:8D:9D:85:CB:7E:3C:5C:4B:F7:F8:A0:44:77:F4:C8:F9:A2
  ```

---

## Code Invariants & Gotchas (read before changing data layer)

- **Room schema is exported** to `android/app/schemas/` (currently version 2: `metadataUpdatedAt`, `MIGRATION_1_2`, `MigrationTest`). No `fallbackToDestructiveMigration` — any entity change needs a version bump + `Migration` + committed schema JSON.
- **Enums stored as `String` (`.name`)** and mapped back with `valueOf()` in `data/db/Mappers.kt`. Renaming an enum constant (`BookSource`, `BookStatus`, `DownloadState`) breaks existing rows — needs a migration.
- **`saveBook()` replaces all chapters** (delete by bookId + upsert in one transaction). `progress.chapterId` and `bookmarks.chapterId` have **no FK** to `chapters` — chapter IDs must be deterministic, otherwise re-import leaves dangling references.
- **`LibraryRepository.deleteBook()` removes DB rows only** (chapters/progress/bookmarks via `ON DELETE CASCADE`). UI must delete through `BookActions.delete()` (`AppContainer.bookActions`), which also removes `filesDir/books/<bookId>/`. `PlaybackService` stops and clears the queue when the playing book disappears from the DB; `savePosition()` ignores a book that no longer exists.
- **`BookActions`** also owns `setStatus()` (status + `progress.finished`, position kept) and `updateMetadata()` (DB + `book.json` + cover as `cover-<stamp>.jpg`, so the path changes and the list reloads it).
- **Library search/filter/sort/series grouping is in memory** (`domain/library/LibraryArranger`), not SQL: SQLite NOCASE/LIKE folds ASCII only. `ProgressDao.observeBookPositions()` gives the absolute position for the listened %.
- **Progress `updatedAt` is monotonic**: `max(now, previous + 1)` — required for cross-device LWW merge. Keep this in any new write path (`savePosition` also bumps `books.lastListenedAt`).
- **Bookmarks use tombstones** (`deleted = 1` via `markDeleted`), never hard delete — needed for union-merge sync. `RoomBookmarkRepository` (in `AppContainer`) orders by chapter index, then position — the DAO alone orders by position only.
- **Settings split**: only `playback_speed` is syncable (`SyncableSettings.keys`). `wifi_only` and `last_sync_at` are device-local and must never be synced. Speed is clamped to 0.5–3.0.
- **Device ID** lives in plain SharedPreferences (`device` / `device_id`), not DataStore — intentional, DataStore is for user settings only.
- **JSON = kotlinx-serialization 1.8.1** (`book.json` via `BookManifestCodec`, `ignoreUnknownKeys`, `schemaVersion`). Release (R8 + `shrinkResources`) builds; runtime check of serialization in release is task 9.2.
- **Import (`data/importer/`)**: `BookImporter` copies through `.part` (md5 per file), writes `book.json`, then `saveBook`; on failure/cancel deletes the whole book dir. Chapter IDs = `UUID.nameUUIDFromBytes("$bookId/$relativeFile#$startMs")`. Remember: `CancellationException` *is* an `IllegalStateException` — check it first in catch/when.
- **m4b chapters** (spike `docs/spikes/m4b-chapters.md`): Media3 `MetadataRetriever` (module `media3-inspector`) gives Nero `chpl` only; QuickTime chapter tracks need `Mp4ChapterTrackReader` (only a `tref/chap`-referenced text track counts). `CascadingChapterReader` = Media3 → track reader → one chapter.
- **m4b tags**: `MediaMetadataRetriever` misses QuickTime `keys`/`mdta` tags (ebook2audiobook writes these) → `Mp4Tags` (ex-`Mp4QuickTimeKeys`) fills gaps. MMR is fed a file descriptor; it still cannot read `/data/local/tmp` (SELinux), copy files into app storage first.
- **Series tags**: no platform key exists. `Id3Frames` (ID3v2.3/2.4 text frames, `TXXX` keyed by description) and `Mp4Tags` (`----` free-form, `©mvn`/`©mvi` → `mvnm`/`mvin`, `mdta` keys) feed `MediaMetadataTagReader`; key lists are `SERIES_KEYS`/`SERIES_PART_KEYS`. Index = first number of the part tag (`2/10` → 2), only when a series is present.
- **TTS markup**: `TtsMarkup.strip` runs inside `FileTags.of` (all text tags) and on embedded chapter titles in `BookImporter`; it removes only stress `+` before a Cyrillic vowel and `[break]`/`[pause]`/`[voice:…]`. Keep it narrow — `C++` and `[Часть 1]` must survive (task 2.7).
- **Import UI**: `ImportActivity` gets the picker URI as `intent.data` (+ `folder` extra), the import lives in `ImportViewModel` (survives rotation; leaving the screen cancels it and the importer cleans up). No persistable URI permission — files are copied immediately. `AppContainer.importSources` is a `var` only so `ImportScreenTest` can serve fixtures through `MockContentResolver` and gate copying.
- **Playback (`playback/`)**: `PlaybackService` (MediaSessionService + ExoPlayer, speech audio attributes, ±15 s increments). Controllers ask for a book with `PlaybackIds.request(bookId, chapterId?, positionMs?)` as the only media item; `onSetMediaItems` answers with one item per chapter (`ChapterQueue` → `QueueEntry.toMediaItem`, mediaId `"$bookId/$chapterId"`, clipped for m4b, last chapter of a file unclipped). Progress writes go through one ordered `Channel` in `applicationScope` (a late position write must not undo "finished"); `PositionTracker` throttles to 5 s and ignores chapters of a previous book during a queue swap. Sleep timer = custom session command `SleepTimer.COMMAND`, state in session extras; "end of chapter" = `pauseAtEndOfMediaItems`. Speed lives in DataStore (`AppSettings.normalizeSpeed`), the service collects it.
- **Media3 unstable APIs** (`CommandButton` prefs, `pauseAtEndOfMediaItems`, `Chapter`) need `@OptIn(UnstableApi::class)` or lint fails with `UnsafeOptInUsageError`.
- **Test fixtures**: `app/src/androidTest/assets/fixtures/` (ffmpeg-generated, also on the JVM test classpath). SAF tests use `FixtureDocumentsProvider` + `MockContentResolver` (`useLibrary("android.test.mock")`). `M4bSpikeTest` runs only if real books are in `/data/local/tmp/abl-spike/`.
- **Dark-only, Russian-only** (app-shell spec): `AblApplication` forces `MODE_NIGHT_YES` (so system bar icons stay light under edge-to-edge) and the app locale `ru` (`LocaleManager` on 33+, so library strings are Russian too); theme parent `Theme.Material3.Dark`. Only `values/strings.xml`, never add other locales. On 33+ `Resources.getSystem()` reports the app locale — use `LocaleManager.systemLocales` for the device language.
- **Settings screen** (`ui/settings/`): Wi-Fi only, storage size + «Удалить все загрузки» (`BookActions.removeDownloads()`: only `YANDEX_DISK` books in DOWNLOADED/PARTIAL → state IN_CLOUD, `audio/` removed, manifest and cover kept; `PlaybackService` stops a book that is no longer DOWNLOADED), last sync time, account placeholder (sign-out is task 6.3). Entry: toolbar icon of the library. `MainActivity` = toolbar search + status/source/sort chips + «Продолжить слушать» card + library with series headers, status and % + empty states; `BookActivity` = header (series, progress, status popup) + bookmarks + chapters, menu edit/delete; `EditBookActivity` = metadata + cover (`PickVisualMedia`, scaled ≤1024 px JPEG); `PlayerActivity` accepts chapter + position (bookmarks). Shared UI-test helpers: `androidTest/.../ui/UiTestSupport.kt`.
- **Cloud books & downloads (`data/disk/`)**: `CloudBookImporter` makes an `IN_CLOUD` record (bookId = hash of the remote path, placeholder chapters, `book.json` with `ManifestFile.remote`/size/md5); `CloudLibraryAdder` = import + `DownloadController.start` (free-space check, 50 MB headroom, then `DownloadWorker` via `DownloadScheduler`; Wi-Fi only → `UNMETERED`). `BookDownloader` resumes `.part` by `Range`, verifies md5, then `MediaBookFinalizer` reads tags/chapters/cover and sets `DOWNLOADED`. Cancel by the user = state flips to `IN_CLOUD` *first*, then the worker drops `audio/`; a system stop keeps `.part`. UI: `Book.needsDownload` blocks play (`offerDownload`) in the book screen and the «Продолжить» card. `AppContainer.downloadScheduler` is a `var` for tests (the controller looks it up per call).
- **Sync (`data/sync/`)**: files on Disk under `Приложения/AudioBookPlayer` — `progress/<deviceId>.json`, `settings/<deviceId>.json`, shared `bookmarks.json` and `library.json`, `covers/<bookId>.jpg` (`SyncEngine` over `CloudFiles` + `SyncStore`; merge rules in `SyncMerge`). A damaged file of another device is skipped. No change queue: local changes live in Room and each run publishes the difference, so offline changes go out on the next run. `SyncRunner` classifies the result (transient → WorkManager `retry()` with backoff), `WorkManagerSyncScheduler` = unique work `sync`, network CONNECTED, `APPEND_OR_REPLACE`, 10 s settle delay unless manual. Triggers: `MainActivity` cold start, `PlayerActivity.onStop` (not on rotation), Settings button. `AppContainer.syncScheduler` is a `var` for tests. Metadata sync is LWW on `books.metadataUpdatedAt` (monotonic, set in `BookActions.updateMetadata`).
- **Look & feel**: warm dark palette in `values/colors.xml` (`abl_*`, amber accent) mapped in `themes.xml`; shape scale 12/16/24 dp (`radius_*`, `ShapeAppearance.Abl.*`), spacing tokens `space_*`. Styles: `Widget.Abl.Cover` (ShapeableImageView, rounded, use `ImageView.setCover()` to get the book placeholder when there is no cover), `Widget.Abl.Card`, `Widget.Abl.SheetRow`, `Widget.Abl.Button.Danger`. Library = CoordinatorLayout (toolbar/filters/continue card scroll away) + `addFab` → bottom sheet `sheet_add` (`sheetFolder/sheetFile/sheetCloud`); `importFolder`/`importFile` live in the empty state. `MiniPlayer` (`ui/library/`) connects its own `MediaController` between `onStart` and `onStop`; the «Продолжить» card hides while it shows. `PlayerActivity` has a toolbar (back, «О книге»). Keep view ids stable — UI tests use them.
- OpenSpec: all artifacts in English (`openspec/config.yaml`); every task must state how it is verified. `openspec/specs/` is empty until change 001 is archived.

---

## Next Up

`openspec/changes/001-android-audiobook-player/tasks.md` is the source of truth (first unchecked task); design decisions are in `design.md`.

---

## Known Risks / Spikes Required

1. **Yandex ID SDK** — returns only access token (~1 year), no refresh token. Re-auth once/year. Verify Disk permission group is selectable for login-type app; if not, fall back to API-access type + manual code flow.
2. **Disk API rate limits** — need cached listings + lazy tree loading for browser (task 7.x).
3. **Large downloads (1–2 GB)** — WorkManager with `Range` resume, md5 verification, `dataSync` foreground notification, Wi-Fi-only constraint, storage guardrails.

---

## Environment

```bash
# SDK
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

# Emulator (already created)
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager list avd
# → abl-api36 (pixel_7, API 36, google_apis/x86_64, 512 MB sdcard)

# Start headless
nohup $ANDROID_HOME/emulator/emulator -avd abl-api36 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 3072 > /tmp/opencode/emulator.log 2>&1 &

# Device (physical)
# SM-S926B (Galaxy S24+), serial R5CX3488MTY
# Old package com.abl.audiobookplayer still installed — manually uninstall if needed
```

---

## Commands not to guess

```bash
cd /home/spell/src/abl/android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest   # pick the device when the phone is plugged in too
# connectedDebugAndroidTest uninstalls the app afterwards; to keep files a test writes:
#   ./gradlew :app:installDebug :app:installDebugAndroidTest
#   adb shell am instrument -w -e class <Test> com.efisuby.abl.test/androidx.test.runner.AndroidJUnitRunner
cd /home/spell/src/abl && openspec validate 001-android-audiobook-player --strict
```

---

## Git

- Remote: `git@github.com:efisuby/abl.git` (branch `master`)
- Author: `Alexander Baranovskiy <3995123+efisuby@users.noreply.github.com>`
- `.claude/` ignored (local ECC config)
- Keystore files ignored (`keystore.properties`, `*.jks`, `*.keystore`) — example file `keystore.properties.example` tracked

---

## Handoff Notes for Next Agent

1. **Status (2026-10-26):** Groups 1–8 complete (8.8 checked manually on the S24+). Next: group 9 (integration and verification).
2. **m4b spike done** — `docs/spikes/m4b-chapters.md`; sample has no Audible/iTunes m4b yet.
3. **Backup rules are verified** — don't remove them. The A/B test proved exclusion works.
4. **Signing is stable** — debug and release use the same release key. OAuth fingerprint won't change.
5. **OpenSpec tasks.md is the source of truth** for what's done/next. Update checkboxes as you complete tasks.

---

*Generated from session context. Update this file when significant milestones change.*