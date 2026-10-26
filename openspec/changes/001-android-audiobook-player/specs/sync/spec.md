# Spec Delta

## Purpose

Synchronizes listening progress, bookmarks, book metadata and selected settings between devices through Yandex Disk, resolves conflicts and restores the library after a reinstall.

## ADDED Requirements

### Requirement: Progress synchronization

The app MUST upload each book's listening position to the cloud (chapter, offset, total time) and MUST pick the freshest value when receiving, without losing a newer local position.

#### Scenario: Progress from another device

- **WHEN** a book was played to 2:10:00 on the phone and to 0:30:00 on the tablet, and the tablet synchronizes
- **THEN** the tablet shows position 2:10:00

### Requirement: Conflict resolution

When the same data is changed on several devices at once, the app MUST keep both versions where a union is possible (bookmarks) and MUST choose the most recently modified version where a union is not possible (position, settings).

#### Scenario: Simultaneous listening

- **WHEN** two devices listen to the same book offline and then come online
- **THEN** the position with the later modification time is kept and the other position does not block synchronization

### Requirement: Bookmark synchronization

Bookmarks MUST be synchronized between devices: a bookmark created on one device appears on the others, and a deleted one is not restored during the next exchange.

#### Scenario: Deletion does not resurrect

- **WHEN** the user deletes a bookmark on the phone and synchronizes, while the tablet still holds an old copy
- **THEN** after synchronization the bookmark is absent on both devices

### Requirement: Metadata and cover synchronization

Manually changed title, author, narrator, series, series number and cover MUST be transferred between devices.

#### Scenario: Editing metadata

- **WHEN** the user changes a book cover on the phone
- **THEN** after synchronization the tablet shows the new cover

### Requirement: Settings synchronization

The app MUST synchronize only listening settings (speed). Device-specific settings, including the Wi-Fi-only download constraint and storage parameters, MUST NOT be synchronized.

#### Scenario: Speed on the second device

- **WHEN** the user sets speed 1.25× and it is synchronized
- **THEN** on the other device a new session starts at 1.25×

#### Scenario: Network constraint is not transferred

- **WHEN** "download over Wi-Fi only" is enabled on the phone
- **THEN** that setting does not change on the tablet

### Requirement: Automatic synchronization

The app MUST synchronize on launch, on leaving the player, when the network appears and on manual request, without blocking the interface.

#### Scenario: Manual synchronization

- **WHEN** the user taps "Synchronize"
- **THEN** the exchange runs in the background and the result is shown when it finishes

### Requirement: Offline durability

Changes made without a network MUST be stored locally and sent on the next successful synchronization, and MUST NOT be lost when the app restarts.

#### Scenario: Long offline period

- **WHEN** the user listens to a book for a week without internet
- **THEN** after reconnecting the current position and all bookmarks are uploaded to the cloud

### Requirement: Restore after reinstall

After reinstalling the app and signing in, the library, progress and bookmarks MUST be restored from the cloud, and for books without local files the app MUST offer to download them again.

#### Scenario: Reinstall

- **WHEN** the user installs the app again and signs in
- **THEN** the book list and positions are restored, and books show a prompt to download the audio
