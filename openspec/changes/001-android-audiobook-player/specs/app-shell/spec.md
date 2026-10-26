# Spec Delta

## Purpose

Defines the app's basic settings: the dark appearance, the Russian interface, the notification permission and the shared settings screen.

## ADDED Requirements

### Requirement: Dark appearance

The app MUST always use the dark appearance, regardless of the system dark mode setting. The user has no theme choice.

#### Scenario: Light system mode

- **WHEN** dark mode is turned off in the system
- **THEN** the app is still displayed in the dark appearance

### Requirement: Interface language

The interface MUST be in Russian only, regardless of the device language.

#### Scenario: English system

- **WHEN** the device language is English
- **THEN** the interface is displayed in Russian

### Requirement: Notification permission

On Android 13 and above, the app MUST request the notification permission before the first background playback. If the user denies it, playback MUST still work and the control notification MUST NOT be shown.

#### Scenario: Permission denied

- **WHEN** the user declines the notification permission request
- **THEN** playback starts, no notification appears and the app does not crash

### Requirement: Settings screen

The app MUST provide a settings screen that gathers the "Wi-Fi only" mode, downloaded volume with a cleanup action, synchronization state and account sign-out.

#### Scenario: Cleaning up downloads

- **WHEN** the user taps "Delete all downloads" and confirms
- **THEN** the audio files are removed from the device and the books stay in the library with the "in the cloud" state
