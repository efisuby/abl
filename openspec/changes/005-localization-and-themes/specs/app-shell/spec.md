# Spec Delta

## REMOVED Requirements

### Requirement: Dark appearance

**Reason**: The app follows the system theme and offers a choice.

**Migration**: Replaced by "Appearance".

### Requirement: Interface language

**Reason**: The app is localized.

**Migration**: Replaced by "Interface languages".

## ADDED Requirements

### Requirement: Appearance

The app MUST support system, light and dark themes. The default is the system theme; the user can change it in settings and the choice is stored on the device only.

#### Scenario: Following the system

- **WHEN** the theme is "System" and the system switches to light mode
- **THEN** the app is displayed in the light appearance with readable text and icons in the system bars

#### Scenario: Forcing dark

- **WHEN** the user selects "Dark" while the system is light
- **THEN** the app is dark and stays dark after a restart

### Requirement: Interface languages

The interface MUST be available in Russian and English. By default it follows the device language (Russian for any language other than English), and the user can pick the language in settings.

#### Scenario: English device

- **WHEN** the device language is English and the app language is "System"
- **THEN** all screens, plurals and notifications are in English

#### Scenario: Choosing Russian

- **WHEN** the user selects Russian in settings
- **THEN** the interface switches to Russian without reinstalling and the choice persists

## MODIFIED Requirements

### Requirement: Settings screen

The app MUST provide a settings screen that gathers the "Wi-Fi only" mode, theme, language, downloaded volume with a cleanup action, synchronization state and account sign-out.

#### Scenario: Cleaning up downloads

- **WHEN** the user taps "Delete all downloads" and confirms
- **THEN** the audio files are removed from the device and the books stay in the library with the "in the cloud" state

#### Scenario: Changing theme

- **WHEN** the user opens the theme choice and selects "Light"
- **THEN** the open screens switch to the light appearance immediately
