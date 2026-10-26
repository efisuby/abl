# Spec Delta

## MODIFIED Requirements

### Requirement: Cloud is read-only

The app MUST modify only its own service files and the book folders it created itself in the app folder of the cloud. It MUST NOT modify, rename or delete audio files or book folders that were not uploaded by the app, and MUST NOT write audio to the cloud without an explicit user action.

#### Scenario: Deleting a book from the device

- **WHEN** the user deletes a downloaded book
- **THEN** no delete request is sent to the audio files in the cloud

#### Scenario: Browsing does not write

- **WHEN** the user only opens and plays books
- **THEN** no audio file is uploaded or changed in the cloud

## ADDED Requirements

### Requirement: Upload a local book

The user MUST be able to upload a locally imported book to the app folder of Yandex Disk. The upload runs in the background, creates the needed folders, checks every file by md5, honours the "Wi-Fi only" setting and resumes after interruption without re-sending finished files. The book keeps its progress and bookmarks and becomes available to other devices.

#### Scenario: Successful upload

- **WHEN** the user uploads a local book of 3 files
- **THEN** `books/<title>` is created in the app folder with 3 files whose md5 match, the book stays playable offline and appears in the library of another signed-in device as a cloud book

#### Scenario: Interrupted upload

- **WHEN** the connection is lost after file 2 of 3 and then restored
- **THEN** only file 3 is sent and no duplicate is created

#### Scenario: Not signed in

- **WHEN** the user chooses "Upload to cloud" without a Yandex sign-in
- **THEN** the app asks the user to sign in first and uploads nothing

#### Scenario: Not enough space on Disk

- **WHEN** Disk answers that the quota is exceeded
- **THEN** the upload stops, the app shows a quota message and the local book is untouched
