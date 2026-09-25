# Hermex Android

Hermex Android is a native Kotlin/Compose chat client for the Hermes Agent AI assistant.

## Current state
- v0.1.137, repo `gravol/hermex-android` (PUBLIC since 2026-08-12)
- Dashboard at `http://100.80.204.66:9119` (REST + WebSocket)
- Full architecture in `PROJECT_HANDOFF_CURRENT.md`

## Next step
**Fixes #1 & #2 from field-device QA:** #1 approval-notification-click-kills-in-flight-response (RESOLVED/re-verified 2026-08-22, needs final confirmation), #2 cron check-ins missing (fixed v0.1.118–119, needs live re-verify). See `PROJECT_HANDOFF_CURRENT.md` → "PENDING / NEXT".

## Key files
- `PROJECT_HANDOFF_CURRENT.md` — complete project state
- `app/src/main/java/com/hermex/android/feature/chat/DashboardChatViewModel.kt` — main ViewModel
- `app/src/main/java/com/hermex/android/feature/chat/ChatScreen.kt` — Compose UI
- `core/network/src/main/java/com/hermex/core/network/JsonRpcClient.kt` — RPC client
- `core/network/src/main/java/com/hermex/core/network/RpcNotification.kt` — event types

## File path rules (CRITICAL — read before touching files)
- Kotlin/Java package declarations map to **real filesystem directories**: every `.` in the package name is a separate directory.
- `package com.hermex.core.network` → path `com/hermex/core/network/` (NOT `com.hermex.core.network/`).
- NEVER write a package name as a single folder segment (e.g. `com/hermex.core.network/` is wrong).
- `src/main` uses a **slash**, never a dot: it is `app/src/main/...`, NOT `app/src.main/...`.
- ALWAYS verify a file path exists before reading/editing: run `ls <path>` or `find /home/jeff/HermexAndroid -name '<File>.kt'`.
- NEVER reconstruct a path from memory — if unsure, `find /home/jeff/HermexAndroid -name '<File>.kt'` to get the exact path, then use it verbatim.
- Root of the repo is `/home/jeff/HermexAndroid`. Use paths relative to this root.

## dsh tool return contract (CRITICAL — read before writing JS)
- dsh `tools.bash()` and `tools.read()` return results to the model as **plain text strings**, NOT structured JS objects.
- Do NOT do `const { stdout } = await tools.bash(...); stdout.stdout.text` — there is no `.stdout.text` nested path.
- To inspect a bash/read result, treat the returned value as a string: `const out = await tools.bash({command:"pwd"}); console.log(String(out));` or just `console.log(out)`.
- If you need structured access, parse a string you control: `JSON.parse(String(result))`.
- Never assume a `.stdout.text` / `.stderr` / `{stdout:{...}}` shape — dsh gives you the rendered text directly.

## dsh tool ARGUMENT contract (CRITICAL — every call is an object, never positional)
Every dsh tool call MUST pass a single arguments OBJECT with snake_case keys — NEVER positional args, NEVER a bare string. Use exactly these shapes:

- `read`: `await tools.read({ file_path: "<path or relative>", offset?: <int>, limit?: <int> })`
- `glob`: `await tools.glob({ pattern: "**/*.kt", path?: "<dir, optional>" })`
- `bash`: `await tools.bash({ command: "<shell cmd>", description: "<what it does>", workdir?: "<cwd>", run_in_background?: <bool> })`

**`description` is REQUIRED for `bash` (NOT optional).** Every `tools.bash()` call MUST include BOTH `command` AND `description`. Omitting `description` throws `invalid arguments: missing required property "description"` — a very common failure. ALWAYS write the description.

**Common failure (signature of a wrong call):** error `invalid arguments: "arguments" must be an object` — this means you passed a positional arg (e.g. `tools.read("/path")` or `tools.glob("**/*.kt", dir)`) instead of an object. Fix by wrapping every arg in `{ key: value }`. And `missing required property "description"` means you called `tools.bash` without the required `description` field — add it.
- Paths are resolved by the filesystem backend against the session workspace; an absolute repo path like `/home/jeff/HermexAndroid/...` also works when the backend permits it, but prefer workspace-relative paths.
- `read` returns line-numbered text; `glob` returns matching file paths (never dirs). Treat returned values as strings per the return contract above.

**BEFORE the FIRST call to ANY tool in a session, re-read this entire tool-ARGUMENT contract AND verify every parameter name + whether it is required.** Do not call `tools.bash`, `tools.read`, or `tools.glob` from memory — confirm the exact keys (`command`+`description` for bash, `file_path` for read, `pattern`+`path` for glob) against the session-provided tool schemas first. If a call fails with a schema error, STOP, re-read the schemas, and fix the argument shape — never retry the same malformed call.

## Publishing a release to GitHub + Obtainium (CRITICAL — follow exactly)
A "coding-complete, but release never lands in Obtainium" bug has recurred when an agent pushes a release wrong. Obtainium only updates when a **GitHub Release exists with the APK attached AND a matching versionCode**. The CI auto-releases from `app/build.gradle.kts`, but its "tag already exists" guard **skips the entire build+release if the tag and commit arrive together**.

Do this to ship a release:

1. **Build the APK** FIRST (so you never publish a stale version):
   ```bash
   ./gradlew assembleRelease --no-configuration-cache
   ```
   Confirm `app/build/outputs/apk/release/output-metadata.json` shows the NEW `versionCode`/`versionName`. If it shows an old version, you did not rebuild after bumping — rebuild.

2. **Bump version** if not already done: `versionCode` AND `versionName` in `app/build.gradle.kts`.

3. **Push the commit ALONE, wait for CI to fully finish** (watch Actions or `gh run list --limit 1` until `completed`):
   ```bash
   git add -A && git commit -m "Bump version to X.Y.Z ..."
   git push origin master
   ```
   Wait. Do NOT push the tag yet.

4. **Then push the tag SEPARATELY** (never concatenated — that triggers the CI skip):
   ```bash
   git tag v<X.Y.Z>       # annotate if you like
   git push origin v<X.Y.Z>
   ```

5. **Verify a GitHub Release with the APK actually exists** (CI "success" in 10s = it skipped, NOT built):
   ```bash
   gh release view v<X.Y.Z> --json name,isDraft,assets
   ```
   - If "release not found" → **the guard skipped it**. Manually create it from your built APK:
     ```bash
     gh release create v<X.Y.Z> app/build/outputs/apk/release/app-release.apk --title "v<X.Y.Z>" --notes "..."
     gh release edit v<X.Y.Z> --latest
     ```
   - If it exists with the APK attached and non-draft → done, Obtainium will pick it up.
6. **Tag must exist on origin before `gh release create`** — if you never pushed the tag, `git push origin v<X.Y.Z>` first.

**Golden rule:** push the **commit** and the **tag in separate steps**, and **never trust a CI run that finishes in under a minute** — that's the skip signal, not a successful build. The APK must be rebuilt right before the release, or you ship the previous version to Obtainium.
