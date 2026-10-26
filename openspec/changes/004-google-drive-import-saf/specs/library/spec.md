# Spec Delta

## ADDED Requirements

### Requirement: Import from Google Drive and other document providers

The user MUST be able to import a folder or a file from any system documents provider, including Google Drive, through the system file picker. The importer MUST work when the provider does not report file sizes, and MUST leave no partial book when a read fails.

#### Scenario: Provider without sizes

- **WHEN** the user imports a folder of 3 mp3 files from a provider that reports no size
- **THEN** the book with 3 chapters is created and the progress is shown as indeterminate

#### Scenario: Read fails in the middle

- **WHEN** the provider throws an I/O error after half of a file
- **THEN** the import stops with an error message, the half-copied files are removed and no book appears in the library

#### Scenario: Access revoked

- **WHEN** the provider denies access to a picked document
- **THEN** the app shows an access error and creates no book

#### Scenario: Clear source names

- **WHEN** the user taps "Add"
- **THEN** the sheet offers "From device" for folders and files and mentions Google Drive for the picker
