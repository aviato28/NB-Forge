# Adding a tool

Every tool is already registered in `app/src/main/java/com/namanbansal/forge/tools/ToolRegistry.kt`
(code, name, category, group, description, keywords, icon). Until it ships, `tool/{code}` shows the
shared **Coming soon** screen. To ship one:

1. **Write the screen** — a Composable that takes a `ToolHost` (gives you the `ToolDef` and `onBack`),
   e.g. `tools/pdf/MergeScreen.kt`. Reuse `TopBar`, `ForgeCard`, `ForgeButton` etc. from `ui/`.
2. **Map it** — add one entry to `ToolScreens.screens`:
   ```kotlin
   "P1" to { host -> MergeScreen(host) },
   ```
3. **Flip the status** — add the code to `AVAILABLE` in `ToolRegistry`.

That's it: search, category lists, pins and the `tool/{code}` route pick it up automatically.

Notes
- Process on-device by default; keep dependencies permissively licensed (Apache/MIT/BSD). No AGPL.
- Need a new icon? Add a 24×24, 1.75-stroke line icon to `ui/icons/ForgeIcons.kt`.
- Don't rename codes — they are stored in users' pins.
