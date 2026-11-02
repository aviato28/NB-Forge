# Forge

Every tool. One app. Forge is an all-in-one Android toolbox — PDF, image, video & audio, device
sensors, everyday utilities and files — built with Kotlin and Jetpack Compose. By Naman Bansal
([nbansal28.com](https://nbansal28.com)).

Phase 0 (this) is the foundation: design system, tool registry (84 tools, all *Coming soon*),
home / search / category / settings screens and the in-app updater.

## Updates

Forge is distributed outside the Play Store, through this repository's
[GitHub Releases](https://github.com/aviato28/NB-Forge/releases).

- Every push to `main` makes CI build a **signed** release APK, tagged `v0.1.N` (N = versionCode),
  and publish it as a GitHub Release. Run the workflow manually with *prerelease* ticked for a beta.
- The app checks the public, unauthenticated GitHub API (no token is shipped in the app):
  stable = latest release, beta = newest release including prereleases. It checks at most once a
  day on launch (toggle in Settings) or on demand via **Settings → Check for updates**.
- Updates download in-app, are verified against the release asset's SHA-256 digest, then handed
  to Android's `PackageInstaller`. Android asks once for "Install unknown apps" and confirms each
  install.
- The source is behind an `UpdateSource` interface; switching to the planned
  `forge.nbansal28.com/update/latest.json` endpoint is one constant in `UpdateConfig`.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 37).

```sh
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/
```

Local builds are versionCode 1 / `0.0.1-dev`, so any published release will offer itself as an
update. Release signing only happens in CI, from the repository secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Never commit keystores — this repo is public.

## Docs

- [docs/DECISIONS.md](docs/DECISIONS.md) — product decisions and scope
- [docs/ADDING_A_TOOL.md](docs/ADDING_A_TOOL.md) — how to ship a tool
- [docs/brand/mockups.html](docs/brand/mockups.html) — visual spec
