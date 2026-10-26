# Tasks

## 1. Importer

- [x] 1.1 Extend `FixtureDocumentsProvider` with modes (size -1, slow reads, error after N bytes, security exception); verify with a smoke test of each mode
- [x] 1.2 Support unknown sizes in `SafImportSources`/`BookImporter` and the import progress; verify with an instrumented test: an unknown size imports a 3-file folder and the progress bar is indeterminate
- [x] 1.3 Make mid-file failures and revoked access clean; verify with tests: no `.part`, no book directory and no database row remain, and the screen shows the error

## 2. UI

- [x] 2.1 Rename the add sheet entries and empty-state hints; verify with `LibraryScreenTest`/`ImportScreenTest` (ids unchanged)
- [x] 2.2 Update README and AGENTS.md; verify `openspec validate 004-google-drive-import-saf --strict`, `lintDebug` clean and the full test run green
