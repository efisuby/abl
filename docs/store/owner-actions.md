# Owner actions (cannot be done from the repository)

Mirrors section 3 of `openspec/changes/006-public-release/tasks.md`. Recheck current store rules in the consoles before each step.

1. **Developer accounts.** Google Play Console (one-time fee, identity verification) and RuStore Console (developer verification). Done when both consoles open.
2. **Publish the privacy policy.** Host `docs/store/privacy-policy.md` at a public URL (the app links to `https://github.com/efisuby/abl/blob/master/docs/store/privacy-policy.md`; the repository must be public, or change `about_policy_url` in `res/values/donottranslate.xml` and rebuild). Done when the URL opens without signing in.
3. **Yandex OAuth console.** Check whether publishing/moderation is required for `cloud_api:disk.*`. Add SHA-256 fingerprints:
   - current signing key / upload key: `7B:F2:E4:8A:08:37:E8:38:9E:AA:8F:95:3F:E2:7D:8D:9D:85:CB:7E:3C:5C:4B:F7:F8:A0:44:77:F4:C8:F9:A2`
   - Play App Signing key (Play Console → App integrity)
   - the RuStore signing key, if RuStore re-signs the APK
   Done when sign-in works in an internal-test build from each store.
4. **Console forms.** Data safety, content rating, target audience, foreground-service declarations: use `docs/store/data-safety.md`. Done when the consoles show no policy warnings.
5. **Listing assets.** Text from `docs/store/listing.md`; icon 512×512, feature graphic 1024×500 and screenshots from `docs/store/assets/` (if the folder is missing, see task 2.2).
6. **Closed test and release.** Play requires a closed test with a minimum number of testers for a minimum period (check the current numbers), then production; upload `app-release.aab`. RuStore takes `app-release.apk`. Done when both listings are live.
7. **Back up the key.** Store `~/keystores/abl-release.jks` and its passwords outside the repository (a password manager and an offline copy). Losing it breaks updates and Yandex sign-in.
