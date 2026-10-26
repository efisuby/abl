# Data safety answers (Google Play) and RuStore data declaration

Source of truth: `openspec/changes/006-public-release/design.md` D2. Recheck the wording in each console before submitting.

## Google Play — Data safety form

| Question | Answer | Reason |
|---|---|---|
| Does the app collect or share user data? | **No data collected by the developer** | No developer server, analytics or ads. Data moves only between the user's device and the user's own Yandex Disk. |
| Is all data encrypted in transit? | Yes | HTTPS only (Yandex APIs). |
| Can users request data deletion? | Yes, in the app and by uninstalling | Sign-out removes the token; delete removes books; the user owns the Disk files. |
| Data sharing with third parties | None by the developer | Yandex is the user's own storage provider chosen by the user (Yandex ID sign-in). |

Notes for the form: the Yandex token and device id never leave the user's control; if the console asks about "account info" or "app activity", the app handles them on-device and in the user's Disk only, not collected by the developer.

## Other Play declarations

- **Ads:** none. **App category:** Books & Reference (or Music & Audio). **Target audience:** 13+/18+ (not for children).
- **Content rating questionnaire:** no violence, no user-generated content shared with others, no purchases.
- **Foreground services (Android 14+):**
  - `mediaPlayback` — `PlaybackService`: listening with the screen off, lock-screen and notification controls.
  - `dataSync` — download/upload workers: long book transfers with a progress notification.
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE (+ the two types above), POST_NOTIFICATIONS (requested on Android 13+; playback works if denied).
- **App access:** all local features work without an account; the cloud needs a Yandex account — give reviewers a test account if the console requires it (owner action).

## RuStore
Same answers: no data collection by the developer, no ads, no analytics SDKs. Provide the privacy policy URL, the APK signed with `abl-release.jks`, and the age rating (18+ or 12+, per the console questionnaire).
