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
- APKs are published to a **separate public releases repo** (source repo stays private). App calls the unauthenticated `releases/latest` API. **No token shipped inside the app** — any token in an APK can be extracted.
- App: Settings → "Check for updates" + silent check on launch (max once/day). Shows changelog, downloads APK, hands off to Android `PackageInstaller`. User grants "Install unknown apps" once; Android still shows a confirm tap per update (no silent installs without device-owner).
- Signing keystore stored as a GitHub Actions secret + offline backup. Losing it = users must uninstall to update.
- Build flavours: `github` (with updater) and `play` (updater stripped — Play policy forbids self-updating).
