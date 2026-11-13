# Adding a tool

Every tool is already registered in `app/src/main/java/com/namanbansal/forge/tools/ToolRegistry.kt`
(code, name, category, group, description, keywords, icon). Until it ships, `tool/{code}` shows the
shared **Coming soon** screen. **P4 Rotate is the reference implementation: copy it.**

| Piece | File |
|---|---|
| Screen | `tools/pdf/RotateScreen.kt` |
| State + the actual work | `tools/pdf/RotateViewModel.kt` |
| Pure operation (unit-tested) | `core/pdf/PdfRotator.kt` |
| Tests | `core/pdf/PdfRotatorTest.kt`, `ui/RotateScreenTest.kt`, `ui/RotateViewModelTest.kt` |

## Steps

1. **Operation** — put the real work in a plain Kotlin object under `core/` (no Compose, no ViewModel),
   e.g. `PdfRotator.rotate(source, out, ...)`. It takes `File`/streams, a `checkCancelled: () -> Unit` and an
   `onProgress: (Float) -> Unit`, and throws `ToolException("friendly message")` for expected problems.
   Unit-test it under Robolectric against fixture files generated in test code (`core/PdfFixtures.kt`).
2. **ViewModel** — extend `ToolViewModel` (any input) or `PdfToolViewModel` (one PDF: gives you a private
   working copy, password prompt/retry, and `thumbnails`). Override:
   - `toolCode` — the registry code (`"P4"`).
   - `onInputsReady(files)` / `onPdfReady(source)` — inspect inputs; throw `ToolException` to reject them
     (the user lands back on Pick with that message).
   - `execute(scope: RunScope): ToolOutcome` — do the work. Use `scope.progress(0..1)`, `scope.checkCancelled()`
     between units of work and `scope.save("action", "ext", "mime") { out -> ... }` which writes
     `<original>_<action>.<ext>` into `Downloads/Forge/` (de-duplicated, deleted again if you fail or are cancelled).
     Return `ToolOutcome(saved, headline, detail)`.
   Keep all option state (selection, sliders...) in `StateFlow`s here so it survives rotation.
3. **Screen** — one call to `ToolScaffold`, which gives you the whole Pick → Options → Run → Result flow:
   ```kotlin
   @Composable
   fun RotateScreen(host: ToolHost, vm: RotateViewModel = viewModel()) {
       ToolScaffold(
           tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
           pick = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to rotate"),
           actionLabel = "Save", actionEnabled = changed > 0, actionHint = "...",
           inputSubtitle = { "12 pages · 4.2 MB" },
           optionsReady = source != null,
           dialogs = { prompt?.let { PasswordDialog(it, vm::submitPassword, vm::cancelPassword) } },
       ) { /* your options UI; this is a ColumnScope with weight(1f), so a LazyVerticalGrid can fill it */ }
   }
   ```
   The scaffold handles: SAF picker, "Opening…", the input file card with Change, the primary button,
   progress + Cancel (cooperative, also bound to system Back), friendly error + **Try again**, the result page
   (success check, before → after, output card, Share / Save as / Open, haptic tick, "Next" chips) and snackbar messages.
   Pass `PickSpec(..., multiple = true)` for multi-file tools.
4. **Map it** — add one entry to `ToolScreens.screens`: `"P4" to { host -> RotateScreen(host) }`.
5. **Flip the status** — add the code to `AVAILABLE` in `ToolRegistry`.

That's it: search, category lists, pins, the `tool/{code}` route, the "Open with…" share sheet and the
"Next" chips on other tools' results all pick it up automatically.

## What the registry drives

- `accepts` (derived per category, overridable in `ACCEPTS_OVERRIDES`) = which file kinds a tool takes. A file shared to Forge
  from another app opens a sheet listing **Available** tools that accept it; "Next" chips on a result list Available
  tools that accept the output. Tapping either opens the tool **with the file already loaded** (`PendingFiles`).
- `shortLabel` (`SHORT_LABELS`) = unambiguous label for pinned tiles/chips ("Compress PDF" vs "Compress image").
  Add one whenever the name alone is ambiguous; `ToolRegistryTest.shortLabelsAreUnique` enforces uniqueness.

## Rules of the road (see `docs/QUALITY.md`)

- Never block the main thread: `execute` already runs on `Dispatchers.Default`; wrap blocking IO in `io { }`/`withContext(IO)`.
- Never read an input uri twice from the UI: `PdfToolViewModel` copies it to a private working file first.
- Outputs are always new files via `scope.save`; never overwrite the input.
- Heavy previews: use `PdfThumbnails` (PdfRenderer + byte-bounded LRU, one page at a time, downsampled) in a lazy grid.
- Encrypted PDFs: handled for you by `PdfToolViewModel` (prompt, retry, wrong-password message). The working copy is decrypted,
  so say so in the UI if the saved output will have no password (Rotate shows a note).
- Process on-device by default; keep dependencies permissively licensed (Apache/MIT/BSD). No AGPL.
- Need a new icon? Add a 24×24, 1.75-stroke line icon to `ui/icons/ForgeIcons.kt`.
- Don't rename codes — they are stored in users' pins.
- Test the operation (edge cases: encrypted, 1 page, 300 pages, mixed sizes, corrupt, cancel) and add a Compose smoke test
  for the screen states, as `RotateScreenTest` does.

## Shared building blocks

`core/`: `FileKind`, `PickedFile`/`FileResolver`, `OutputNaming`/`OutputSaver`, `FileActions` (share/open),
`RecentFilesStore`, `ToolViewModel`, `NextSuggestions`, `IncomingIntents`, `core/pdf/` (`PdfLoader`, `PdfThumbnails`, `PdfToolViewModel`).
`ui/toolkit/`: `ToolScaffold`, `PasswordDialog`, `OpenWithSheet`, `rememberFilePicker`, `ForgeSnackbarHost`/`rememberSnack`.

## Added in Batch 2

- `core/pdf/PdfAssembler` builds any new PDF from pages of existing ones (order, duplicates, turns, blanks): Extract, Split, Page manager and Insert all use it. `PdfMerger` (PDFBox merger, temp-file mode) backs Merge.
- `core/pdf/PageRanges` parses `1-3, 5, 8-10`; `PageEditor` is the Page manager's undo/redo page-index model.
- Passwords: `PdfLoader` decrypts into a working copy and records a `PdfProtection`; every tool writes its output through it (`PdfSource.protection`, `PdfProtection.applyTo`). Mixed sources use `PdfProtection.forAll`.
- Option state that should survive process death: `persistedInt/Bool/String/IntSet/persisted(...)` in `ToolViewModel` (reset when new files are picked, kept after a restore: check `stateRestored`).
- Many outputs: return `ToolOutcome(outputs = ..., archive = zip)`; the result screen offers Share all / Share ZIP and rolls back saved files if a run fails or is cancelled.
- UI: `PageThumb` / `PageSelectGrid`, `ReorderableGrid` / `ReorderableColumn` (drag to reorder), `ForgeTextField`, `Stepper`, `ToggleRow`, `InfoNote`, `OptionChip`.

## Page picking (Round 1A)

Anything that asks the user for pages should show them, not ask for page numbers:
- `ui/toolkit/PagePicker` = selection grid (tap, drag across a run, long-press peek, All/None/Invert) plus an optional range field in two-way sync (`core/pdf/PageSelectionSync`: keep the selection set and the text in the ViewModel, both persisted).
- `PagePickerGrid` is the lower-level lazy grid: pass `GridPage`s (tint, badge, dimmed, blank, gap ids) and optional `GridGaps` for tappable gaps (Split's cuts, Insert's insertion point), plus `header`/`footer` via `fullWidthItem`.
- `PagePeek` is the full-screen page view (swipe between pages, swipe down or Back to close); the grid opens it on long-press, Merge and the Page manager open it directly.
- `core/pdf/SplitGroups` (cuts -> files, colour owners) and `core/pdf/InsertPreview` (in-place preview list) are the pure models behind Split and Insert.

## Screenshots

`./gradlew recordRoborazziDebug --tests '*ScreenshotTest'` writes light/dark PNGs of every screen to `docs/screenshots/`
(Roborazzi, Robolectric native graphics, 411x891dp). Plain `testDebugUnitTest` renders the same screens but writes nothing.
Look at the PNGs after UI changes. Robolectric's PdfRenderer draws nothing, so tests swap in `PdfBoxRasterizer`.

## The viewer (Round 1B)

`tools/pdf/Viewer*.kt` is the app's PDF reader and the hub for Round 2 editing. Pieces worth reusing:
- `core/pdf/viewer/TileRenderer` + `TileCache` + `TileMath`: sharp zoom. The UI says which tiles it wants (`setWanted`); one background thread renders them with `PageRasterizer.renderTile`, results sit in a byte-bounded LRU that never evicts visible tiles. `SlotLayout` sizes pages (single / spread, fit width / page) and is shared by scrolling, tiles and hit-testing.
- `PdfDocIndex`: lazy per-page text boxes (`PageText`), link rectangles (`PageLink`) and the outline; all coordinates are fractions of the displayed page, so they are valid at any zoom and rotation.
- `ViewerPositionStore` (recent list + last page/zoom per document, key = hash of the first 64 KB + size) and `ViewerPrefs` in `SettingsRepository` (global layout choices).
- `DocumentWriter`: back up the original to `Downloads/Forge/Backups/`, overwrite it, verify, else save a copy. No UI yet; Round 2 calls it.
- Layering for Round 2 modes: `ViewerPages(extraLayer = ...)` draws per page above the tiles and below selection handles; intercept `onTap` in the caller to take over gestures.
- `DefaultPdfViewer` (Settings > "Make Forge your PDF viewer") detects which app opens PDFs and builds the App Info / test intents; the sample PDF is `assets/forge-sample.pdf`.

## Edit mode (Round 2C: P16 Add content, P18 Whiteout, P19 Redact)

All three open the viewer in Edit mode (`ViewerScreen(startMode = EDIT, startTool = ...)`); the tool chosen is only preselected, the Edit strip has all of them.
- `core/pdf/edit/EditModel`: `EditObject` (text / image / shapes / whiteout, page fractions), `RedactMark`, `EditSession` (undo / redo across both), `EditJson` + `EditPendingStore` (survive process death), `FieldLabels` (field sheet titles).
- `EditIo` appends objects to the page content stream (`PDPageContentStream` APPEND + `resetContext`): standard-14 fonts, image XObjects (JPEG passes through, PNG keeps its alpha as an SMask), shapes with `/ca` fill opacity. `EditImageStore` keeps the pictures in `filesDir/edit-images` until saved.
- `Redactor` is real redaction: it parses the content stream, tracks the text matrix / graphics state, splits `Tj`/`TJ`/`'`/`"` so the kept text does not move, re-encodes pictures with the area blacked out (or drops them), drops vector paths fully inside, recurses into Form XObjects copy-on-write, deletes annotations / widgets inside, draws black boxes and, on request, strips Info + XMP. `redactInPlace` verifies the result with a text re-extraction (no glyph centre inside an area, removed strings gone) and throws `ToolException` (the save is aborted, the original untouched) otherwise. `RedactSearch` holds the presets (email, phone, long numbers / cards, dates) and term search.
- `tools/pdf/EditController` (PageInput: select, move, resize, draw, marks, search), `EditMode` (overlay) and `EditUi` (strip, option rows, search review, apply dialog) plug into `ViewerMode` like Annotate and Fill & Sign. Saving goes through `AnnotSaver`'s `extra` hook: Fill & Sign -> Edit objects -> redaction, then the usual backup / overwrite / copy. Marks can only be saved through the Apply dialog.

## Edit existing (Round 2D, P15)

`core/pdf/edit/content/`: `ContentWalker` scans / rewrites a page's content stream (glyphs, pictures, painted paths get a stable index; Form XObjects copy-on-write), `TextBlocks` groups glyphs into lines and paragraphs, `TextReflow` (`TextFonts.choose` reuses the block's font when every glyph exists, else a standard-14 relative or bundled Noto; `TextLayout` re-wraps with real metrics), `ExistingIndex` serves the viewer. `core/pdf/edit/ExistingApplier` writes `ExistingEdit`s (in `EditState.existing`) on save, after Edit objects and before redaction. UI: `ExistingController`, `ExistingLayer`, the "Existing" tab in `EditUi`. Known limits: Type 3 / outlined / rotated text and scanned pages are not editable; mixed styles inside a paragraph become one style; fully embedded Type0 fonts only reuse characters already on the page.

## Save & share (Round 2E)

`core/share/`: `FileOrigins` (is this uri a temporary hand-over from another app, or a real file on the phone), `ShareStaging` (copy to `cache/share/<n>/<real name>` behind the FileProvider, so other apps get the true name and a read grant), `PdfPrintAdapter` (Android print service), `PdfInfo` / `FileInfoRows` (file-info sheet), `PageSharer` (selected pages as a PDF, one page as PNG), `KeptCopies` (size-capped private copies so temporary PDFs reopen from Recent; Settings toggle). `OutputSaver` gained `saveKeepingName`, `saveFromUri`, `rename`, `deleteFile`, `writeTo`; `FileActions.openWith` is a chooser that excludes Forge. `AnnotSaver.save(target = SaveTarget...)`: `Original` (backup + overwrite, long-standing), `Downloads`, `Chosen` (Save as); `render` builds the edited file without storing it (Share / Print / Open with "with your changes"). In the viewer, `requestSave` asks `needsSaveLocation()` first and shows `SaveWhereSheet` when the original can't be replaced. Reuse `RecentActions` / `RecentMenuItems` for any file row.

## Edit-existing coverage (Round 2F)

`GlyphRecovery` reads text back from an embedded TrueType font's cmap / glyph names when the PDF has no ToUnicode; what is still unknown becomes a replace-only block (`Glyph.unmapped`, `TextBlock.unreadable`). `TextBlock.styles` / `runsOf` keep per-character fonts and colours; `TextPlanner` + `layoutStyled` rewrite a mixed-style paragraph keeping the unchanged parts' styles (`StyleMap`). `PageScan.skew` is the page's tilt (under about 2 degrees): glyph/block coordinates are in the straightened working frame, `toPage` maps back, and rewritten text is written with the same tilt. `PageObjects.locked` (`LockReason`), `freeText` and `ExistingIndex.debugBoxes` drive the "Why can't I edit this?" sheet, outlines for comment boxes / form fields (tap routes to Annotate / Fill & Sign) and Settings > Developer overlay. Test fixtures: `CoverageFixtures`.

## Chunk B building blocks (the remaining PDF tools)

- `core/pdf/PdfTransformViewModel`: "one PDF in, one PDF out" in one override. Implement `action`, `validate`, `transform(doc, source, ctx)` (returns the result detail); it loads with temp-file memory, keeps the input's protection (`protectionFor`, Unlock/Protect override it), saves through `scope.save`, and offers `previewPage` for live previews. `PdfSelectionViewModel` adds the page selection (grid + range text, persisted).
- `core/pdf/PdfPreview` renders the *real* operation on one page into a temp one-page PDF: `LivePreview` + `PreviewPageRow` (in `ui/toolkit/PdfToolScreen.kt`) show it. `PdfToolScreen` is `ToolScaffold` pre-wired for a one-PDF tool (password dialog, subtitle, protection note, scrolling options); `PagesChooser`, `ColorDots`, `PositionGrid` are the shared option widgets.
- `PageGeometry` maps "bottom centre of what is displayed" to the page's own coordinates (rotation and crop box), so anything that stamps text or links must use it.
- `PdfImagePass` (placement scan, downsample, re-encode, gray, dedupe) backs Compress and Grayscale; `PdfGrayscale.rewrite` rewrites colour operators; `PdfSanitizer`, `PdfBookmarks` (flat list with levels + pure edit ops), `PdfLinks`, `PdfImposer`/`ImposePlan`, `PdfCropper`, `PdfWatermarker`, `PdfStamper`, `PdfMetadataEditor`, `PdfFlattener`, `PdfRepair` are plain, unit-tested objects.
- Tests: `RenderProbe` (pixel + text probes), `ImageFixtures`, `SanitizeFixtures`; `ChunkBEndToEndTest` runs every tool through its view model, `ChunkBScreensTest` / `ChunkBScreenshotTest` cover the screens.

## Video / audio tools (Chunk F)

Extend `tools/video/VideoToolViewModel` instead of `ToolViewModel`: it probes the file (`VideoInfo`: duration, upright size, codecs, rotation, HDR), loads a poster frame, and runs `buildJob(...)` as a background job (`core/video/VideoJobs` + `VideoJobService`: progress notification, Cancel, survives leaving the app; a returning screen re-attaches through `ToolViewModel.resumedRun`). Inside the job use `VideoOps.exportAndSave(exporter, ...)` (Transformer -> temp file -> `Movies/Forge` or `Music/Forge`). Describe what to do with an `ExportRequest` (only set what changes so Transformer can remux); the planners in `core/video/Planners.kt` hold the testable decisions. In tests swap `ForgeApp.mediaExporter` / `mediaProbe` / `frameSourceFactory` and `FrameThumbs.provider` for fakes (see `video/VideoFakes.kt`), and wrap screens in `LocalVideoPreview provides false` (ExoPlayer cannot render under Robolectric).
