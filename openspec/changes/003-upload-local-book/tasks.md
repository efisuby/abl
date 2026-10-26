# Tasks

## 1. Data

- [ ] 1.1 Add the remote path to the book record and `book.json` and, if a column is needed, Room version 3 with `Migration`, schema JSON and `MigrationTest`; verify with the migration test on a version 2 database with a local book, progress and a bookmark
- [ ] 1.2 Extend `library.json` and `CloudBookImporter` with `remotePath`; verify with a unit test: a library file from an uploaded book restores one book with the same chapter ids on a second device

## 2. Upload

- [ ] 2.1 Implement `BookUploader` (ensure folders, upload link, PUT, md5 check, skip existing files); verify with a test against the fake Disk: 3 files land with matching md5, a rerun sends nothing
- [ ] 2.2 Add `UploadWorker`/`UploadScheduler` with the Wi-Fi-only constraint, a foreground notification and an `AppContainer` seam; verify with an instrumented test that an interrupted run resumes at file 3
- [ ] 2.3 Map errors (401 sign-in, 507 quota, 5xx) to user messages; verify with unit tests per code

## 3. UI

- [ ] 3.1 Add "Upload to cloud" to the book menu with progress and a result state; verify with an instrumented test against the fake Disk: after the upload the cloud badge appears and playback still works
- [ ] 3.2 End-to-end check on the emulator: import a fixture book, upload, delete locally, download it back; verify md5, chapter count, progress and bookmarks are equal
- [ ] 3.3 Update README, AGENTS.md and the "never uploads" wording; verify `openspec validate 003-upload-local-book --strict`, `lintDebug` clean and the full test run green
