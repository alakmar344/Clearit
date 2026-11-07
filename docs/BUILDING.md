# Building, signing and releasing

## Requirements
- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
  (Android Studio Hedgehog or newer installs these automatically)
- Point the build at the SDK with `ANDROID_HOME` or a `local.properties` file containing
  `sdk.dir=/path/to/Android/sdk` (this file is git-ignored)

The Gradle wrapper (Gradle 8.7) is committed — always use `./gradlew`.

## Common tasks

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest` | Runs engine unit tests and the Robolectric UI test (reports in `app/build/reports/tests/`). The first run downloads Robolectric's Android runtime. |
| `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (package `com.clearit.debug`, installs side by side with release) |
| `./gradlew assembleRelease` | `app/build/outputs/apk/release/app-release.apk` (R8-minified, resource-shrunk) |
| `./gradlew lintDebug` | Android lint |
| `./gradlew installDebug` | Build and install on a connected device |

## Versioning

The version lives in **`version.properties`**:

```properties
versionName=1.new
versionCode=1
```

`versionName` becomes the release tag `v<versionName>` (here **`v1.new`**). For the next
release bump both values (versionCode must increase for Android to accept the update).

## Release signing

`app/build.gradle.kts` reads the release key from environment variables:

| Variable | Meaning |
|---|---|
| `CLEARIT_KEYSTORE_FILE` | Path to the `.jks` keystore |
| `CLEARIT_KEYSTORE_PASSWORD` | Keystore password |
| `CLEARIT_KEY_ALIAS` | Key alias |
| `CLEARIT_KEY_PASSWORD` | Key password |

If `CLEARIT_KEYSTORE_FILE` is unset, release builds are signed with the debug key so the APK
is still installable (fine for testing, but updates between builds signed by different
debug keys require uninstalling first).

Create a key once and keep it safe — losing it means users can't update:

```bash
keytool -genkeypair -v -keystore clearit-release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias clearit
```

### GitHub secrets for the Release workflow

In **Settings → Secrets and variables → Actions** add:

| Secret | Value |
|---|---|
| `CLEARIT_KEYSTORE_BASE64` | `base64 -w0 clearit-release.jks` |
| `CLEARIT_KEYSTORE_PASSWORD` | keystore password |
| `CLEARIT_KEY_ALIAS` | `clearit` |
| `CLEARIT_KEY_PASSWORD` | key password |

## CI workflows

> **Setup note:** the v1 workflows are in [`ci/workflows/`](../ci/workflows/) because the
> tool that pushed this branch can't write to `.github/workflows/` (GitHub requires the
> `workflow` permission). Copy them into `.github/workflows/` once using the GitHub website;
> [`ci/README.md`](../ci/README.md) has the two-minute steps. Until then, the original
> *Build Android APK* workflow keeps running unchanged.

### Build Android APK (`build-apk.yml`)
On every push to main/master, every PR and on demand: runs unit tests, builds the debug APK
and uploads it as the `clearit-debug-apk` artifact (plus test reports).

### Release (`release.yml`): same version, rolling updates
Keeps **one GitHub Release per version**, `v<versionName>` (currently **`v1.new`**), with
`clearit-v1.new.apk` and its SHA-256 checksum attached.

- Every push or merge to main/master rebuilds the APK and **replaces the files on the
  existing release**, creating the release first if needed. The notes are refreshed with
  the build number and commit.
- The version name stays `1.new`. Each build gets
  `versionCode = versionCode × 10000 + workflow run number` (through the
  `CLEARIT_VERSION_CODE` environment variable), so phones install every new APK as an update.
- Pushing a tag `v*` (must match `version.properties`) or **Actions → Release → Run
  workflow** does the same.
- Release notes come from `docs/releases/v<versionName>.md` (falls back to `CHANGELOG.md`).

To start a genuinely new version, change `versionName` and bump `versionCode` in
`version.properties`; the next push creates a new release next to the old one.
