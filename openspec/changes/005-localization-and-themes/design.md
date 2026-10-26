# Design

## D1. Theme
Parent `Theme.Material3.DayNight`; light roles in `values/colors.xml`, dark roles in `values-night/colors.xml`, same names. The mode is stored in DataStore as `theme_mode` (`SYSTEM|LIGHT|DARK`), device-local: not added to `SyncableSettings.keys`. `AppCompatDelegate.setDefaultNightMode` is applied in `AblApplication` and on change.

## D2. Language
Default resources are Russian; `values-en` holds English. The per-app language uses `AppCompatDelegate.setApplicationLocales` with `locales_config.xml` for the system picker on 33+. The `language` setting (`SYSTEM|ru|en`) is device-local. Plurals need the right forms in both languages.

## D3. Tests and checks
UI tests pin the locale to `ru` in `UiTestSupport` (they match Russian text). Lint `MissingTranslation`/`ExtraTranslation` are errors. Screenshots of all screens in ru/en × light/dark are taken on the emulator (`adb shell cmd uimode night`, app locale) and reviewed.

## D4. Light palette contrast
The amber accent on a warm light surface needs a darker amber for text and buttons to keep contrast at 4.5:1 or more; checked per role.
