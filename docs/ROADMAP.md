# Forge — Roadmap

Released: v0.1.121 — foundation, branding, Rotate, Merge, Split, Page manager, Extract, Insert, Viewer (basic).

## Owner decisions (2026-11-03)
- Saving edits made in the viewer: **overwrite the original when Android grants write access, after writing a backup copy** to `Downloads/Forge/Backups/`; otherwise save a new copy. Standalone tools still always create new files.
- Viewer scrolling: **continuous vertical by default**; page-by-page swipe is a setting.
- Viewer is the hub: the standalone tools stay, and some of them also open the viewer in a matching mode.

## Round 1 — Previews everywhere + daily-driver viewer
**1A. Visual page picking (shared `PagePicker`)**
- [x] Thumbnail grid component: tap / drag-select, range text field kept in two-way sync with the grid, long-press = full-screen page peek
- [x] Split: tap the gap between pages to cut; colour-coded groups preview the output files
- [x] Extract: tap pages
- [x] Insert: tap the gap to choose the insert position, with a preview of the pages being inserted
- [x] Merge: expand a file to see its pages
- [x] Page manager uses the same component
- [x] Default pins → Merge, Split, Viewer, Page manager (migrate users still on the old defaults)

**1B. Viewer as default PDF app**
- [x] Sharp zoom (tiled hi-res rendering of the visible area)
- [x] Text select + copy, tappable links (external + internal), outline/bookmarks panel
- [x] Remember the last page per document; viewer home with recent PDFs
- [x] Fit-width, landscape two-page, page-by-page setting, dark pages
- [x] Settings → "Make Forge your PDF viewer" guided setup
- [x] Write-back infrastructure: detect a writable URI, back up the original, then overwrite (used in Round 2)

## Round 2 — Edit inside the viewer
- [x] Annotate: highlight, underline, strike, pen, notes (P17) — Round 2A checklist:
  - [x] core model + PDF round-trip (`core/pdf/annot/*`), session undo/redo, hit-test, smoothing, text snapping + tests
  - [x] save path: AnnotSaver (DocumentWriter), PendingStore, render-copy strip, reload in place + tests
  - [x] ViewerMode interface + mode bar (Read · Annotate · Edit · Fill & Sign · Pages · More), Coming-soon sheet
  - [x] Annotate overlay + gestures (draw, text-snap drag, eraser, note, select) + toolbar/palette/colour picker
  - [x] Selected-annotation bar, note editor sheet, unsaved-changes dialog, Save dot + snackbar
  - [x] P17 Available (opens viewer in Annotate), Compose tests, screenshots (light+dark) incl. saved-page render
  - [x] Final: full gradle check + CI green
- [ ] Edit: add text, image, shapes (P16), whiteout (P18), true redaction (P19)
- [ ] Fill & Sign: fill forms (P25), sign with a saved signature library (P27) — Round 2B checklist:
  - [x] core: form detection (all field kinds, XFA-only), FillIo writer (values + appearances, flatten, FreeText, Stamp / flattened signature), FillSession undo/redo + pending store + tests
  - [x] signature library, image processing (crop, threshold), placement math, OFL fonts + licences + tests
  - [ ] viewer integration: FillSignMode overlay/input/toolbar, field sheets, next/previous field, Add text
  - [ ] sign UI: library, draw pad, type, import preview, placement handles
  - [ ] save options sheet, permissions guard, pending survives process death
  - [ ] P25 + P27 Available, Compose tests, screenshots (light+dark), final gradle check + CI

## Round 3 — Remaining PDF tools
- [ ] Compress (P9), Protect / Unlock (P29/P30), Watermark (P20), Page numbers (P21), PDF→images (P31), Images→PDF (P32) — each with a live preview
