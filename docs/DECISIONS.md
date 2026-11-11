# Forge — Decisions Log

Source: annotated `Forge-Longlist.pdf` (returned by owner, 2026-11-02).

## Owner answers
| Q | Answer |
|---|---|
| Q1 Platform | Android |
| Q2 Processing | Hybrid (on-device default, server for heavy jobs) |
| Q3 Leveller | D1 Spirit level |
| Q4 Audience | Undecided → build public-ready; no accounts in v1; permissive licences only |
| Q5 Style | Minimal, sleek, modern |
| Q6 Theme | Light + dark with toggle (default: follow system) |
| Q7 Home | Delegated → search-first + category grid + pinned/recent |
| Q8 v1 scope | Delegated → see Roadmap |
| Q9 Missing | Not sure |

## Selected tools
- **PDF:** P1–P43 (all)
- **Image:** I1–I7, I9–I12 (not I8)
- **Video/Audio:** V1–V5, V8, V9 (not V6, V7, V10–V12)
- **Device:** D1–D10 (all)
- **Utilities:** U1–U12 (all)
- **Files:** F1
- **AI:** A1–A4 — later
- **Not selected:** all T, all X, F2–F4, I8

## Proposed (pending owner confirmation)
- Stack: native Kotlin + Jetpack Compose, minSdk 26
- Licences: Apache/MIT/BSD only; no AGPL (MuPDF, iText, Ghostscript)
- APK built by GitHub Actions on every push

## Confirmed 2026-11-02
- Accent: Ember Orange `#FF6A1A`
- minSdk: 30 (Android 11)
- v1 scope: as recommended (P1–P5, P9, P17, P20, P21, P27, P29–P32, P37, P40, P43, I1–I3, I5, D1, D6, D8, U1, U3)
- Mockups: static, incl. icon/branding/website hero

## In-app updater (Phase 0 — built first, it is the delivery channel)
- CI (GitHub Actions) builds a **signed release APK** on push to main, bumps versionCode, publishes a GitHub Release with the APK attached.
- Repo `aviato28/NB-Forge` is **public** → APKs are published as GitHub Releases on **this same repo**. App calls the unauthenticated `releases/latest` API. **No token shipped inside the app** — any token in an APK can be extracted.
- App: Settings → "Check for updates" + silent check on launch (max once/day). Shows changelog, downloads APK, hands off to Android `PackageInstaller`. User grants "Install unknown apps" once; Android still shows a confirm tap per update (no silent installs without device-owner).
- Signing keystore stored as a GitHub Actions secret + offline backup. Losing it = users must uninstall to update.
- Single build variant, updater always on. (Play Store deferred.)

## Confirmed 2026-11-02 (round 2)
- App icon: **B — Anvil** (charcoal #18181B tile, ember anvil + spark)
- Look approved as mocked.
- Naman Bansal branding to be incorporated (details pending from owner; placeholder "by Naman Bansal" in splash/About/website footer).
- Single repo for source + releases.
- applicationId: `com.namanbansal.forge` (permanent once on Play).
- Releases cut on push to `main`; dev branches get build-only CI.

## Distribution (confirmed 2026-11-02)
- Distribution = **project website + in-app updater only**. Play Store is the very last step (~6+ months out); do not design for it now.
- Website: GitHub Pages from this repo (planned phase), "Download for Android" → latest release APK.
- NB branding: owner has no assets yet. Placeholder "Forge by Naman Bansal"; an NB mark can be designed alongside the website.

## Hosting & updates — aligned with nbansal28.com (2026-11-02)
- Main site repo `aviato28/nbansal28-site` (Cloudflare Worker + static assets). Products live on subdomains (penumbra., crewbrew., afterglow., lifeos.nbansal28.com), each a folder in that repo, plus an F-PLN card on the home page.
- Forge will be **forge.nbansal28.com** (site work = later phase, not now).
- Follow Penumbra's update contract: `GET /update/latest.json?channel=stable|beta` →
  `{version, versionCode, channel, notes, publishedAt, apk, size, sha256}`; `/download/apk` for the website button.
  Tag format `v0.1.{N}` where N = versionCode. Stable = GitHub "latest" release, beta = newest incl. prereleases.
- Phase 0 app: `UpdateSource` interface. Implementation now = GitHub Releases API on aviato28/NB-Forge (public, no token), mapped into the same schema. Later swap to forge.nbansal28.com endpoint (one-line change, ships via an update).
- About screen links to https://nbansal28.com.

## Workflow (2026-11-02)
- Owner has authorised Claude to open PRs, merge to `main` and publish releases autonomously.
- Signing secrets are configured in GitHub. First release: v0.1.104.

## OCR / ML (decided 2026-11-05, owner delegated)
- Use **Google ML Kit** for OCR (P37, text recognition, bundled model, works offline), document scanning (P43), barcode/QR (D8) and background removal (I4, subject segmentation).
- Rationale: much better quality than Tesseract, on-device and free, and its terms don't force us to open-source Forge (unlike the AGPL libraries we avoid), so a paid tier stays possible.
- The licence rule is now: Apache/MIT/BSD/OFL, or free proprietary SDKs with no copyleft (ML Kit only so far).
- Prefer bundled models over Play Services-delivered ones where both exist, so they work offline and on de-Googled phones. The document scanner needs Play Services: detect that, and if it's missing, fall back to a plain camera capture + manual crop.

## Parallel tracks (2026-11-11, owner approved)
- PDF track: branch `ccr-3c0bfd4d-pvi6u0`, worktree /home/user/NB-Forge (chunks A → B → C).
- Everything-else track: branch `forge-track-2`, worktree /home/user/NB-Forge-t2 (chunks D → E → F → G).
- Each chunk: PR to main, squash merge, release. Sync the other branch with main after each merge.
