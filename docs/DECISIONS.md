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
