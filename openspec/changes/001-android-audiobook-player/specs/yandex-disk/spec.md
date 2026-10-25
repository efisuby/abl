# Spec Delta

## Purpose

Provides access to audiobooks stored in Yandex Disk: authorization, browsing the book folder, downloading to the device and managing occupied space. Cloud audio is read-only.

## ADDED Requirements

### Requirement: Yandex ID sign-in

The user MUST be able to sign in to a Yandex account and sign out. The access token is stored on the device in protected form and MUST NOT appear in backups or logs.

#### Scenario: Successful sign-in

- **WHEN** the user completes authorization in Yandex ID
- **THEN** the app receives a token and shows the contents of the book folder

#### Scenario: Signing out

- **WHEN** the user taps "Sign out"
- **THEN** the token is removed from the device and the cloud section becomes unavailable

### Requirement: Expired token

If the server rejects the token, the app MUST ask the user to sign in again and MUST NOT lose the local library or progress.

#### Scenario: Token expired

- **WHEN** an API request returns 401
- **THEN** the app asks the user to sign in again and local books stay available

### Requirement: Browsing the book folder

The app MUST show the contents of the app's fixed folder in the cloud, loading tree levels as the user navigates, and MUST distinguish folders from files.

#### Scenario: Navigating the tree

- **WHEN** the user opens the books folder
- **THEN** the nested folders and supported audio files of the current level are shown

#### Scenario: Empty folder

- **WHEN** the folder contains no books
- **THEN** the app shows a hint on how to add books through the Disk web interface

### Requirement: Importing a book from the cloud

The user MUST be able to add a book from the cloud: either a folder with audio files or a single m4b/m4a. The book appears in the library immediately, while the audio downloads in the background.

#### Scenario: Importing a folder

- **WHEN** the user picks a cloud folder with 30 mp3 files and taps "Download"
- **THEN** the book appears in the library with 30 chapters and the "downloading" state

### Requirement: Download queue

The app MUST download books in the background through the job scheduler, resume an interrupted download from the break point, limit the number of concurrent downloads and show progress in a notification.

#### Scenario: Connection drops

- **WHEN** the connection is lost at 60% of a download and then restored
- **THEN** the download continues from 60% instead of starting over

#### Scenario: Cancelling a download

- **WHEN** the user cancels a book download
- **THEN** the job stops, temporary files are removed and the book returns to the "in the cloud" state

### Requirement: Network constraint for downloads

The user MUST be able to enable "download over Wi-Fi only". On a mobile connection, jobs MUST wait for Wi-Fi and MUST NOT consume traffic.

#### Scenario: Mobile connection with the constraint on

- **WHEN** a book is queued and the active connection is mobile
- **THEN** the download does not start until Wi-Fi is available

### Requirement: Offline book states

The app MUST show one of the states "in the cloud", "downloading", "partially downloaded" or "downloaded" for every book. Playback MUST be available only for fully downloaded books.

#### Scenario: Playing a book that is not downloaded

- **WHEN** the user taps play on a book in the "in the cloud" state
- **THEN** the app offers to download the book and does not start playback

### Requirement: Storage management

The app MUST show the size of each book and the total downloaded volume, MUST allow deleting the audio files of a single book, and MUST warn when there is not enough space for a download.

#### Scenario: Not enough space

- **WHEN** a book needs 1.8 GB and 900 MB are free
- **THEN** the download does not start and the user sees a warning with the book size

### Requirement: Cloud is read-only

The app MUST modify only its own service files in the cloud and MUST NOT modify, rename or delete audio files or book folders.

#### Scenario: Deleting a book from the device

- **WHEN** the user deletes a downloaded book
- **THEN** no delete request is sent to the audio files in the cloud
