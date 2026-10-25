# Tasks

## 1. Data and app shell

- [x] 1.1 Define the Room schema (`books`, `chapters`, `progress`, `bookmarks`) and DAOs; verify with an instrumented test on an in-memory database: inserting a book with chapters returns them in `index` order
- [x] 1.2 Implement `AppContainer` and initialization in `AblApplication`; verify that `./gradlew :app:assembleDebug` passes and the app starts without crashes
- [x] 1.3 Wire DataStore Preferences with an explicit list of syncable keys; verify with a unit test that a setting outside the list does not enter the syncable set
- [x] 1.4 Implement library and progress repositories over Room; verify with instrumented tests: saving a position updates `updatedAt`, deleting a book removes its chapters and bookmarks
- [x] 1.5 Add `dataExtractionRules` excluding downloaded audio from backup; verify with `adb shell bmgr backupnow` on a debug build that the backup size does not grow with audio

## 2. Local import and metadata

- [x] 2.1 Implement folder and file picking through SAF (`OpenDocumentTree`, `OpenDocument`); verify with an instrumented test on a fixture: a picked folder with 3 mp3 files creates one book with 3 chapters
- [x] 2.2 Implement copying into `filesDir/books/<bookId>` through `.part` and writing `book.json`; verify with a unit test of manifest serialization and manually that a cancelled copy leaves no `.part`
- [x] 2.3 Implement metadata and cover extraction (`MediaMetadataRetriever`) with a fallback to the folder name; verify with unit tests on three fixtures: full tags, partial tags, no tags
- [x] 2.4 Run the m4b chapter spike and implement the cascade `MediaMetadata.chapters` → `chpl` → chapter track → single chapter; verify with the `docs/spikes/m4b-chapters.md` report and unit tests on extracted boundaries
- [x] 2.5 Implement nested folder traversal and chapter ordering by path; verify with a unit test: `part-1/02.mp3`, `part-1/01.mp3`, `part-2/01.mp3` become chapters 1, 2, 3
- [x] 2.6 Build the import screen with copy progress and errors; verify with an instrumented test: importing a folder shows progress and finishes by opening the book screen
- [x] 2.7 Strip speech-synthesis markup (stress `+`, `[break]`, `[pause]`, `[voice:…]`) from chapter titles and text tags on import; verify with unit tests on titles taken from real ebook2audiobook books, a markup-only title and lookalike text (`C++`, `[Часть 1]`)

## 3. Player

- [x] 3.1 Wire ExoPlayer inside `MediaSessionService` and publish a notification with the cover and chapter; verify manually: playback continues with the app in the background and the screen locked
- [x] 3.2 Implement chapter queue building (separate `MediaItem`s for mp3, `ClippingConfiguration` for m4b); verify with an instrumented test: when chapter 1 ends, chapter 2 starts automatically in both formats
- [x] 3.3 Build the player screen with pause, ±15 s seek, chapter navigation and progress dragging; verify with a UI test: tapping "back 15" moves the position by 15 s
- [x] 3.4 Implement speed change in the 0.5–3.0× range with persistence in settings; verify with a unit test of persistence and manually that the speed is the same after a restart
- [x] 3.5 Implement the sleep timer (5/10/15/30/45/60 minutes and "until end of chapter"); verify with an instrumented test using a fake clock: when it expires, playback is paused
- [x] 3.6 Implement position autosave every 5 seconds, on pause, on going to the background and at chapter end; verify with a unit test: after a process `kill` the position differs by no more than 5 seconds
- [x] 3.7 Add the "Continue listening" card to the home screen; verify with a UI test: the card shows the last book and opens the player at the saved position
- [x] 3.8 Implement bookmarks: creation at the current position, list, jump, deletion; verify with a DAO test and a UI test of jumping to a bookmark
- [x] 3.9 Implement the `POST_NOTIFICATIONS` request before the first background playback; verify the denial scenario manually: playback works, no notification, no crash

## 4. Library

- [ ] 4.1 Build the home screen with the book list (cover, author, status, progress); verify with a UI test on three books with different statuses
- [ ] 4.2 Build the book screen with cover, metadata, chapter list and bookmarks; verify with a UI test: tapping a chapter starts its playback
- [ ] 4.3 Implement search by title, author and series, filters by status and source, and sorting; verify with unit tests of DAO queries on a set of 10 books
- [ ] 4.4 Implement series grouping and ordering within a series; verify with a unit test: books numbered 2, 1, 3 are shown as 1, 2, 3
- [ ] 4.5 Implement metadata editing and cover picking; verify with a UI test: after saving, the new title is visible in the list and on the book screen
- [ ] 4.6 Implement book deletion with confirmation and directory cleanup; verify with a unit test: after deletion the book directory is gone and the record disappears from the database
- [ ] 4.7 Add empty states for the library and search; verify with a UI test: with an empty library an import hint is visible

## 5. Settings, theme, localization

- [ ] 5.1 Implement the light, dark and system theme with immediate application; verify with an instrumented test: switching the theme changes the activity theme without a restart
- [ ] 5.2 Move all strings into `values/strings.xml` and `values-en/strings.xml`; verify with a build using `-Pandroid.testInstrumentationRunnerArguments` and a manual check of both languages
- [ ] 5.3 Build the settings screen: theme, language, "Wi-Fi only", storage, synchronization, sign-out; verify with a UI test: all elements are reachable and trigger the expected actions

## 6. Yandex Disk: authorization

- [ ] 6.1 Wire the Yandex ID SDK and build the sign-in screen handling `Success`, `Failure`, `Cancelled`; verify manually on the device: a successful sign-in opens the cloud section, cancellation returns to the screen
- [ ] 6.2 Implement encrypted token storage with an Android Keystore key; verify with a unit test: the token reads back identical after writing, and the file contains no plaintext token
- [ ] 6.3 Implement sign-out and 401 handling; verify with a client unit test: 401 moves the state to "sign-in required" while local books remain
- [ ] 6.4 Verify authorization on a real account and confirm that the `cloud_api` permission group is available; if it is not, record the switch to the "For API access" app type in `design.md`

## 7. Yandex Disk: browser and downloads

- [ ] 7.1 Implement the REST client (`/v1/disk/resources`, `download`, `upload`) with models and 401/404/429 handling; verify with MockWebServer tests, including retry after 429
- [ ] 7.2 Build the cloud folder browser with lazy level loading; verify with a UI test on a mock: navigating into a folder requests only its contents
- [ ] 7.3 Implement cloud book import: creating the record, detecting the format, queueing the download; verify with a unit test: `bookId` is the same for two imports of the same path
- [ ] 7.4 Implement the download worker with `Range` resume, `md5` verification and a `dataSync` foreground notification; verify with a MockWebServer test: an interruption mid-way continues from the same byte
- [ ] 7.5 Implement the "Wi-Fi only" constraint, cancellation and retry; verify with a unit test: on a mobile connection the job does not start, and after cancellation the `.part` is removed
- [ ] 7.6 Show offline book states and block playback of a book that is not downloaded; verify with a UI test: tapping play on a book "in the cloud" opens the download offer
- [ ] 7.7 Implement storage management: book sizes, total volume, deleting downloads, free-space check; verify with a unit test of the size calculation and manually the low-space warning

## 8. Synchronization

- [ ] 8.1 Implement `deviceId`, the JSON file format and the `schemaVersion` field; verify with a unit test: re-serializing produces an identical document
- [ ] 8.2 Implement progress upload and merging across all `progress/*.json`; verify with unit tests: with two positions the fresher one wins, and a tie triggers the tie-break
- [ ] 8.3 Implement bookmark synchronization with union and tombstones; verify with a unit test: a deleted bookmark is not restored after merging with an old copy
- [ ] 8.4 Implement metadata and cover synchronization; verify with a unit test: a changed cover is downloaded to the "other device" (emulated with a second directory)
- [ ] 8.5 Implement LWW per settings key and the exclusion of device-specific keys; verify with a unit test: speed is synchronized, "Wi-Fi only" is not
- [ ] 8.6 Implement synchronization triggers: launch, leaving the player, network appearance, manual start; verify with an instrumented test: a player event starts synchronization exactly once
- [ ] 8.7 Implement a local change queue and retry with backoff while offline; verify with a unit test: changes made offline are sent after the network returns
- [ ] 8.8 Implement library restoration after reinstall from `library.json` with an offer to re-download audio; verify manually: clearing app data and signing in restores the list and positions

## 9. Integration and verification

- [ ] 9.1 Walk the end-to-end scenario on a physical device: sign-in, importing a book from Disk, listening with the screen locked, adding a bookmark, switching devices and checking progress
- [ ] 9.2 Build release with R8 and verify serialization, token encryption and playback in the release build
- [ ] 9.3 Reach zero warnings from `./gradlew :app:lintDebug` and pass `openspec validate --strict`
- [ ] 9.4 Update `README.md` (screens, scenarios, limitations) and `docs/spikes/m4b-chapters.md`
- [ ] 9.5 Run all unit and instrumented tests with a single command and record the result

## Workflow follow-up

- Archive the change after all tasks are done and the end-to-end run passes.
- Verify that after archiving `openspec/specs/` contains all five capabilities without placeholder purposes.
