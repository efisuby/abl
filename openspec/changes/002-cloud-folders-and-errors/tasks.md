# Tasks

## 1. Folders

- [x] 1.1 Add `ensureFolder(path)` to `DiskApi`/`DiskSource` and use it in `CloudFiles`; verify with a unit test: a nested path creates 3 levels, a 409 answer is success
- [x] 1.2 Build the fake Disk server for `androidTest` (listing, folders with 409, download with `Range`, upload, injectable 500); verify with a smoke test that `DiskApi(baseUrl = fake)` lists and downloads a fixture

## 2. Download errors

- [x] 2.1 Record the failed file and code in `book.json`, classify permanent errors and stop early; verify with a unit test: two 500s on the same file end the worker without 15 attempts, a dropped connection still retries
- [x] 2.2 Show the reason on the book screen with "Retry" and "Skip file"; verify with an instrumented test against the fake server: file 2 of 3 answers 500, the screen names it, "Skip" lets the other two download and the book plays
- [x] 2.3 Update README limits and AGENTS.md; verify `openspec validate 002-cloud-folders-and-errors --strict`, `lintDebug` clean and the full test run green
