# Tasks

## 1. Themes

- [x] 1.1 Switch to `Theme.Material3.DayNight`, add light colors and `values-night`, drop the forced `MODE_NIGHT_YES`; verify with screenshots of every screen in light and dark on the emulator and a contrast check of text roles
- [x] 1.2 Add the `theme_mode` setting (device-local) and the settings control; verify with a unit test that the key is not syncable and an instrumented test that the choice persists and applies

## 2. Languages

- [x] 2.1 Move existing strings to a complete English `values-en` including plurals and format strings; verify `lintDebug` has no MissingTranslation/ExtraTranslation
- [x] 2.2 Remove the forced `ru` locale, add `locales_config.xml` and the language setting; verify with an instrumented test that switching to English changes a visible string and persists
- [x] 2.3 Pin the locale to `ru` in `UiTestSupport` and add English smoke tests for library and settings; verify the whole existing suite stays green

## 3. Finish

- [x] 3.1 Screenshot review of all screens in ru/en × light/dark on the emulator; verify no clipped text and no low-contrast element
- [x] 3.2 Update README and AGENTS.md (remove the "dark-only, Russian-only" rule); verify `openspec validate 005-localization-and-themes --strict` and the full test run green
