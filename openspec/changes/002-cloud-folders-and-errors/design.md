# Design

## D1. `ensureFolder` lives next to `DiskApi.createFolder`
Splits the path, creates each missing level (`PUT /v1/disk/resources?path=`), treats 409 "already exists" as success. `CloudFiles` (sync) switches to it, so sync folders and upload folders share one code path.

## D2. Failure classification
`DownloadFailure(file, httpCode)` is recorded in `book.json`, not Room: it is transient diagnostic data and avoids a schema bump. HTTP 5xx on the same file twice in a row, and any 4xx other than 401/429, are permanent: the worker ends with a failure and the book state becomes `PARTIAL` or `IN_CLOUD` as today. A network `IOException` without a response, 429 and 503 keep the existing retry.

## D3. Skipping a file
"Skip" adds the file to `skipped` in `book.json`; the downloader ignores it and the finalizer builds chapters from the files present. Skipped files are listed on the book screen with a "Retry" action that removes the mark.

## D4. Testing
A fake Disk server in `androidTest` (shared with change 003) answers 500 for chosen files.
