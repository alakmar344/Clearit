# CI workflow templates

These are the GitHub Actions workflows for Clearit v1. They are kept here, outside
`.github/workflows/`, because the tool that pushes this branch isn't allowed to change files
in `.github/workflows/`: GitHub requires the **`workflow`** permission for that.

| File | Purpose |
|---|---|
| `workflows/release.yml` | Builds a signed release APK and publishes/updates the GitHub Release `v<versionName>` (e.g. `v1.new`). Every push to main **updates the same release** (same version, higher build number). |
| `workflows/build-apk.yml` | Improved CI: uses the committed Gradle wrapper, runs the unit tests and uploads the debug APK and test reports. |

## Enable them (one time, about 2 minutes)

Use the GitHub website, which uses your own permissions:

1. Open the repository on GitHub → **Add file → Create new file**.
2. Name it `.github/workflows/release.yml` and paste the contents of
   [`ci/workflows/release.yml`](workflows/release.yml). Commit to `main`.
3. Open `.github/workflows/build-apk.yml` → ✏️ **Edit** → replace its contents with
   [`ci/workflows/build-apk.yml`](workflows/build-apk.yml). Commit to `main`.

From then on, every push or merge to `main` rebuilds the APK and refreshes release **v1.new**.
You can also run it by hand: **Actions → Release → Run workflow**.

Alternatively, grant the `workflow` permission to the GitHub connection used by your
coding agent, then it can move these files into `.github/workflows/` itself.

## Same version, automatic updates

The version name shown in the app and on the release stays `1.new`. Each release build gets
`versionCode = <versionCode from version.properties> × 10000 + <workflow run number>`, so every
new APK installs over the previous one as an update. To start a new version later, change
`versionName` (and bump `versionCode`) in `version.properties`.
