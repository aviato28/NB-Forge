# Forge — Quality Bar (every build agent must follow)

The owner's rule: **whatever ships must work well.** Fewer tools that are solid beat many that are flaky.

## Must never happen
- Crashes. Every tool operation runs off the main thread, catches failures and shows a friendly error with a "Try again" action. No `!!` on anything that can be null from IO.
- ANRs / frozen UI. Heavy work in `Dispatchers.IO`/`Default`, with visible progress and a working Cancel.
- Lost work on rotation, dark-mode switch, or backgrounding. State lives in ViewModels / `rememberSaveable`; picked file URIs survive config change.
- Silent failure. If output can't be produced (encrypted PDF, corrupt file, unsupported type), say exactly why.
- Overwriting user files. Outputs are always new files.

## Files
- Input via Storage Access Framework (`OpenDocument` / `OpenMultipleDocuments`), plus Android share-sheet / "Open with" where the tool supports it. Take persistable permission only when needed.
- Large inputs (100+ MB, 500+ pages) must not OOM: stream, process page-by-page, downsample previews, use `PdfRenderer` for thumbnails with bitmap reuse/LRU cache.
- Outputs saved to `Downloads/Forge/` via MediaStore, sensible names (`<original>_<action>.pdf`, de-duplicated). Also Share and Open actions.

## UX
- Every tool follows the shared flow: **Pick → Options → Run (progress, cancel) → Result (Share / Save / Open, before→after stats, "Next" suggestions)**.
- Sensible defaults: the user should be able to pick a file and tap one button.
- Empty, loading, error states designed, not afterthoughts. Haptic tick on success. Subtle motion (150–250 ms), no gimmicks.
- Match the design tokens and mockups (`docs/brand/mockups.html`). Light + dark both checked.
- Touch targets ≥ 48 dp; content descriptions on icon buttons; text scales with system font size without clipping.

## Testing (no emulator available — KVM absent)
- Core operations live in plain Kotlin classes (no Compose) and are unit-tested under **Robolectric** against generated fixture PDFs/images (create fixtures in test code; don't commit large binaries).
- Test edge cases: encrypted input, 1-page input, corrupt input, huge page count (generate 300 pages), mixed page sizes, cancel mid-way.
- Compose UI smoke tests under Robolectric for each tool screen (renders, primary button enabled after pick, error state shows).
- `./gradlew testDebugUnitTest assembleDebug lintDebug` must pass locally AND in CI before you finish. Fix lint errors; don't suppress without a comment why.

## Code
- Only Apache/MIT/BSD deps. PDF engine: `com.tom-roush:pdfbox-android` (Apache-2.0) + Android `PdfRenderer` for rendering.
- Follow `docs/ADDING_A_TOOL.md`. Shared building blocks go in shared packages, never copy-pasted between tools.
- Commit + push after each logical chunk (container can restart).
