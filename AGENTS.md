# AGENTS.md — ABL (AudioBook Player) Project State

**Last updated:** 2026-10-25 (commit 7ccbb04)

---

## Project Overview

**ABL** — Android audiobook player with Yandex.Disk cloud library.
- Package: `com.efisuby.abl`
- Min SDK: 24, Target SDK: 36, Compile SDK: 36
- Kotlin 2.3.20, AGP 8.13.2, Gradle 8.14.6
- Architecture: single `:app` module, `data/domain/ui/playback` packages, Room as source of truth, manual DI via `AppContainer`
  - Existing today: `data/` (db, repository, settings, importer), `domain/` (model, repository interfaces), `di/`, `ui/` (common, library, importer, book, player), `playback/` (service, queue, timer).
  - Screens are separate Activities (no fragments/navigation lib yet); ViewModels via `viewModelFactory { initializer { … } }` reading `appContainer`. Edge-to-edge is enforced at target 36 → call `enableEdgeToEdge()` + `applySystemBarInsets(view)` (`ui/common/UiKit.kt`).
- UI toolkit: **Views + ViewBinding** (AppCompat, Material, RecyclerView, ConstraintLayout). **No Jetpack Compose** — don't add it without a design change.
- Toolchain: JDK 21 to run Gradle; bytecode target Java/JVM 17. KSP 2.3.12 (Room compiler).
- Distribution: sideload / personal use (no Play Store)

---

## What's Done (M1 — Foundation & Local Playback, Group 1)

### Code
- **Room schema** — `books`, `chapters`, `progress`, `bookmarks` tables with DAOs and mappers
- **Repositories** — `RoomLibraryRepository`, `RoomProgressRepository` over Room
- **Settings** — DataStore Preferences with explicit syncable-key allowlist (`theme`, `language`, `playback_speed`)
- **DI** — `AppContainer` (lazy init), `AblApplication`, `Context.appContainer` extension
- **Backup rules** — `dataExtractionRules` + `fullBackupContent` excluding `filesDir/books/` (verified: 5.6 KB vs 20.98 MB with 20 MB audio file)

### Tests
- Unit: `SyncableSettingsTest` (2 tests)
- Instrumented: `AblDatabaseTest` (chapters ordered by index), `RepositoriesTest` (saveBook, savePosition monotonic updatedAt, deleteBook cascades)
- All pass on API 36 emulator (`pixel_7`, x86_64)

### OpenSpec
- Change `001-android-audiobook-player` — **5 capabilities, 38 requirements, 54 tasks**
- All artifacts in English (proposal, specs: library/playback/yandex-disk/sync/app-shell, design.md, tasks.md)
- Group 1 tasks (1.1–1.5) ✅ checked in `tasks.md`
- Validation: `openspec validate --strict` → valid

### Yandex OAuth
- App registered as **Android application** (type "Для авторизации пользователей")
- Client ID: `4a94a7008ebc4ad2b5c0800dd28aed62` (public, in manifestPlaceholders)
- SDK: `com.yandex.android:authsdk:3.1.3` (Maven Central)
- Scopes: `cloud_api:disk.read`, `cloud_api:disk.write`, `cloud_api:disk.info`
- Release keystore: `~/keystores/abl-release.jks` (alias `abl`, RSA 4096, valid to 2056)
- SHA-256 fingerprint (add to OAuth console):
  ```
  7B:F2:E4:8A:08:37:E8:38:9E:AA:8F:95:3F:E2:7D:8D:9D:85:CB:7E:3C:5C:4B:F7:F8:A0:44:77:F4:C8:F9:A2
  ```

### Build & Verification
```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest  # BUILD SUCCESSFUL
./gradlew :app:connectedDebugAndroidTest                                         # 4 tests pass on emulator
adb install -r .../app-debug.apk                                                 # Success on device & emulator
```

---

## Code Invariants & Gotchas (read before changing data layer)

- **Room schema is exported** to `android/app/schemas/` (currently version 1). No `fallbackToDestructiveMigration` — any entity change needs a version bump + `Migration` + committed schema JSON.
- **Enums stored as `String` (`.name`)** and mapped back with `valueOf()` in `data/db/Mappers.kt`. Renaming an enum constant (`BookSource`, `BookStatus`, `DownloadState`) breaks existing rows — needs a migration.
- **`saveBook()` replaces all chapters** (delete by bookId + upsert in one transaction). `progress.chapterId` and `bookmarks.chapterId` have **no FK** to `chapters` — chapter IDs must be deterministic, otherwise re-import leaves dangling references.
- **`deleteBook()` removes DB rows only** (chapters/progress/bookmarks via `ON DELETE CASCADE`). Deleting `filesDir/books/<bookId>/` is still TODO (task 4.6).
- **Progress `updatedAt` is monotonic**: `max(now, previous + 1)` — required for cross-device LWW merge. Keep this in any new write path (`savePosition` also bumps `books.lastListenedAt`).
- **Bookmarks use tombstones** (`deleted = 1` via `markDeleted`), never hard delete — needed for union-merge sync. `RoomBookmarkRepository` (in `AppContainer`) orders by chapter index, then position — the DAO alone orders by position only.
- **Settings split**: only `theme`, `language`, `playback_speed` are syncable (`SyncableSettings.keys`). `wifi_only` and `last_sync_at` are device-local and must never be synced. Speed is clamped to 0.5–3.0.
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
- **Strings**: only `values/strings.xml` (Russian). `values-en/` is not created yet (task 5.2). `MainActivity` = «Продолжить слушать» card + minimal library list + «Добавить папку/файл» (full home screen is task 4.1); `BookActivity` = minimal book screen: header + «Слушать» + chapters (tap plays); `PlayerActivity` = player with speed, sleep timer, bookmarks (full book screen is task 4.2).
- OpenSpec: all artifacts in English (`openspec/config.yaml`); every task must state how it is verified. `openspec/specs/` is empty until change 001 is archived.

---

## Next Up: Task Group 2 — Local Import & Metadata

| Task | Description | Verification |
|------|-------------|--------------|
| 2.1 | SAF folder/file picker (`OpenDocumentTree`, `OpenDocument`) | Instrumented test: 3 mp3 → 1 book, 3 chapters |
| 2.2 | Copy to `filesDir/books/<bookId>` via `.part` + `book.json` | Unit test: manifest serialization, cancelled copy leaves no `.part` |
| 2.3 | Metadata/cover extraction (`MediaMetadataRetriever`) with folder-name fallback | Unit tests: full tags, partial tags, no tags |
| 2.4 | **m4b chapter spike** → cascade: `MediaMetadata.chapters` → `chpl` → chapter track → single chapter | `docs/spikes/m4b-chapters.md` + unit tests on extracted boundaries |
| 2.5 | Nested folder traversal, chapter ordering by path | Unit test: `part-1/02.mp3`, `part-1/01.mp3`, `part-2/01.mp3` → chapters 1,2,3 |
| 2.6 | Import screen with copy progress & errors | Instrumented test: import folder → shows progress → opens book screen |

### Key Design Decisions (from `design.md`)
- **Storage layout:** `filesDir/books/<bookId>/{book.json,cover.jpg,audio/}` — private, excluded from backup
- **Chapter model:** mp3 = separate file (startMs=0); m4b = shared file + `ClippingConfiguration` with startMs/durationMs
- **Metadata extraction:** `MediaMetadataRetriever` for mp3; m4b chapter cascade with mandatory spike first
- **Book ID for cloud books:** deterministic `UUID.nameUUIDFromBytes(remotePath.getBytes())`

---

## Known Risks / Spikes Required

1. **m4b chapter extraction** — Media3 exposes `MediaMetadata.chapters` but real files vary (QuickTime chapter track vs Nero `chpl`). Spike `docs/spikes/m4b-chapters.md` must be done before 2.4.
2. **Yandex ID SDK** — returns only access token (~1 year), no refresh token. Re-auth once/year. Verify Disk permission group is selectable for login-type app; if not, fall back to API-access type + manual code flow.
3. **Disk API rate limits** — need cached listings + lazy tree loading for browser (task 7.x).
4. **Large downloads (1–2 GB)** — WorkManager with `Range` resume, md5 verification, `dataSync` foreground notification, Wi-Fi-only constraint, storage guardrails.

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

## Common Commands

```bash
cd /home/spell/src/abl/android

# Build
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# Tests
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # needs emulator/device

# Lint
./gradlew :app:lintDebug

# OpenSpec
cd /home/spell/src/abl
openspec validate 001-android-audiobook-player --strict
openspec status --change 001-android-audiobook-player --json
openspec list
```

---

## Git

- Remote: `git@github.com:efisuby/abl.git` (branch `master`)
- Author: `Alexander Baranovskiy <3995123+efisuby@users.noreply.github.com>`
- `.claude/` ignored (local ECC config)
- `AGENTS.md` itself is **untracked** as of 2026-10-25 — commit it if it should be shared
- Keystore files ignored (`keystore.properties`, `*.jks`, `*.keystore`) — example file `keystore.properties.example` tracked

---

## Handoff Notes for Next Agent

1. **Group 2 status (2026-10-25):** Group 2 complete. Group 3 complete (3.1, 3.4, 3.9 checked manually on the S24+). Next: group 4 (library).
2. **m4b spike done** — `docs/spikes/m4b-chapters.md`; sample has no Audible/iTunes m4b yet.
3. **Backup rules are verified** — don't remove them. The A/B test proved exclusion works.
4. **Signing is stable** — debug and release use the same release key. OAuth fingerprint won't change.
5. **OpenSpec tasks.md is the source of truth** for what's done/next. Update checkboxes as you complete tasks.

---

*Generated from session context. Update this file when significant milestones change.*