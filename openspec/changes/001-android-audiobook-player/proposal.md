# Change 001: ABL — audiobook player with a Yandex Disk library

## Why

The repository currently holds only an empty Android scaffold that renders a placeholder. It needs a personal audiobook player: books live in the cloud, are downloaded for offline listening, and progress, bookmarks and settings move between devices. Off-the-shelf players do not combine Yandex Disk, position sync and these file formats, so the app is built for personal use.

## What Changes

- Import books from local files: a folder of mp3 (chapter = file) or a single m4b/m4a with embedded chapters.
- Media3 player: chapters, seeking, 0.5–3.0× speed, sleep timer, background playback with a notification, resume from the last position.
- Library: covers and metadata, search, sorting, statuses, series, timecoded bookmarks, manual editing.
- Yandex Disk as the book source: Yandex ID sign-in, browsing the app's fixed folder, a download queue with resume, offline states, storage management.
- Sync through Disk: progress, bookmarks, metadata with covers, selected settings; conflict resolution between devices.
- App basics: theme (light/dark/system), ru/en localization, settings screen.
- **BREAKING** for the previously installed placeholder: `applicationId` changes to `com.efisuby.abl`, so it cannot be updated in place and must be uninstalled first.

The app only reads audio files on Disk: it never uploads, renames or deletes books in the cloud.

## Capabilities

### New Capabilities

- `library`: the book collection — import from local files, metadata and cover extraction and editing, series, statuses, search, filtering, sorting, removal from the device.
- `playback`: chapter playback, playback controls, speed, sleep timer, position saving and restoring, background playback, bookmarks.
- `yandex-disk`: Yandex ID authorization, browsing the cloud book folder, importing and downloading books, offline states, storage management.
- `sync`: exchange of progress, bookmarks, metadata and settings through Yandex Disk, conflict resolution, restoration after reinstall.
- `app-shell`: theme, localization, notification permission, settings screen.

### Modified Capabilities

None: the project is new and no existing specifications exist.

## Impact

- `android/app`: all application code — data (Room, DataStore), network (Yandex Disk REST), player (Media3), UI.
- New dependencies: OkHttp, kotlinx.serialization, WorkManager, DataStore; Media3, Room and the Yandex ID SDK are already wired.
- External systems: Yandex OAuth application (package `com.efisuby.abl`, signing key fingerprint, `cloud_api:disk.read/write/info` scopes), Yandex Disk as the library storage.
- Storage: downloaded books live in the app's private directory, so a reinstall requires downloading the audio again; the library and progress are restored from the cloud.
- Signing: debug and release builds share one key so the certificate fingerprint registered in the OAuth application never changes.
