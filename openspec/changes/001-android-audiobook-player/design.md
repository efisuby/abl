# Design

## Context

See `proposal.md` — Why. Constraints that mattered at design time:

- The app is personal and sideloaded; Play policies do not shape the architecture, but OS requirements (foreground service types, `POST_NOTIFICATIONS`) do apply.
- The Yandex Disk REST API does not support conditional writes (no `If-Match`/ETag for updates), so two devices writing the same file at once can lose data.
- The book library lives in the app's fixed folder on Disk; audio is read-only.
- Downloaded books live in the app's private directory and are lost when the app is uninstalled.
- Audio comes in two shapes: a set of mp3 files (chapter = file) and a single m4b/m4a with embedded chapters.

## Goals / Non-Goals

**Goals:**

- A single source of truth for the library and progress — the local database; the network and the cloud are synchronization, not a requirement for working.
- A chapter model independent of the file format: the player and progress work with `Chapter`, not with files.
- Synchronization that survives the absence of conditional writes in the Disk API.
- Room to add streaming and other book sources without reworking the data model.

**Non-Goals:**

- Multi-module Clean Architecture and a DI framework: for a single developer this adds build complexity without benefit.
- Streaming without a full download, Android Auto, a widget, listening statistics — outside this change.
- Writing, renaming or deleting audio in the cloud.
- A custom backend: the only infrastructure is Yandex Disk.

## Decisions

### D1. A single `:app` module, packages instead of modules

Packages: `data` (Room, network, files), `domain` (models, use cases, repository interfaces), `ui` (screens, ViewModels), `playback` (service and media session).

**Why:** one developer, one APK; layer boundaries are expressed well enough by packages and dependency rules. Multi-module builds (`:core:data`, `:feature:*`) buy parallel compilation that is not needed here.

**Alternative:** full Clean Architecture with modules — rejected as premature.

### D2. Room is the source of truth, UI through `StateFlow`

DAOs return `Flow`, ViewModels combine them into `UiState`. The network and the file system write to the database; the UI reads only from the database.

**Why:** one path for UI updates, no drift between "network" and "local" state, and free updates after synchronization.

### D3. Chapters as `MediaItem` with `ClippingConfiguration`

For an mp3 folder each chapter is its own `MediaItem` with its own URI. For an m4b there is one `MediaItem` per chapter with the same URI and `ClippingConfiguration(startMs, endMs)`, with `mediaId` = chapter id.

**Why:** Media3 handles advancing to the next chapter, correct `duration`, `previous/next` and the position within a chapter by itself; there is no need to recompute offsets on every seek.

**Alternative:** one `MediaItem` per book and seeks by chapter offsets — rejected: chapter navigation and intra-chapter progress would have to be computed by hand, and the notification `duration` would show the whole book.

**Limitation:** boundary accuracy is ~10–50 ms because of keyframes; imperceptible for audiobooks.

### D4. Private directory and a book manifest

```
filesDir/books/<bookId>/
  book.json        # metadata, chapter list, source, checksums
  cover.jpg
  audio/...
```

Files are copied from SAF through `ContentResolver` into a temporary `.part` and then renamed. `book.json` makes it possible to restore a book without the database and survives a migration failure.

**Why:** no permission for shared storage, no conflicts with other apps, and deleting a book is simply deleting a directory.

**Consequence:** `allowBackup` is constrained through `dataExtractionRules` — audio is excluded so the 25 MB backup limit is not hit.

### D5. Metadata and chapter extraction

Metadata and cover come from `MediaMetadataRetriever` (ID3, MP4 tags, `getEmbeddedPicture`). m4b chapters use a cascade:

1. `MediaMetadata.chapters` from Media3, if the extractor provides them;
2. parsing of the Nero `chpl` atom;
3. the QuickTime chapter track (`tref/chap`);
4. a single chapter for the whole file.

**Why:** the chapter format in m4b is not standardized and files from different sources behave differently; the cascade gives a predictable result without external dependencies.

**A spike is mandatory:** run 3–5 real books before implementing the chapter screen; the result is recorded in `docs/spikes/m4b-chapters.md`.

### D6. A REST client instead of `disk-restapi-sdk`

OkHttp + kotlinx.serialization, hosts `cloud-api.yandex.net`, header `Authorization: OAuth <token>`.

**Why:** `com.yandex.android:disk-restapi-sdk:1.03` is a 2013 Java library with no Android integration, no resume and no timeout control; for 1–2 GB files that is critical.

**Alternative:** WebDAV — slower on listings and less documented for mobile scenarios.

### D7. Authorization through the Yandex ID SDK, token in Keystore-backed storage

`com.yandex.android:authsdk:3.1.3`, `manifestPlaceholders["YANDEX_CLIENT_ID"]`. The token is encrypted with an Android Keystore key (AES/GCM) and stored in a private file; it never reaches `DataStore`, logs or backups.

**Why:** the SDK does not return a refresh token, so the token lives about a year and requires signing in again; storing it in plaintext on the device is not acceptable.

**Risk:** an app of the "For user authorization" type may not have the `cloud_api` permission group available; plan B is the "For API access" type with `verification_code` and PKCE.

### D8. Synchronization: one file per device instead of a shared one

```
/Apps/AudioBookPlayer/
  books/                     # audiobooks, placed by the user
  progress/<deviceId>.json   # positions of this device
  bookmarks.json             # union + tombstones
  settings/<deviceId>.json   # settings of this device
  library.json               # book and metadata registry
  covers/<bookId>.jpg
```

Progress: on read all `progress/*.json` are merged, and the record with the greatest `updatedAt` per book wins. Bookmarks: union by `id`, deletions are tombstones with `deletedAt`. Settings: LWW per key.

**Why:** the Disk API has no conditional writes, so a shared progress file would lose data on simultaneous writes. Separate files remove the write conflict; merging moves to read time and needs no locks.

**Alternative:** a single `progress.json` with `If-Match` — impossible on this API.

**Limitation:** device clocks can drift; when `updatedAt` values are equal, the larger `positionMs` wins only for books in the "in progress" status, otherwise a deterministic order by `deviceId` applies.

### D9. `bookId` is deterministic for cloud books

`bookId = UUID.nameUUIDFromBytes(remotePath.toByteArray())` for books from Disk; a random UUID for local imports.

**Why:** two devices that imported the same cloud folder must get the same identifier, otherwise progress and bookmarks will not converge.

### D10. Downloads through WorkManager, one unique job per book

`enqueueUniqueWork(bookId, KEEP)`, foreground `dataSync`, resume through `Range`, `md5` verification from Disk metadata, concurrency limit 2, "Wi-Fi only" through `Constraints`.

**Why:** WorkManager survives process restarts, can wait for a network and reports progress in a notification. `md5` serves both as an integrity check and as a signal that a file changed in the cloud.

### D11. Manual DI through `AppContainer`

`AblApplication` creates an `AppContainer` with the database, repositories, Disk client and scheduler; ViewModels receive dependencies through a factory.

**Why:** Hilt adds KSP processing and build time; for an app of about 30 screens a manual container is easier to read.

**Alternative:** Hilt — rejected, can be introduced later without changing the architecture.

### D12. Settings in DataStore Preferences

Theme, language, speed, "Wi-Fi only", last synchronization time. Syncable keys are marked explicitly so that new settings do not leak into the cloud by default.

### D13. Testing

- Unit (JUnit): file-name parsing, chapter order, progress merging (including clock drift), bookmark tombstone logic, the `chpl` parser.
- Network: MockWebServer for the Disk client — 401/404/429 codes, resume through `Range`.
- Instrumented: Room in-memory DAOs, a player smoke test (plays a local file, pauses, restores the position), import verification through `ActivityScenario`.
- Manual verification: OAuth on a real account and downloading a book of ≥500 MB on a physical device.

## Risks / Trade-offs

| Risk | Mitigation |
| --- | --- |
| Variety of m4b chapter formats | Cascade from D5, a spike on real files, degradation to a single chapter without losing playback |
| The `cloud_api` permission group is unavailable for a login app | Fallback to the "For API access" type + PKCE; verified on the first authorization |
| Yandex Disk API rate limits | Listing cache, lazy level loading, retry with exponential backoff on 429 |
| Audio lost on reinstall | `library.json` restores the library; re-downloading is an explicit flow with progress |
| Large files and low free space | Free-space check before start, cancellation with `.part` cleanup, a warning with the book size |
| R8 breaks kotlinx.serialization | `@Serializable` models are covered by keep rules and the release build is checked in every task |
| Android 14+ kills the foreground service | Explicit `mediaPlayback` and `dataSync` types, starting only from a visible state |
| Device clock drift | Deterministic tie-break from D8, `updatedAt` grows monotonically locally |
| Audio in `filesDir` is not backed up | Deliberate: the backup does not grow, recovery comes from the cloud |

## Migration Plan

The project is new, so there are no data migrations. `versionCode = 1`, Room schema version 1 without migrations. `applicationId` changes to `com.efisuby.abl`, so the previously installed placeholder `com.abl.audiobookplayer` cannot be updated and must be removed manually. Rollback means reinstalling the previous APK; the cloud service files survive because their format is versioned by a `schemaVersion` field in every JSON document.

## Open Questions

- Is streaming without a full download (F6) needed — decided after M3, does not affect the data model.
- Are Android Auto and a widget needed — they affect only the UI layer and the media session.
- Are listening statistics needed — they would require a separate table but do not change the current requirements.
