# Change 003: Upload a local book to Yandex Disk

## Why

Books imported from the device stay on that device only: they are not restored after a reinstall and do not appear on other devices. The user wants to push a local book to the cloud so it joins the library everywhere.

## What Changes

- Book menu action "Upload to cloud" for locally imported books.
- Files go to `Приложения/AudioBookPlayer/books/<title>` (folders created automatically, change 002), uploaded in the background with an md5 check, the Wi-Fi-only rule and resume of interrupted uploads.
- After the upload the book is a cloud book that is also on the device and is listed in `library.json` for other devices; progress and bookmarks are kept.
- **BREAKING** for the earlier promise "the app never uploads": audio is now written to Disk, only on explicit user action and only inside the app folder.

## Capabilities

### Modified Capabilities

- `yandex-disk`: "Cloud is read-only" becomes "writes only on user action in the app folder"; new "Upload a local book".

## Impact

`data/disk/` (new `BookUploader`, `UploadWorker`, `UploadScheduler`), `AppContainer`, `BookActions`, `ui/book/`, possibly a Room migration (design D2).
