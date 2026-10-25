# Spec Delta

## Purpose

Defines the app's basic settings: the visual theme, the interface language, the notification permission and the shared settings screen.

## ADDED Requirements

### Requirement: Visual theme

The user MUST be able to choose the light, dark or system theme. The system theme is the default, and a change applies immediately without a restart.

#### Scenario: Changing the theme

- **WHEN** the user chooses the dark theme
- **THEN** the interface switches to the dark appearance immediately

#### Scenario: System theme

- **WHEN** the system theme is selected and dark mode is enabled in the system
- **THEN** the app is displayed in the dark appearance

### Requirement: Localization

The interface MUST support Russian and English. The system language is used by default, with Russian as the fallback when a translation is missing.

#### Scenario: English system

- **WHEN** the device language is English
- **THEN** the interface is displayed in English

### Requirement: Notification permission

On Android 13 and above, the app MUST request the notification permission before the first background playback. If the user denies it, playback MUST still work and the control notification MUST NOT be shown.

#### Scenario: Permission denied

- **WHEN** the user declines the notification permission request
- **THEN** playback starts, no notification appears and the app does not crash

### Requirement: Settings screen

The app MUST provide a settings screen that gathers the theme, language, "Wi-Fi only" mode, downloaded volume with a cleanup action, synchronization state and account sign-out.

#### Scenario: Cleaning up downloads

- **WHEN** the user taps "Delete all downloads" and confirms
- **THEN** the audio files are removed from the device and the books stay in the library with the "in the cloud" state
