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

## Chunk E: image tools (2026-11-12)
- **I7 Upscale ships as "Enhance (2x/4x)"**: Lanczos-3 resampling plus an optional unsharp mask, labelled honestly as classic resampling, not AI. No permissively licensed super-resolution model small enough (~15 MB) was found that we could verify and bundle, so none is shipped. Output is capped at 30 MP for memory safety.
- **I4 Background remover**: ML Kit Subject Segmentation (beta, Play-services delivered) first, ML Kit Selfie Segmentation (bundled, offline, people only) as fallback.
- **AVIF**: decoded everywhere the platform can, but written only if `Bitmap.CompressFormat` has an AVIF entry (none as of API 37), so the option is hidden today.
- **Licences added**: Anton (OFL 1.1), AndroidSVG 1.4 (Apache-2.0), ML Kit face detection / segmentation (free SDK terms, same rule as the other ML Kit use).
- **Metadata stripping** never re-encodes: JPEG segments, PNG chunks and WebP chunks are rewritten; "location only" on PNG/WebP removes all EXIF/XMP (it cannot be edited safely).

## Chunk B: remaining PDF tools (2026-11-13)
- **P14 is "Optimise", not "Linearise".** PDFBox 2.0.27 (the only Apache-licensed engine we ship) can neither write linearised files nor object streams (that arrived in PDFBox 3). A real "fast web view" would need its own writer, so the tool is honestly named *Optimise (compress streams + clean up)*: it Flate-compresses raw streams, drops unreferenced objects and rewrites the xref. The screen says plainly that it is not fast web view. Code `P14` is unchanged (pins).
- **Compress** downsamples to the painted size (placement found by running the content stream through PDFBox's engine), re-encodes to JPEG only when that is smaller, merges identical pictures (soft masks included in the comparison), Flate-compresses raw streams and lets PDFBox's save drop unreferenced objects. The size estimate really re-encodes up to 8 sampled pictures and extrapolates; text-only files estimate close to their current size. If the result is not smaller than the input the original is saved unchanged and the result says so.
- **Grayscale** pictures are converted with a Rec.601 colour matrix and stored as JPEG (3-component with neutral chroma: R=G=B on decode); colour operators rg/RG/k/K and sc/scn under RGB/CMYK colour spaces are rewritten. Patterns, shadings and inline images keep their colour (stated in the UI).
- **Protect** uses AES-256 and keeps passwords in memory only (never in saved state): after process death the person types them again. Inputs that were encrypted are re-saved with AES-128 and the same password, as before (`PdfProtection.keyLength`).
- **Unlock** works only with the password the person can already supply (or an empty user password with owner restrictions). No cracking.
- **Crop** sets the CropBox only (lossless), optionally the MediaBox too. **N-up / booklet** use LayerUtility form XObjects, so pages stay vector. Booklet is saddle-stitch, padded to a multiple of 4.
- **Watermark / page numbers** draw in the *displayed* page space (rotation and crop box mapped back via `PageGeometry`), with Standard-14 fonts only (characters outside WinAnsi show as "?").
- **Sanitise** scans first and shows a checklist. Hidden-layer removal deletes the marked content in page and form streams.
- **Live previews** render the real operation on one page into a temp one-page PDF (`PdfPreview`), so what is shown is what is saved.
- Repair has no password prompt: a password-protected file must be unlocked first.

## Chunk F: video tools (2026-11-13)
- **Engine**: AndroidX Media3 Transformer + ExoPlayer (Apache-2.0) for all processing and preview. No FFmpeg (licensing, size).
- **Foreground job**: one video/audio job at a time runs on an app-wide scope (`VideoJobs`) with `VideoJobService` (foreground type mediaProcessing) showing progress + Cancel. A screen that returns re-attaches to the running or finished job. Inputs are read through the original content uri (no private copy), so a one-time share grant that Android revokes after the task ends can stop a job; picked files are fine.
- **Outputs**: video to `Movies/Forge`, audio to `Music/Forge`, GIF to `Pictures/Forge` (MediaStore, via `OutputSaver` + `MediaTarget`). Transformer writes a temp file in the cache first; it is streamed into the media store, so a half-written video never appears in the gallery.
- **WebM**: Transformer's bundled muxers only write MP4, so `WebmMuxer` (framework `MediaMuxer`, WEBM mode) is plugged in as the muxer factory. Offered only if the device has a VP8/VP9 encoder and an Opus encoder. Untested on a physical device (no encoder under Robolectric).
- **Remux**: Trim/Mute/Convert/Extract set only what changes, so Transformer copies streams it can; Trim uses frame-accurate re-cut (no experimental trim optimisation).
- **HDR**: HDR input exported as H.264 is tone-mapped to SDR (OpenGL); exported as HEVC the HDR is kept. Result notes say which happened.
- **GIF**: own median-cut quantiser (15-bit histogram, per-frame palette), Floyd-Steinberg dithering (serpentine) and an LZW writer; frames via `MediaMetadataRetriever` (exact seek). The first second is encoded for real to preview and to extrapolate the file size.
- **Speed**: pitch-corrected audio (Media3 speed provider) up to 2x, audio removed above that.

## Chunk G: NB mark and website (2026-11-13)
- **NB monogram**: geometric N and B drawn as 8-unit rounded strokes in Ember Orange on the anvil icon's charcoal tile, with a short rounded bar underneath that echoes the anvil's foot. Files: `docs/brand/icons/nb-mark.svg` (tile), `nb-mark-mono.svg` (single colour), `res/drawable/ic_nb_mark.xml` (tile, used by `NbMark`).
- Used in Settings → About (replaces the info icon on "Forge by Naman Bansal") and next to "by Naman Bansal" in the home header. The system splash stays the anvil icon alone.
- **Site**: forge.nbansal28.com (branch `forge-site` in aviato28/nbansal28-site, not merged or deployed) serves `/update/latest.json?channel=stable|beta` and `/download/apk` from GitHub Releases of this repo, with the same field rules as `GitHubReleasesSource` (version from tag, versionCode = last tag segment, sha256 from the asset `digest`).
- **`ForgeSiteSource` is NOT switched on yet.** `UpdateConfig.USE_FORGE_SITE` stays `false` until forge.nbansal28.com is live and serving `/update/latest.json`; then flip it in a normal release (the site's `apk` URL is a 302 to the GitHub asset, which the app's downloader follows).
