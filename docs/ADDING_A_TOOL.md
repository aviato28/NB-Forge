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

## Screenshots

`./gradlew recordRoborazziDebug --tests '*ScreenshotTest'` writes light/dark PNGs of every screen to `docs/screenshots/`
(Roborazzi, Robolectric native graphics, 411x891dp). Plain `testDebugUnitTest` renders the same screens but writes nothing.
Look at the PNGs after UI changes. Robolectric's PdfRenderer draws nothing, so tests swap in `PdfBoxRasterizer`.
