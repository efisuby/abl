# ABL — Audio Book Listener

Android-плеер аудиокниг с библиотекой на Яндекс.Диске: книги хранятся в облаке,
скачиваются на устройство для офлайн-прослушивания, прогресс и закладки
синхронизируются между устройствами.

Приложение личное, распространяется сайдлоадом (в Google Play не публикуется).

## Статус

Каркас проекта: собирается, запускается, показывает заглушку. Функциональность
добавляется по милстоунам — см. `openspec/changes/001-android-audiobook-player/`.

## Требования

- JDK 21
- Android SDK: `platforms;android-36`, `build-tools;36.0.0`
- Путь к SDK в `android/local.properties` (файл не коммитится):
  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

## Сборка

```bash
cd android
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # release APK (R8 + shrinkResources)
./gradlew :app:lintDebug
```

APK: `android/app/build/outputs/apk/{debug,release}/`.

## Подпись

Debug и release подписываются одним ключом, чтобы отпечаток сертификата
(SHA-256), указанный в OAuth-приложении Яндекса, не менялся между сборками.

Создайте `android/keystore.properties` по образцу `keystore.properties.example`:

```properties
storeFile=/absolute/path/to/abl-release.jks
storePassword=...
keyAlias=abl
keyPassword=...
```

Без этого файла сборка проходит, но release-APK остаётся неподписанным, а debug
использует стандартный отладочный ключ.

Ключ и пароль нужно хранить в надёжном месте: их потеря ломает и обновления
поверх установленной версии, и вход через Яндекс ID.

## Яндекс.Диск

OAuth-приложение типа «Для авторизации пользователей» с платформой
«Android-приложение». В консоли указываются:

- package name: `com.efisuby.abl`
- SHA-256 отпечаток ключа подписи
- права: `cloud_api:disk.read`, `cloud_api:disk.write`, `cloud_api:disk.info`

`client id` не является секретом и задаётся в `android/app/build.gradle.kts`
(`yandexClientId`). `client secret` приложению не нужен.
