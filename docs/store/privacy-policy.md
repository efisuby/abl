# Privacy Policy — AudioBook Player (ABL)

Last updated: 2026-10-26 · Политика на русском — ниже.

AudioBook Player ("the app", package `com.efisuby.abl`) is an audiobook player. It has no ads, no analytics, no tracking SDKs and no developer-operated server.

## What the app handles

| Data | Where it lives | Why |
|---|---|---|
| Your audiobook files, covers, titles, authors | On your device, in the app's private storage | Playback and the library |
| Listening progress, bookmarks, playback speed | On your device (private database) | Resume where you stopped |
| Yandex access token | On your device only, encrypted with an Android Keystore key, excluded from backups | Access to your own Yandex Disk after you sign in |
| Sync files (progress, bookmarks, library list, settings, covers) | In **your own Yandex Disk**, folder `Apps/AudioBookPlayer` | Keep several of your devices in step |
| Random device identifier | On your device and in the names of your sync files | Tell your devices apart when merging progress |

## Yandex ID and Yandex Disk
Signing in is optional and only needed for the cloud features. The app asks for Disk read, write and info access. It reads books you choose, writes its own service files and books you upload into its app folder, and never changes or deletes your other files. Data goes directly between your device and Yandex; it passes through no server of ours. Yandex processes it under its own terms: https://yandex.com/legal/

## Google Drive and other file sources
The app imports files through Android's system file picker. It receives only the files you choose and copies them into its private storage. It has no access to your Google account.

## What we do not do
We do not collect, sell or share personal data. We do not use advertising or analytics identifiers. There is nothing to transmit to us, because we run no server.

## Permissions
- Internet and network state: to talk to Yandex Disk and to wait for Wi-Fi when you choose "Wi-Fi only".
- Notifications: playback controls and download/upload progress.
- Foreground services (media playback, data sync): background listening and long downloads or uploads.

## Your control
Signing out removes the token from the device. "Delete" in the app removes a book from the device; files in your Disk are removed only by you. Uninstalling the app deletes all local data. Sync files in your Disk can be deleted in Yandex Disk at any time.

## Children
The app is not directed at children and collects no data from anyone.

## Changes and contact
Changes to this policy appear in this file with a new date. Questions: https://github.com/efisuby/abl/issues

---

# Политика конфиденциальности — AudioBook Player (ABL)

Обновлено: 26.10.2026

В приложении нет рекламы, аналитики, трекеров и сервера разработчика.

**Что хранится.** Книги, обложки, прогресс, закладки и скорость — на вашем устройстве, в закрытом хранилище приложения. Токен Яндекса — только на устройстве, зашифрован ключом Android Keystore, в резервные копии не попадает. Файлы синхронизации (прогресс, закладки, список книг, настройки, обложки) — в **вашем** Яндекс.Диске, папка «Приложения/AudioBookPlayer». Случайный идентификатор устройства — на устройстве и в именах файлов синхронизации, чтобы различать ваши устройства.

**Яндекс.Диск.** Вход необязателен, он нужен только для облачных функций. Данные идут напрямую между вашим устройством и Яндексом, через наши серверы они не проходят (у нас их нет). Приложение читает выбранные вами книги, пишет свои служебные файлы и загруженные вами книги в папку приложения и не меняет остальные ваши файлы.

**Google Drive и другие источники.** Файлы импортируются через системный выбор файлов Android; приложение получает только выбранное и копирует в своё хранилище. Доступа к вашему аккаунту Google у приложения нет.

**Мы не** собираем, не продаём и не передаём персональные данные.

**Разрешения.** Интернет — для Яндекс.Диска и ожидания Wi-Fi; уведомления — управление воспроизведением и ход загрузки; фоновые службы — прослушивание с выключенным экраном и долгие загрузки.

**Ваш контроль.** Выход из аккаунта удаляет токен с устройства; удаление книги убирает её с устройства; удаление приложения стирает все локальные данные; файлы синхронизации можно удалить в Яндекс.Диске в любой момент.

**Дети.** Приложение не предназначено для детей и ничего не собирает.

Вопросы: https://github.com/efisuby/abl/issues
