# Spec Delta

## ADDED Requirements

### Requirement: About screen

The app MUST provide an About screen showing the app version, a link to the privacy policy, the open-source licenses and a support contact.

#### Scenario: Opening the licenses

- **WHEN** the user opens About and taps "Open-source licenses"
- **THEN** the list of bundled libraries with their licenses is shown

### Requirement: Usable without a cloud account

All local features (importing books, playback, bookmarks, search) MUST work without signing in to Yandex. Cloud features MUST explain what signing in adds and MUST NOT block local use.

#### Scenario: First launch without sign-in

- **WHEN** the app is launched for the first time and the user never signs in
- **THEN** the empty library offers importing from the device, and importing and playing a book needs no sign-in
