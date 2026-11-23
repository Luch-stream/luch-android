# Updates

## Which build updates itself

| Build (flavor) | Where it is published | Who updates it |
|---|---|---|
| `github` | `luch-release.apk` on the releases of [ALS](https://altlinux.space/Luch/luch-android/releases) and [GitHub](https://github.com/Toxblh/luch-android/releases) | Luch itself, from those releases |
| `fdroid` | F-Droid | F-Droid. A self-updater is against F-Droid's rules |
| `play` | an app store | the store. Google Play does not allow an app to update itself |

`BuildConfig.SELF_UPDATE` is true only in `github`. The permissions that installing needs (`REQUEST_INSTALL_PACKAGES`,
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`) and `UpdateReceiver` are declared only in `android/app/src/github`, and R8
removes the updater code from the other builds (check with the APK's dex: no `api.github.com` string).

## What the `github` build does

1. A minute after the service starts, then once a day, it asks the ALS release feed
   (`https://altlinux.space/api/v1/repos/Luch/luch-android/releases/latest`) and, if that does not answer, GitHub's
   (`https://api.github.com/repos/Toxblh/luch-android/releases/latest`); no account, no identifiers beyond what any
   HTTP request carries. The feeds are `BuildConfig.UPDATE_FEEDS` (`-PluchUpdateFeeds=a|b` changes them).
2. It takes a release only if it is final (not a draft or pre-release), its version is higher than the app's, and it
   has an asset named exactly `luch-release.apk` with a SHA-256: GitHub's `digest` of the asset, or, on ALS (Forgejo has none), the file `luch-release.apk.sha256` published next to it.
3. The download must match that digest, and the APK must be signed with the same key as the running app. The system
   installer checks the key again.
4. Installing:
   - **The person presses "Update to X" on the status screen.** The first time, Android asks whether Luch may install
     apps (a settings page; on TVs without one the screen explains where to allow it) and then asks to confirm the
     update.
   - **In the background**, only when the system will not ask anything (Android 12+, Luch already being the app that
     installed itself, allowed to install apps) and nothing is playing, mirroring or measuring. If the system wants
     to ask anyway, the background update is dropped rather than popping up over a film; the status screen still
     offers it.
5. After the update Android restarts the app; `BootReceiver` starts the service again (`MY_PACKAGE_REPLACED`).

Failures worth knowing about (a digest or key mismatch, the installer refusing) become crash and error reports
(PROTOCOL.md, section 6); a network error is only logged.

## Releasing

- CI (`.forgejo/workflows/build.yml` on ALS, `.github/workflows/build.yml` on GitHub) signs `luch-release.apk` with the release key from the repository secrets `LUCH_KEYSTORE_BASE64`,
  `LUCH_KEYSTORE_PASSWORD`, `LUCH_KEY_ALIAS`, `LUCH_KEY_PASSWORD`. **Every release must be signed with that same
  key**: Android installs an update only over an APK with the same signature. If the key is lost, installed copies
  can never be updated again; keep a copy of it outside GitHub.
- Without the secrets CI publishes `luch-release-unsigned.apk` instead, which the updater ignores.
- Raise `versionCode` and `versionName` in `android/app/build.gradle`, tag `vX.Y`, push the tag.
- APKs signed with another key (`luch-debug.apk`, or a release from before the release key, 0.2) cannot be updated
  over: install `luch-release.apk` once by hand (`adb install`; uninstall the old one first if Android refuses).

## Trying an update on an emulator

Debug builds read a release feed of one's own from `files/update-url`, and allow plain HTTP to `127.0.0.1` and to
`10.0.2.2` (the computer running the emulator); release builds always ask GitHub over HTTPS.

```sh
cd android
for v in 2:0.2 3:0.3; do
  ./gradlew assembleGithubDebug -PluchVersionCode=${v%%:*} -PluchVersionName=${v##*:}
  cp app/build/outputs/apk/github/debug/app-github-debug.apk /tmp/feed/luch-${v##*:}.apk
done
# /tmp/feed/latest.json: {"tag_name":"v0.3","draft":false,"prerelease":false,"assets":[{"name":"luch-release.apk",
#   "digest":"sha256:<sha256 of luch-0.3.apk>","browser_download_url":"http://10.0.2.2:8765/luch-0.3.apk"}]}
(cd /tmp/feed && python3 -m http.server 8765 --bind 127.0.0.1) &
adb install /tmp/feed/luch-0.2.apk
adb shell "run-as ru.toxblh.luch sh -c 'mkdir -p files; echo http://10.0.2.2:8765/latest.json > files/update-url'"
adb shell am start -n ru.toxblh.luch/.StatusActivity   # a minute later: "Update to 0.3"
```

`UpdaterTest` (instrumented, `github` build only) covers version comparison, picking the asset, the check against a
local server, and refusing a wrong digest or a foreign signature. The install itself is checked by hand as above,
since installing replaces the app under the running test.
