# Design

## D1. Upload protocol
Per file: `GET /v1/disk/resources/upload?path=…&overwrite=false` returns a link; `PUT` the bytes; the local md5 is compared with the md5 Disk reports afterwards. Files go one at a time. After an interruption the next run lists the target folder and skips files that already exist with the right size and md5; an upload is atomic per file, so the unit of resume is a file.

## D2. Identity of an uploaded book
`bookId` of cloud books is a hash of the remote path, while local books have a random id. Chapter ids derive from `bookId`, so changing it would orphan progress and bookmarks. Decision: keep the local `bookId` and store the remote path in the book record and in `book.json` (`remotePath`); `library.json` carries `remotePath`, so a second device creates the cloud record under the same identity. If this needs a column: Room version 3 with `Migration`, committed schema JSON and `MigrationTest` (AGENTS.md rule).

## D3. Source and state
An uploaded book gets `source = YANDEX_DISK` (enum names unchanged) and `downloadState = DOWNLOADED`. Upload progress lives in worker progress, not in Room.

## D4. Testing without the real Disk
`DiskApi` takes a `baseUrl`; tests start an in-process fake Disk (`androidTest`, shared with change 002) implementing folders, upload links, PUT, listing, md5 and failure injection, and point `AppContainer.diskSource` at it.

## Risks
Disk quota exhausted (HTTP 507): stop with a clear message. A title with characters invalid for Disk paths is sanitised and the mapping kept in `book.json`.
