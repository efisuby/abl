# Change 004: Import books from Google Drive

## Why

Many books live in Google Drive. The Drive app already exposes its files through the system file picker (a documents provider), so no Google sign-in, scopes or app verification are needed if the importer copes with what such providers return.

## What Changes

- The add menu names the sources: "From device" and "From Google Drive / other apps".
- The importer tolerates provider quirks: unknown file size, streamed or virtual files, slow reads, errors in the middle of a file, revoked access.
- Import progress becomes indeterminate when the size is unknown, and failures show a clear message.

## Capabilities

### Modified Capabilities

- `library`: import from any documents provider including Google Drive.

## Impact

`data/importer/SafImportSources`, `BookImporter`, `ui/importer/`, `sheet_add`, the empty state; no new permissions or dependencies.
