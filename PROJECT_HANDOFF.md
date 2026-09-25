# Hermex Android — Project Handoff

**⚠️ This file is superseded by `PROJECT_HANDOFF_CURRENT.md` — see that file for the latest state.**

**Last updated:** 2026-07-18  
**Current version:** v0.1.32 (see PROJECT_HANDOFF_CURRENT.md for full details)  
**Repository:** `git@github.com:gravol/hermes-android.git`  
**Working directory:** `~/HermexAndroid`

---

## Project Overview

### What the application does
Hermex Android is a native Kotlin/Compose chat client for the **Hermes Agent** AI assistant. It connects to a self-hosted Hermes Dashboard (running on a Linux server at Tailscale IP `100.80.204.66`) to provide a mobile-first conversational interface with live token streaming, tool-call visualization, thinking/reasoning blocks, and session management.

### Overall architecture
- **Multi-module Gradle project** — `:app`, `:core:network`, `:core:data` (plus un-wired `:feature/*` modules)
- **MVVM with Compose** — `ViewModels` expose `mutableStateOf` snapshot state (no `StateFlow` conflation during streaming)
- **Plain OkHttp** — no Retrofit, no Hilt/Dagger. Manual DI via `AppModule` singleton
- **Two networking stacks coexist:**
  1. **Legacy REST+SSE** (`ApiClient.kt`) — connects to Hermes API Server on port 8650 with Bearer token auth
  2. **New JSON-RPC/WebSocket** (`DashboardApiClient.kt` + `WsConnectionManager.kt` + `JsonRpcClient.kt`) — connects to Hermes Dashboard on port 8443/9119 with cookie-based auth

### Primary technologies
| Layer | Library | Version |
|---|---|---|
| Language | Kotlin | 2.0.20 |
| UI | Jetpack Compose (Material 3) | BOM 2024.06.00 |
| Build | AGP + Gradle | 8.5.0 |
| HTTP/WS | OkHttp | 4.12.0 |
| Serialization | kotlinx.serialization | 1.7.3 |
| Persistence | Room + EncryptedSharedPreferences + DataStore | Room 2.6.1 |
| DI | Manual (`AppModule` singleton) | — |
| Navigation | Navigation Compose | 2.8.0 |
| Images | Coil | 2.7.0 |

### Relationship to iOS version
There is no iOS counterpart in this repo. The `~/hermes-android` directory next to this one is an abandoned **Flutter/Dart** project (tagged `v1.0.20`, now marked with `ABANDONED.md`). Do not touch that directory. This `~/HermexAndroid` project is the canonical, actively developed native Kotlin port.

---

## Current Status

### What works (REST+SSE port 8650 stack)
- Setup screen with server URL + API key entry
- Session list (loaded from `GET /api/sessions`)
- Chat screen with live SSE streaming
- Telegram-style message bubbles (user right-aligned, assistant left-aligned)
- Live thinking ticker (dimmed italic above bubble during model thinking)
- Typing dots indicator (bouncing dots shown during pre-first-event gap)
- Keyboard auto-scroll on composer tap
- Drag-detected auto-scroll guard (NestedScrollConnection, pauses on user drag)
- Post-stream scroll-to-bottom
- Long-press copy to clipboard
- Debug log export (ring buffer, 1000 entries, Bearer redacted)

### What was just built (JSON-RPC/WebSocket port 9119 stack)
Four new files in `core/network/`, all compiling, full APK builds clean:

| File | Lines | Role |
|---|---|---|
| `DashboardApiClient.kt` | 242 | Cookie-based REST: `login()`, `fetchWsTicket()`, `status()`, 401 auto-relogin `Authenticator` |
| `RpcNotification.kt` | 143 | Sealed class for 17 server-pushed event types (deltas, tool calls, approval, clarify, completion) |
| `WsConnectionManager.kt` | 198 | `StateFlow<State>`, `Channel`/`Flow` for frames, exponential backoff reconnect, `pingInterval(30s)` keepalive |
| `JsonRpcClient.kt` | 433 | `@PublishedApi internal inline reified request()`, `CompletableDeferred` pending map, notification parser, v1 convenience methods, auto-deny for approval/clarify |

### What is NOT yet wired
- **No dashboard SetupViewModel** — none of the dashboard auth flow is connected to UI (login, status check)
- **No dashboard ChatViewModel** — no JSON-RPC streaming parser wired to Compose state
- **`MainActivity`** / `HermexNavGraph` still uses the old `ApiClient.isConfigured` check
- **Feature modules** (`feature/chat/`, `feature/session/`, etc.) contain auto-generated stubs — dead code

### Known bugs (pre-pivot)
- The REST+SSE `ApiClient` uses `DebugLoggingInterceptor.peekBody(Long.MAX_VALUE)` which buffers SSE streams — fixed by skipping SSE paths, but the `ApiClient` is being superseded anyway
- Obtaimium update detection relies on versionCode; make sure to bump with every release

---

## Recent Changes — The Dashboard Pivot

### What changed and why
The original architecture connected to the **Hermes API Server** (port 8650) via REST+SSE with Bearer token auth. This was a simplified API designed for programmatic access. The **Hermes Dashboard** (port 9119) exposes the full agent surface via JSON-RPC 2.0 over WebSocket — the same protocol the TUI/clerk uses internally. Pivoting to the dashboard gives the Android app access to:
- Tool call visualization (started/progress/completed events)
- Approval/clarify workflows
- File/image attachments
- Voice recording
- Session branching, undo, compress
- Real protocol consistency with other Hermes clients

### Files changed in this pivot
```
NEW: core/network/src/main/java/com/hermex/core/network/DashboardApiClient.kt
NEW: core/network/src/main/java/com/hermex/core/network/RpcNotification.kt
NEW: core/network/src/main/java/com/hermex/core/network/WsConnectionManager.kt
NEW: core/network/src/main/java/com/hermex/core/network/JsonRpcClient.kt
```

These live alongside the existing REST+SSE code (`ApiClient.kt`, `DTOs.kt`, etc.) — both stacks coexist. The old code should be cleaned up after the new stack is proven working end-to-end.

### Auth chain (new stack)
```
1. POST /auth/password-login {"provider":"basic","username":"jeff","password":"***"}
   → session cookies (hermes_session_at 12h, hermes_session_rt 30d) stored in CookiePersistor

2. POST /api/auth/ws-ticket (cookie-authenticated)
   → {"ticket":"...", "ttl_seconds":30}  # single-use, 30s TTL

3. ws://100.80.204.66:9119/api/ws?ticket=...
   → 101 Switching Protocols
   → {"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready",...}}
```

### Assumptions and compromises
- **Trust-all SSL**: DashboardApiClient trusts self-signed certs (development server on Tailscale IP). Production should use a proper CA or CertificatePinner
- **Hardcoded username**: The 401 Authenticator in DashboardApiClient hardcodes `username = "jeff"` for re-login. Replace with stored value
- **Auto-deny for v1**: Approval and clarify requests are auto-denied with a log message. Real UI should be built before removing this
- **Delta shape**: `message.delta`, `thinking.delta`, `reasoning.delta` are APPEND-ONLY fragments. Client concatenates. No sequence field. Order guaranteed by WebSocket stream order

---

## Completed Work

### Phase 1 — Dashboard Initialization (v0.1.7, commit `19f036c`)

**What changed:**
- `KeychainStore.kt` — added `saveDashboardCredentials()`, `getDashboardUrl()`, `getDashboardPassword()` methods alongside existing API key storage. Same `EncryptedSharedPreferences` backend (AES256 GCM), same fallback-to-plain-SharedPreferences pattern.
- `HermexApplication.kt` — added `DashboardApiClient.init(this)` in a separate try/catch block after legacy `ApiClient.init()`. Restores saved dashboard URL and password from `KeychainStore` on startup. Legacy `ApiClient` init path unchanged.
- `app/build.gradle.kts` — versionCode 7→8, versionName "0.1.6"→"0.1.7"

**Design decisions:**
- Dashboard init is in a **separate try/catch** from legacy init — a dashboard failure does not block legacy API server access
- Dashboard credentials use **the same `hermex_auth` prefs file** as legacy credentials (separate keys, shared encrypted store)
- No new Compose screens, no ViewModel changes, no WebSocket/JSON-RPC wiring — purely startup initialization

**Verified:** `./gradlew assembleRelease --no-configuration-cache` → BUILD SUCCESSFUL

**Next recommended phase:** Phase 2 — Dashboard SetupViewModel (`DashboardApiClient.status()` → `login()`, Compose screen for URL + password entry, route in `HermexNavGraph`). No WebSocket yet.

---

## Architecture

### Navigation
```
Setup (if not configured) → Sessions → Chat
                              ↓
                           Settings
```
Routes: `setup`, `home`, `chat/{sessionId}/{title}`, `settings`. Defined inline in `MainActivity.HermexNavGraph()`.

### State management
- **`mutableStateOf` (Compose snapshot state)** — used in `ChatViewModel` instead of `StateFlow` to avoid conflation during fast streaming. Every `uiState` reassignment feeds the next Compose frame.
- **`scrollGeneration: Long`** — counter in `ChatUiState` bumped on every SSE event that mutates the message list. Triggers auto-scroll in `ChatScreen` via `LaunchedEffect(state.scrollGeneration)`.

### Networking (NEW stack)
```
DashboardApiClient (REST, cookie-auth)
  ↓ provides OkHttpClient
WsConnectionManager (WebSocket lifecycle, reconnect, Channel<Flow>)
  ↓ provides raw text frames
JsonRpcClient (JSON-RPC 2.0, request/response correlation, notification router)
  ↓ provides notifications: Flow<RpcNotification>
UI Layer (ChatViewModel, etc.)
```

### Networking (OLD stack — still active in HermexApplication)
```
ApiClient (Bearer auth, REST+SSE, port 8650)
  ↓
ChatScreen → ChatViewModel → ApiClient.openChatStream() → SSE EventSource
SessionsScreen → SessionsViewModel → ApiClient.getSessions()
```

### Authentication
- **Dashboard (new):** Cookie-based via `DashboardApiClient`. `CookiePersistor` stores encrypted cookies. 401 auto-relogin via `DashboardAuthenticator`.
- **API Server (old):** Bearer token via `ApiClient.BearerInterceptor`. `KeychainStore` stores token + server URL in `EncryptedSharedPreferences`.

### Storage
- **Room DB** (`core/data`): `Session`, `Message`, `ToolCall`, `ThinkingCard` entities with DAOs. Designed for the old stack — may need schema changes for the new protocol.
- **EncryptedSharedPreferences** (`KeychainStore`): Server URL + API key (old stack). Reusable for dashboard password + URL.
- **CookiePersistor** (`NetworkCookieJar`): Encrypted cookie storage via `EncryptedSharedPreferences`. Shared between old and new stacks.
- **DataStore** (`DataStoreManager`): App preferences.

### Background services
`HermesForegroundService` exists in `core/ui` but is not wired. Intended for keeping the WebSocket alive in background.

### Dependency injection
Manual via `AppModule` singleton object (no Hilt, no Koin). Provides: `Database`, DAOs, `CacheManager`, `DataStoreManager`. ViewModels use `AndroidViewModel` with `Application` context.

---

## Android Implementation

### Project structure (active modules)
```
app/                          # Main application + Compose UI
├── ChatScreen.kt             # 655 lines — chat UI, scroll logic, bubbles, typing dots
├── ChatViewModel.kt          # 371 lines — SSE handling, mutableStateOf, scrollGeneration
├── SetupScreen.kt            # Server URL + API key entry
├── SetupViewModel.kt         # Health-check + login via old ApiClient
├── SessionsScreen.kt         # Session list
├── SessionsViewModel.kt      # Fetches /api/sessions via old ApiClient
├── SettingsScreen.kt         # Debug log export
├── HermexApplication.kt      # App startup, ApiClient.init, crash handler
├── MainActivity.kt           # NavGraph + edge-to-edge
├── AppModule.kt              # Manual DI
└── ui/theme/                 # Material 3 theme (Color, Type, Theme)

core/network/                 # Networking layer (both stacks)
├── ApiClient.kt              # OLD: Bearer auth, REST+SSE (port 8650)
├── DTOs.kt                   # OLD: SseEvent, ChatMessage, NetworkResult
├── SseParser.kt              # OLD: SSE event parser
├── DashboardApiClient.kt     # NEW: cookie auth, login, ws-ticket (port 9119)
├── WsConnectionManager.kt    # NEW: WebSocket lifecycle, reconnect
├── JsonRpcClient.kt          # NEW: JSON-RPC 2.0, pending requests, notifications
├── RpcNotification.kt        # NEW: sealed class for server events
├── CookiePersistor.kt        # Encrypted cookie storage
├── NetworkCookieJar.kt       # OkHttp CookieJar backed by CookiePersistor
├── DebugLog.kt               # Ring buffer logger (1000 entries)
├── DebugLoggingInterceptor.kt # HTTP/SSE debug logging
└── NetworkResult.kt          # sealed class: Success, HttpError, Error

core/data/                    # Data layer
├── auth/KeychainStore.kt     # EncryptedSharedPreferences for secrets
├── db/                       # Room entities + DAOs
├── models/                   # API response models (old stack)
├── DataStoreManager.kt       # Preferences
└── cache/CacheManager.kt     # In-memory cache
```

### Key classes and responsibilities

| Class | Responsibility |
|---|---|
| `ChatScreen.kt` | Compose UI: LazyColumn, bubbles, composer, typing dots, thinking ticker, NestedScrollConnection drag guard, all 4 scroll-on-content-change LaunchedEffects |
| `ChatViewModel.kt` | `handleSseEvent()` dispatches SSE events, `sendMessage()`, `loadMessages()`, `toggleThinking()`, `scrollGeneration` counter |
| `DashboardApiClient.kt` | `init()`, `login()`, `fetchWsTicket()`, `status()`, 401 auto-relogin `DashboardAuthenticator`, trust-all SSL |
| `WsConnectionManager.kt` | `connect()`, `disconnect()`, `send()`, `StateFlow<State>`, `messages: Flow<String>`, exponential backoff, `pingInterval(30s)` |
| `JsonRpcClient.kt` | `request(method, params)`, `notify(method, params)`, `notifications: Flow<RpcNotification>`, pending-requests map, auto-deny for approval/clarify |
| `RpcNotification.kt` | `MessageDelta`, `ThinkingDelta`, `ToolStarted/Progress/Completed`, `ApprovalRequest`, `ClarifyRequest`, `MessageCompleted`, `GatewayReady`, etc. |
| `AppModule.kt` | Singletons: `Database`, DAOs, `CacheManager`, `DataStoreManager` |
| `HermexApplication.kt` | Startup: `ApiClient.init()`, restore saved secrets |

### Components that mirror iOS
Not applicable — there is no iOS version in this repo. The old `~/hermes-android` Flutter project is abandoned.

### Components that intentionally differ from standard patterns
- **`mutableStateOf` instead of `StateFlow`** — avoids conflation during rapid SSE events (50-100ms deltas). `StateFlow` would drop intermediate values; snapshot state writes directly to the next Compose frame.
- **`scrollGeneration` counter** — instead of keying `LaunchedEffect` on specific data fields (content, thinkingText, toolCall size), a monotonic counter avoids missing future event types.
- **`scrollToItem` instead of `animateScrollToItem`** — spring animation fights rapid content changes. Instant scroll completes in one frame.

---

## Remaining Work

### ~~1. Wire DashboardApiClient into HermexApplication~~ ✅ Done (v0.1.7)
**Completed:** `DashboardApiClient.init(this)` now called in `HermexApplication.onCreate()` alongside legacy `ApiClient.init()`. Dashboard credentials restored from `KeychainStore` on startup. Separate try/catch ensures dashboard failure doesn't block legacy API access.

### 2. Create Dashboard SetupViewModel + setup flow
**Goal:** User enters dashboard URL (e.g. `https://100.80.204.66:8443`), password. App calls `DashboardApiClient.status()` then `DashboardApiClient.login()`. On success, store credentials and navigate to sessions.  
**Relevant files:** `DashboardApiClient.kt`, `SetupScreen.kt` (reuse or create new), `KeychainStore.kt` (add dashboard fields)  
**Current progress:** Nothing wired.  
**Blocking issues:** None.  
**Recommended next step:** Create `DashboardSetupViewModel` in `com.hermex.android.feature.onboarding`, update `KeychainStore` with `saveDashboard()`/`getDashboardUrl()`/`getDashboardPassword()` methods. Update `HermexNavGraph` start destination to check `DashboardApiClient.isConfigured`.

### 3. Create Dashboard ChatViewModel for JSON-RPC streaming
**Goal:** Replace SSE-based `ChatViewModel` with one that uses `JsonRpcClient` for `session.list`, `session.resume`, `prompt.submit`, and consumes `RpcNotification` events for streaming.  
**Relevant files:** `ChatViewModel.kt`, `ChatScreen.kt` (mostly reusable), `JsonRpcClient.kt`, `RpcNotification.kt`, `WsConnectionManager.kt`  
**Current progress:** All networking primitives exist. `ChatScreen` UI is fully reusable — the Compose layer doesn't care whether data comes from SSE or WS. `RpcNotification` maps 1:1 to the SSE event types already handled by the old `ChatViewModel`.  
**Blocking issues:** `JsonRpcClient` must be initialized with a connected `WsConnectionManager`. Need wiring from UI.  
**Recommended next step:** Create `DashboardChatViewModel` that:
1. Calls `JsonRpcClient.sessionList()` on init
2. Calls `JsonRpcClient.promptSubmit()` on send
3. Collects `JsonRpcClient.notifications` Flow, maps `RpcNotification` types to `UiMessage` updates (same data model as old `ChatViewModel`)
4. Handles `MessageCompleted` for finalization, `ApprovalRequest`/`ClarifyRequest` for auto-deny

### 4. Update HermexNavGraph for dashboard routing
**Goal:** Switch `MainActivity` start destination to check `DashboardApiClient.isConfigured` instead of `ApiClient.isConfigured`.  
**Relevant files:** `MainActivity.kt`  
**Current progress:** NavGraph unchanged since pivot.  
**Blocking issues:** Needs #1 and #2 completed first.

### 5. Clean up old code (after new stack is proven)
**Goal:** Remove `ApiClient.kt`, `DTOs.kt`, `SseParser.kt`, old `SetupViewModel`, old `SetupScreen`, old `SessionsViewModel`. Update `HermexApplication` to only init the dashboard stack.  
**Current progress:** Not started — intentionally deferred to avoid losing reference code during the pivot.  
**Blocking issues:** Must confirm end-to-end chat works on the new stack first.

### 6. Feature modules cleanup
**Goal:** Delete auto-generated stub files in `feature/chat/views/Component_*.kt` and `feature/generated/phase5_*.kt`. These are unused code-gen artifacts.  
**Relevant files:** `feature/chat/views/Component_*.kt` (14 files), `feature/generated/phase5_*.kt` (4 files)  
**Current progress:** Not started.  
**Blocking issues:** None — safe to delete. They reference old package paths.

---

## Known Issues

### Build
- **Build command:** `./gradlew assembleRelease --no-configuration-cache` (the `--no-configuration-cache` flag is critical — cached configs have produced stale APK bytecode)
- **Signed with debug key:** Release builds use `signingConfigs.getByName("debug")`. Need real keystore before production distribution.
- **Obtaimium updates:** versionCode must be bumped uniquely with every release. Current: versionCode 7.

### Runtime
- **No connection error handling in UI:** If the server is unreachable, the old `SetupScreen` may hang. Add timeout + error display.
- **No keepalive for old SSE stack:** The `ApiClient` SSE connection has no ping mechanism. The new `WsConnectionManager` handles this via `pingInterval(30s)`.
- **Phone lock kills SSE stream:** The old SSE connection aborts when the phone locks. The new WebSocket stack will need background service integration (`HermesForegroundService`).

### UI
- **Feature modules contain dead code:** `feature/chat/`, `feature/session/`, `feature/skills/`, etc. contain auto-generated `Component_*.kt` stubs that don't compile. They're not in the settings.gradle.kts module list so they don't affect the build — just dead source files.
- **Theme not applied to all screens:** `SettingsScreen` and `SetupScreen` may not use `HermexTheme` colors consistently.

### Performance
- **Debug APK is large:** 63MB debug build vs 28MB release (ProGuard + R8). Expected.
- **SSE `peekBody` buffering:** Already fixed for streaming paths, but the old `ApiClient` still has the interceptor on all other requests.

---

## Development Workflow

### Build commands
```bash
# Full release build
cd ~/HermexAndroid
./gradlew assembleRelease --no-configuration-cache

# Compile individual modules
./gradlew :core:network:compileReleaseKotlin
./gradlew :core:data:compileReleaseKotlin

# Clean build
./gradlew clean assembleRelease --no-configuration-cache
```

### Run
The APK is at `app/build/outputs/apk/release/app-release.apk`. Install via `adb install` or Obtanium (point to GitHub Releases).

### Release
```bash
# After build:
git tag -a v0.1.X -m "v0.1.X — description"
git push origin master && git push origin v0.1.X
gh release create v0.1.X app/build/outputs/apk/release/app-release.apk --title "v0.1.X — description" --notes "..."
```

### Environment
- **Server:** Hermes Dashboard at `https://100.80.204.66:8443` (TLS, self-signed cert, Tailscale IP)
- **WebSocket:** `ws://100.80.204.66:9119/api/ws?ticket=...` (plain, inside Tailscale)
- **Legacy API:** `http://100.80.204.66:8650` (plain HTTP, Hermes API Server)
- **Device:** GrapheneOS, Tailscale-connected

### Required secrets
- Dashboard password (stored in `KeychainStore` as `dashboard_password`)
- Hermes API Server bearer key (stored as `api_key` — legacy)
- Obtanium needs GitHub PAT with `repo` scope for private repo access

---

## AI Context

### Architectural decisions (preserve)

1. **`mutableStateOf`, not `StateFlow`** — `StateFlow` conflates intermediate values. During rapid SSE/WS events (10-50ms deltas), `StateFlow` drops writes and the UI misses frames. Compose snapshot state (`mutableStateOf`) writes directly to the next composition frame. This was proven correct through testing.

2. **`scrollToItem`, not `animateScrollToItem`** — Spring animation (~200-300ms) is constantly cancelled and restarted by rapid deltas, causing viewport drift. Instant scroll (`scrollToItem`) completes in one frame and never fights the next event. Use `animateScrollToItem` ONLY for single-shot events (keyboard open, session load).

3. **`scrollGeneration` counter, not field-keyed LaunchedEffect** — Keying auto-scroll on specific fields (`content`, `thinkingText`, `toolCalls.size`) breaks when new event types are added. A monotonic counter bumped on every list mutation ensures ANY future event type gets auto-scroll for free.

4. **Fresh ticket every reconnect** — The WebSocket ticket is single-use with 30s TTL. Never cache or reuse. Every `WsConnectionManager` reconnect cycle fetches a fresh ticket via `DashboardApiClient.fetchWsTicket()`.

5. **Auto-deny for approval/clarify in v1** — Unhandled approval/clarify requests cause hung turns. v1 minimum: auto-deny `approval.respond`/`clarify.respond` with `JsonRpcClient.notify()`, then emit the notification to the UI so it can show a toast. Remove auto-deny only when real approval UI is built.

6. **Coexist old and new networking stacks** — Do NOT delete `ApiClient.kt` or related files until the new JSON-RPC/WS stack is proven end-to-end on device. Both stacks share `CookiePersistor`, `NetworkCookieJar`, and `DebugLog`.

7. **`@PublishedApi internal` for inline functions** — `JsonRpcClient.request()` is `inline` with `reified` type parameter. All members it accesses must be `@PublishedApi internal` (not `private`) because inline functions are copied to the call site where private members are invisible.

8. **Delta = append-only concatenation** — `message.delta`/`thinking.delta`/`reasoning.delta` carry incremental text fragments. The client concatenates them. No sequence number field exists — order is guaranteed by the WebSocket stream order. Do not try to sort or index deltas.

### Coding conventions
- Package: `com.hermex.android` for app, `com.hermex.core.*` for libraries
- ViewModels extend `AndroidViewModel(application)` — manual factory pattern (no Hilt)
- `object` singletons for stateless utilities: `ApiClient`, `DashboardApiClient`, `KeychainStore`, `AppModule`, `DebugLog`, `CacheManager`
- `DebugLog.log(level, tag, message)` for all operational logging — ring buffer, exportable from Settings
- All network errors logged through DebugLog; crash handler in `HermexApplication` catches the rest

---

## Next Recommended Task

### Wire DashboardApiClient + Create Dashboard SetupViewModel

**Priority:** Highest — this unblocks all subsequent dashboard work.

**Files involved:**
1. `HermexApplication.kt` — add `DashboardApiClient.init(this)` call
2. `KeychainStore.kt` — add `saveDashboardCredentials()`, `getDashboardUrl()`, `getDashboardPassword()`
3. `app/.../feature/onboarding/DashboardSetupScreen.kt` — new Compose screen for dashboard URL + password entry
4. `app/.../feature/onboarding/DashboardSetupViewModel.kt` — new ViewModel calling `DashboardApiClient.status()` → `login()`
5. `MainActivity.kt` — add dashboard setup route, switch start destination check

**Approach:**
1. Add `saveDashboardCredentials(url, password)` and `getDashboard*()` to `KeychainStore` (same pattern as existing `save()`/`getServerUrl()`/`getApiKey()` — EncryptedSharedPreferences with AES256 GCM)
2. In `HermexApplication.onCreate()`: after `ApiClient.init()`, call `DashboardApiClient.init(this)`, restore saved dashboard URL/password
3. Create `DashboardSetupScreen` with URL field (default `https://100.80.204.66:8443`), password field, "Connect" button
4. `DashboardSetupViewModel.testConnection()`: calls `DashboardApiClient.status(enteredUrl)` then `DashboardApiClient.login("jeff", enteredPassword)`, saves on success
5. Update `HermexNavGraph` to add `dashboard-setup` route; switch `startDest` to check `DashboardApiClient.isConfigured` before falling back to old `ApiClient.isConfigured`

This is a contained task — no WebSocket or JSON-RPC required yet. Just REST auth. Once login works, the next task is wiring `WsConnectionManager.connect()` + `JsonRpcClient.start()` to get the full pipeline live.
