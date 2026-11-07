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

### Build Android APK (`.github/workflows/build-apk.yml`)
On every push to main/master, every PR and on demand: runs unit tests, builds the debug APK
and uploads it as the `clearit-debug-apk` artifact (plus test reports).

### Release (`.github/workflows/release.yml`)
Publishes **GitHub Release `v<versionName>`** with `clearit-v<versionName>.apk` and its
SHA-256 checksum. Release notes come from `docs/releases/v<versionName>.md` (falls back to
`CHANGELOG.md`). It runs when:

1. a tag `v*` is pushed (the tag must match `version.properties`), or
2. main/master is pushed and **no release exists yet** for the current version — so merging
   a branch that bumps the version publishes it automatically, or
3. it is started manually (**Actions → Release → Run workflow**); if the release exists its
   APK is rebuilt and replaced.

### Publishing v1.new
Merge this branch into `main` — the Release workflow sees that `v1.new` does not exist yet
and creates it. Alternatively push the tag: `git tag v1.new && git push origin v1.new`.
