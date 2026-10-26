# Design

## D1. Distribution builds
Play receives an AAB (`bundleRelease`) signed with the upload key (the current `abl-release.jks`); Google re-signs with its own key. RuStore receives the APK. Each signing certificate has a different SHA-256 fingerprint and every one must be registered in the Yandex OAuth console, or sign-in fails in that store's build.

## D2. Privacy
No analytics, ads or developer server. Data handled: the Yandex token (device only, excluded from backup), sync files in the user's own Disk, the device id. The policy and the Data safety form say exactly this.

## D3. Owner-only actions
Accounts, fees, console forms, hosting the policy and the closed test with its tester quota cannot be done from the repository; they are tracked in tasks as owner actions. Store rules (quota, durations, declarations) must be rechecked in the consoles before submission.

## D4. Licenses
A list of dependency licenses is shown in-app (a static asset maintained in the repository), including Media3, OkHttp, Room, WorkManager, kotlinx-serialization and the Yandex SDK; the Yandex SDK terms for redistribution are checked.
