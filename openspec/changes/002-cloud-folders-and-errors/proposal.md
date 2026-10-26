# Change 002: Cloud folders created automatically, understandable download errors

## Why

Writing to Disk needs every parent folder to exist, and today each caller creates its own chain. A download that hits a permanent server error (HTTP 500 on one file of a book) is retried 15 times in silence and then fails with no reason shown; the user cannot tell which file is broken or continue with the rest.

## What Changes

- One `ensureFolder(path)` helper creates the whole chain of cloud folders; "already exists" is success. All writes to Disk use it.
- A download remembers which file failed and with which code. Permanent errors (HTTP 4xx, 500 on the same file) stop retrying early; transient ones keep the backoff.
- The book screen shows the reason and lets the user skip the failed file or retry.

## Capabilities

### Modified Capabilities

- `yandex-disk`: folder creation for writes, failed-download reporting, skipping a file.

## Impact

`data/disk/DiskApi.kt`, `data/sync/CloudFiles.kt`, `DownloadWorker.kt`, `BookDownloader.kt`, `ui/book/BookActivity.kt`; the failure record lives in `book.json` (see design).
