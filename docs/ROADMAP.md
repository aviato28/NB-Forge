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
- [ ] Edit: add text, image, shapes (P16), whiteout (P18), true redaction (P19) — Round 2C checklist:
  - [x] core: edit model + undo/redo + pending store (`core/pdf/edit/EditModel`), field-label fix (tooltip / humanised name)
  - [x] core: `EditIo` writes text / image / shapes / whiteout into the page content stream + render tests
  - [x] core: `Redactor` (content-stream rewrite, images, forms, annotations, verification), `RedactSearch` presets + tests
  - [x] viewer integration: `EditController` / `EditMode` (select, move, resize, duplicate, front), toolbar, option sheets
  - [x] image import (gallery / camera), whiteout colour picker + eyedropper, replace-with-text
  - [x] redact UI: drag marks, text selection, search & review list, apply-confirm, result report, save path
  - [x] P16 / P18 / P19 Available, Compose tests, screenshots (light+dark), render samples
  - [ ] final gradle check (test, assemble, lint, R8) + CI green
- [x] Fill & Sign: fill forms (P25), sign with a saved signature library (P27) — Round 2B checklist:
  - [x] core: form detection (all field kinds, XFA-only), FillIo writer (values + appearances, flatten, FreeText, Stamp / flattened signature), FillSession undo/redo + pending store + tests
  - [x] signature library, image processing (crop, threshold), placement math, OFL fonts + licences + tests
  - [x] viewer integration: FillSignMode overlay/input/toolbar, field sheets, next/previous field, Add text
  - [x] sign UI: library, draw pad, type, import preview, placement handles
  - [x] save options sheet, permissions guard (save a copy), pending survives process death
  - [x] P25 + P27 Available, Compose tests, screenshots (light+dark)
  - [x] final gradle check (test, assemble, lint, R8) + CI green

## Round 2D — Edit existing content (P15, Acrobat-style)
- [x] Paragraph detection from PageText (lines → blocks; font, size, colour, alignment)
- [x] In-place text editing: remove the old glyphs (Redactor engine), write the new text re-wrapped in the block box
- [x] Font handling: reuse the embedded font when its subset has every glyph, else the closest bundled OFL font (sans/serif/mono) + notice
- [x] Existing images: select, move, resize, replace, delete (content-stream matrix edits)
- [x] Existing vector paths: select + delete
- [x] Tests (re-extract text, pixel-probe renders), screenshots, full gradle gate + CI green

## Round 2E — Save & share everywhere (owner feedback 2026-11-07)
Problem: Forge is now the default PDF viewer, and many PDFs arrive from other apps (Amazon, bank apps, email) as temporary files that aren't saved on the phone. There's no share or save button in the viewer.

**Viewer**
- [x] Share button in the viewer top bar (shares the current file with its real name; with unsaved edits, ask "Share with your changes / original")
- [x] "Save to phone": one tap → Downloads/Forge; "Save as…" → system picker (choose folder + rename)
- [x] Detect temporary files (opened from another app's private storage or a link) and show a slim banner: "Not saved on your phone · Save"
- [x] Print (Android print service; also "Save as PDF" via print)
- [x] Open with… another app
- [x] File info sheet: name, size, pages, where it came from, where it's saved, PDF title/author/producer, encrypted yes/no
- [x] Overflow menu groups these; Share + Save stay visible as icons

**Edits on temporary files**
- [x] When an edit can't overwrite the original, the save sheet asks where to save (Downloads/Forge default, or Save as…) instead of saving silently

**Everywhere else**
- [x] Share-to-Forge sheet (files sent from other apps) also offers "Save to phone"
- [x] Files tab and Recent rows: Share, Save as…, Open with, Rename, Remove from list, Delete file (with confirm)
- [x] Tool result screens: consistent Share / Save as / Open with / Open in viewer
- [x] Page manager / viewer page selection: "Share selected pages" (as PDF) and "Share page as image"
- [x] Recents: offer "Keep a copy" for temporary PDFs so they still open later (app storage, size cap, setting)

**Quality**
- [x] Tests for temp-file detection, save/rename/dedupe, share intents (grant flags), print adapter, file-info parsing; screenshots of viewer bar, banner, save sheet, file info, Files row menu

## Round 2F — Edit-existing coverage (owner feedback 2026-11-11: "some patches it doesn't recognise")
Investigation: ContentScan marks text uneditable for Type 3, vertical, invisible (OCR layer), outlined, rotated, and **no-Unicode-mapping** glyphs; TextBlocks splits paragraphs on any font-family/colour/size change; text in form widgets and annotations isn't page content, so it's never scanned.
- [x] No-Unicode glyphs (common in bank/invoice PDFs): recover text from the embedded font's cmap / glyph names (AGL); if still unknown, allow "Replace text" (remove the exact glyphs, type the new text) instead of locking the block
- [x] Mixed-style paragraphs: merge bold/italic/colour runs into one block, keeping per-run styles on rewrite where the text is unchanged
- [x] Slightly rotated text (scanner skew, <~2°): treat as upright and keep the rotation on rewrite
- [x] Text in form fields / FreeText annotations: show the outline too; tapping routes to Fill & Sign / the annotation editor
- [x] "Why can't I edit this?": tapping an uneditable area shows the specific reason (Type 3 / outlines / scanned → OCR later)
- [x] Debug overlay (Settings → Developer): tints editable vs locked glyphs, to diagnose owner-supplied PDFs
- [ ] Regression fixtures from owner-supplied sample PDFs (non-sensitive) — generated stand-ins for every case are in `CoverageFixtures`; swap in the owner's real samples when they arrive

## Round 3 — Remaining PDF tools
- [ ] Compress (P9), Protect / Unlock (P29/P30), Watermark (P20), Page numbers (P21), PDF→images (P31), Images→PDF (P32) — each with a live preview

## Chunk D — Device tools & utilities (track 2)
- [x] Deps (CameraX, ML Kit barcode bundled, ZXing core), manifest permissions, shared device toolkit (permission gate, sensor interface, lifecycle sensors)
- [x] D1 Spirit level · D2 Ruler · D3 Protractor · D4 Compass · D5 Sound meter
- [x] D6 Flashlight (+ Quick Settings tile) · D7 Magnifier · D8 QR / barcode scanner · D9 Light meter · D10 Vibration meter
- [x] U1 Unit converter · U2 Currency · U3 Calculator · U4 Percent / tip / split · U5 Date calculator · U6 Timezone / world clock
- [x] U7 Timers (foreground service) · U8 Randomiser · U9 Tally · U10 Loan / EMI (+CSV) · U11 BMI / BMR · U12 QR generator
- [x] Unit tests for pure logic, Robolectric smoke tests per screen, screenshots (light + dark)
- [ ] Final gradle gate (test, assemble, lint, R8) + CI green

## Chunk E — Image tools & zip (track 2)
- [x] Shared image core: loader (EXIF, sampled decode), codecs, Pictures/Forge saver, batch results, image ViewModel base
- [x] I1 Resize / crop (+ batch resize)
- [x] I2 Compress (split preview, target size, batch)
- [x] I3 Convert (HEIC, WebP, AVIF, PNG, JPG, ICO, SVG; batch)
- [x] I4 Background remover (ML Kit subject / selfie fallback, refine brush)
- [x] I5 Strip EXIF / location (lossless JPEG, PNG, WebP; batch)
- [x] I6 Colour picker / palette
- [x] I7 Upscale (Lanczos "Enhance")
- [x] I9 Collage
- [x] I10 Text overlay / meme
- [x] I11 Image <-> Base64
- [x] I12 Annotate / blur (+ face detection)
- [x] F1 Zip / unzip (+ open .zip via share)
- [x] Unit tests, Compose smoke tests, screenshots (light + dark)
- [ ] Final gradle gate (test, assemble, lint, R8) + CI green

## Chunk F — Video tools (track 2)
- [x] Deps (Media3 Transformer / ExoPlayer / UI / Effect), OutputSaver media targets (Movies/Forge, Music/Forge), share-in for video/* and audio/*
- [x] Job core: foreground service + job runner, notification state machine (progress, Cancel), survives leaving the app
- [x] Transformer wrapper (trim, compress, convert, speed, mute, audio) behind an interface; encoder-capability query
- [x] V9 Mute
- [x] V1 Trim / cut (preview, frame-thumbnail timeline, handles, remove-middle mode)
- [x] V2 Compress (presets, target size, estimate, HEVC option)
- [x] V3 Convert (MP4 H.264/HEVC, WebM if encoder exists, remux)
- [x] V5 Extract audio (M4A remux/encode, WAV decode)
- [x] V8 Speed (0.25x-4x, pitch-corrected or muted)
- [x] V4 Video to GIF (own LZW + median cut + dithering, preview, size estimate)
- [x] Unit tests, Compose smoke tests, screenshots (light + dark)
- [x] Final gradle gate (test, assemble, lint, R8)
