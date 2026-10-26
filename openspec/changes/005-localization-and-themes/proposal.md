# Change 005: Russian and English interface, dark / light / system themes

## Why

The app is forced to dark and Russian (change 001), which blocks a public release: users expect the app to follow the system language and theme, and the Google Play and RuStore audiences include English speakers.

## What Changes

- Themes: system (default), light, dark; a light palette matching the warm style.
- Languages: Russian (default) and English, following the system language; an in-app choice (system / Russian / English).
- **BREAKING**: the "dark only, Russian only" requirements of `app-shell` are replaced; the rule "never add other locales" in AGENTS.md is removed.

## Capabilities

### Modified Capabilities

- `app-shell`: appearance and language requirements, settings screen.

## Impact

`AblApplication`, `values/colors.xml` + `values-night/`, `themes.xml`, new `values-en/strings.xml`, `locales_config.xml`, `ui/settings/`, `AppSettings` (device-local keys), UI test helpers (locale pinned to Russian).
