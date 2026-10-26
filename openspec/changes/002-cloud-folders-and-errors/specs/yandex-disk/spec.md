# Spec Delta

## ADDED Requirements

### Requirement: Cloud folders are created on demand

Whenever the app writes a file to Disk, it MUST create all missing parent folders first. A folder that already exists MUST NOT be treated as an error.

#### Scenario: Deep path on an empty Disk

- **WHEN** the app writes `books/Foo/part1/01.mp3` and none of the folders exist
- **THEN** `books`, `books/Foo` and `books/Foo/part1` are created and the file is written

#### Scenario: Folder already exists

- **WHEN** a parent folder already exists and Disk answers with a conflict
- **THEN** the write continues without an error

### Requirement: Download failure reporting

When a download stops because a file cannot be fetched, the app MUST remember the file and the server code, stop retrying permanent errors early, and show the reason on the book screen with "Retry" and "Skip file" actions.

#### Scenario: Server error on one file

- **WHEN** file 17 of a book answers HTTP 500 on two attempts in a row
- **THEN** the download stops, the book screen shows that file 17 failed with HTTP 500, and the retry limit of 15 is not used up

#### Scenario: Skipping the broken file

- **WHEN** the user taps "Skip file" and restarts the download
- **THEN** the remaining files are downloaded and the book becomes playable with the chapters that exist

#### Scenario: Temporary network loss

- **WHEN** the connection drops without a server answer
- **THEN** the download retries with the existing backoff and is not marked as failed
