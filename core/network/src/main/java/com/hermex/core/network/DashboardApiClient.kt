package com.hermex.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

/**
 * REST client for the Hermes Dashboard (port 9119 REST + WebSocket).
 *
 * Auth chain: password-login → session cookies → ws-ticket → WebSocket.
 * Separate from [ApiClient] (port 8650 Bearer-auth REST+SSE).
 * Both coexist in the same codebase.
 *
 * REST calls use [restUrl] (port 9119 HTTP). WebSocket upgrades use [wsUrl]
 * (port 9119 WS), derived automatically from [restUrl] in [setDashboardUrl].
 */
object DashboardApiClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val mediaTypeJson = "application/json".toMediaType()

    private var restUrl: String = ""            // e.g. "http://100.80.204.66:9119"
    private var wsUrl: String = ""              // e.g. "ws://100.80.204.66:9119"  (derived)
    private var dashboardPassword: String = ""
    private var dashboardUsername: String = "jeff"
    private lateinit var httpClient: OkHttpClient

    val isConfigured: Boolean get() = restUrl.isNotEmpty() && dashboardPassword.isNotEmpty()

    // ── Init ──

    /**
     * Build the shared OkHttpClient for dashboard REST calls.
     * Call once during app startup.
     */
    fun init(context: android.content.Context) {
        val cookieJar = NetworkCookieJar(context)

        httpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(DebugLoggingInterceptor())
            .authenticator(DashboardAuthenticator())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Log.d("Hermex", "DashboardApiClient.init: OkHttpClient ready")
        DebugLog.log("INFO", "DashboardApiClient", "OkHttpClient initialized (dashboard)")
    }

    fun setDashboardUrl(url: String) {
        restUrl = url.trimEnd('/')
        // Derive the WebSocket URL: same host and port, ws:///wss:// scheme only
        wsUrl = restUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
    }

    fun setPassword(password: String) {
        dashboardPassword = password
    }

    fun setUsername(username: String) {
        dashboardUsername = username.ifBlank { "jeff" }
    }

    /** Expose the HTTP client for ticket-fetch + WS creation. */
    fun httpClient(): OkHttpClient = httpClient

    /** REST base URL (port 9119 HTTP). Used for login, ws-ticket, status. */
    fun baseUrl(): String = restUrl

    /** WebSocket base URL (port 9119 WS/WSS). Used for WS upgrade. */
    fun wsBaseUrl(): String = wsUrl

    // ── Auth endpoints ──

    @Serializable
    data class LoginRequest(
        val provider: String = "basic",
        val username: String,
        val password: String,
    )

    @Serializable
    data class LoginResponse(
        val success: Boolean = false,
        val message: String? = null,
        val user: UserInfo? = null,
        val requires_2fa: Boolean = false,
    )

    @Serializable
    data class UserInfo(
        val id: String? = null,
        val username: String? = null,
        val role: String? = null,
    )

    @Serializable
    data class WsTicketResponse(
        val ticket: String,
        val ttl_seconds: Int = 30,
    )

    @Serializable
    data class StatusResponse(
        val version: String? = null,
        val auth_required: Boolean = false,
        val auth_providers: List<String> = emptyList(),
        val gateway_running: Boolean = false,
        val active_agents: Int = 0,
        val active_sessions: Int = 0,
    )

    @Serializable
    data class TranscribeRequest(
        val data_url: String,
        val mime_type: String? = null,
    )

    @Serializable
    data class TranscribeResponse(
        val ok: Boolean = false,
        val transcript: String? = null,
        val provider: String? = null,
    )

    // ── System panels (v0.1.61): cron / skills / config ──

    @Serializable
    data class CronJob(
        val id: String = "",
        val name: String = "",
        @SerialName("schedule_display") val scheduleDisplay: String? = null,
        val schedule: CronSchedule? = null,
        val enabled: Boolean = true,
        val state: String? = null,
        @SerialName("paused_at") val pausedAt: String? = null,
        @SerialName("next_run_at") val nextRunAt: String? = null,
        val repeat: CronRepeat? = null,
    )

    @Serializable
    data class CronSchedule(
        val kind: String? = null,
        @SerialName("run_at") val runAt: String? = null,
        val expr: String? = null,
        val minutes: Int? = null,
    )

    @Serializable
    data class CronRepeat(
        @SerialName("times") val times: Int? = null,
        @SerialName("completed") val completed: Int? = null,
    )

    @Serializable
    data class SkillInfo(
        val name: String = "",
        val description: String? = null,
        val category: String? = null,
        val enabled: Boolean = true,
        val usage: Int? = null,
        val provenance: String? = null,
    )

    @Serializable
    data class SkillContent(
        val name: String? = null,
        val content: String? = null,
        val path: String? = null,
    )

    @Serializable
    data class ConfigRaw(
        val yaml: String? = null,
        val path: String? = null,
    )

    /**
     * Log in with password. Session cookies are stored by the CookieJar.
     */
    suspend fun login(username: String, password: String): NetworkResult<LoginResponse> =
        withContext(Dispatchers.IO) {
            val body = LoginRequest(provider = "basic", username = username, password = password)
            val bodyStr = json.encodeToString(LoginRequest.serializer(), body)
            Log.d("Hermex", "DashboardApiClient.login() → $restUrl/auth/password-login")
            DebugLog.log("REQ", "Dashboard", "POST /auth/password-login (user=$username)")
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/auth/password-login")
                        .post(bodyStr.toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                val result = response.handleResult(json, LoginResponse.serializer())
                DebugLog.log("RESP", "Dashboard", "login → ${response.code} ${if (result is NetworkResult.Success) "success" else "failed"}")
                result
            } catch (e: Exception) {
                Log.e("Hermex", "Dashboard login failed", e)
                NetworkResult.Error(e)
            }
        }

    /**
     * Fetch a single-use 30s-TTL WebSocket ticket.
     * Requires valid session cookies (from prior login + CookieJar).
     */
    suspend fun fetchWsTicket(): NetworkResult<WsTicketResponse> =
        withContext(Dispatchers.IO) {
            // v0.1.151: do NOT unconditional re-login here. Reusing the existing
            // cookie session avoids a login on every WS connect, which trips the
            // server's brute-force throttle (10 logins / 60s per IP → 429) under
            // frequent sessions.changed reloads. The DashboardAuthenticator already
            // re-logs in only when a request actually 401s — that's the right place.
            Log.d("Hermex", "DashboardApiClient.fetchWsTicket() → $restUrl/api/auth/ws-ticket")
            DebugLog.log("REQ", "Dashboard", "POST /api/auth/ws-ticket")
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/auth/ws-ticket")
                        .post("{}".toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                val result = response.handleResult(json, WsTicketResponse.serializer())
                DebugLog.log("RESP", "Dashboard", "ws-ticket → ${response.code} ${if (result is NetworkResult.Success) "got ticket" else "failed"}")
                result
            } catch (e: Exception) {
                Log.e("Hermex", "Dashboard ws-ticket fetch failed", e)
                NetworkResult.Error(e)
            }
        }

    /**
     * Health-check / status endpoint.
     */
    suspend fun status(serverUrl: String): NetworkResult<StatusResponse> =
        withContext(Dispatchers.IO) {
            val url = serverUrl.trimEnd('/') + "/api/status"
            Log.d("Hermex", "DashboardApiClient.status() → $url")
            DebugLog.log("REQ", "Dashboard", "GET /api/status")
            try {
                val response = httpClient.newCall(
                    Request.Builder().url(url).get().build()
                ).execute()
                response.handleResult(json, StatusResponse.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /**
     * Transcribe a voice recording (dashboard Whisper-backed endpoint).
     * Body: {data_url: "data:audio/webm;base64,...", mime_type?} → {ok, transcript}.
     * Cookie-authenticated — uses the same session cookies as the WS ticket.
     */
    suspend fun transcribeAudio(dataUrl: String, mimeType: String? = null): NetworkResult<TranscribeResponse> =
        withContext(Dispatchers.IO) {
            Log.d("Hermex", "DashboardApiClient.transcribeAudio() → $restUrl/api/audio/transcribe")
            DebugLog.log("REQ", "Dashboard", "POST /api/audio/transcribe (${dataUrl.length} chars)")
            try {
                val body = TranscribeRequest(data_url = dataUrl, mime_type = mimeType)
                val bodyStr = json.encodeToString(TranscribeRequest.serializer(), body)
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/audio/transcribe")
                        .post(bodyStr.toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                val result = response.handleResult(json, TranscribeResponse.serializer())
                DebugLog.log("RESP", "Dashboard", "transcribe → ${response.code} ok=${result is NetworkResult.Success}")
                result
            } catch (e: Exception) {
                Log.e("Hermex", "Dashboard transcribe failed", e)
                NetworkResult.Error(e)
            }
        }

    // ── System panels (v0.1.61) — cookie-authenticated dashboard REST ──

    /** List cron jobs. Response: JSON array of jobs. */
    suspend fun cronJobs(): NetworkResult<List<CronJob>> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder().url("$restUrl/api/cron/jobs").get().build()
                ).execute()
                response.handleResult(json, ListSerializer(CronJob.serializer()))
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** pause | resume | trigger a cron job. */
    suspend fun cronAction(jobId: String, action: String): NetworkResult<JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/cron/jobs/$jobId/$action")
                        .post("{}".toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                response.handleResult(json, JsonObject.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    @Serializable
    data class CronRun(
        val id: String = "",               // session key — usable for deep links
        val title: String? = null,
        val preview: String? = null,
        @SerialName("started_at") val startedAt: Double? = null,
        @SerialName("ended_at") val endedAt: Double? = null,
        @SerialName("end_reason") val endReason: String? = null,
    )

    @Serializable
    data class CronRunsResult(
        val runs: List<CronRun> = emptyList(),
        val limit: Int? = null,
    )

    /** Latest runs of a cron job (cron runs are stored as sessions). */
    suspend fun cronRuns(jobId: String, limit: Int = 1): NetworkResult<CronRunsResult> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder().url("$restUrl/api/cron/jobs/$jobId/runs?limit=$limit").get().build()
                ).execute()
                response.handleResult(json, CronRunsResult.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    @Serializable
    data class SessionMessagesResult(
        @SerialName("session_id") val sessionId: String? = null,
        val messages: List<JsonRpcClient.MessageData> = emptyList(),
    )

    /** A session's messages (cron run output lives here) — v0.1.78. */
    suspend fun sessionMessages(sessionId: String, limit: Int = 10): NetworkResult<SessionMessagesResult> =
        withContext(Dispatchers.IO) {
            // Defensive guard (resume-bug live-SID misuse): the server's
            // resolve_session_id only matches a persistent DB key
            // (YYYYMMDD_HHMMSS_8hex), NOT a transient gateway live SID
            // (bare 8-hex uuid.hex[:8]). Live SIDs are in-memory-only and
            // reaped after WS disconnect, so a bare-sid call can never
            // resolve server-side — it deterministically 404s. Fail loud here
            // instead of emitting a silent 404 that looks like "no messages".
            if (looksLikeLiveSid(sessionId)) {
                DebugLog.log("WARN", "DashboardApiClient",
                    "REJECTING sessionMessages: sessionId '$sessionId' is a transient live SID, not a DB key — " +
                    "the caller must pass the stable DB key. /api/sessions/$sessionId/messages would 404.")
                return@withContext NetworkResult.Error(
                    IllegalStateException("sessionMessages called with a live SID '$sessionId'; expected DB key")
                )
            }
            try {
                val response = httpClient.newCall(
                    Request.Builder().url("$restUrl/api/sessions/$sessionId/messages?limit=$limit").get().build()
                ).execute()
                response.handleResult(json, SessionMessagesResult.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** True if [id] matches a bare 8-hex live SID (uuid.hex[:8]) rather than a DB key. */
    private fun looksLikeLiveSid(id: String): Boolean {
        val isDbKey = id.matches(Regex("\\d{8}_\\d{6}_[0-9a-f]{8}"))
        val isBare8Hex = id.matches(Regex("[0-9a-f]{8}"))
        return isBare8Hex && !isDbKey
    }

    // ── Cron CRUD (v0.1.80): create / update / delete / delivery targets ──

    /** Create a cron job. schedule = cron expr ("0 16 * * 1-5") or interval ("every 90m"). */
    suspend fun cronCreate(prompt: String, schedule: String, name: String, deliver: String): NetworkResult<CronJob> =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("prompt", prompt)
                    put("schedule", schedule)
                    put("name", name)
                    put("deliver", deliver)
                }
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/cron/jobs")
                        .post(body.toString().toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                response.handleResult(json, CronJob.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** Update a cron job's fields (schedule/name/prompt/deliver/...). */
    suspend fun cronUpdate(jobId: String, updates: Map<String, String>): NetworkResult<CronJob> =
        withContext(Dispatchers.IO) {
            try {
                val updatesJson = buildJsonObject {
                    updates.forEach { (k, v) -> put(k, v) }
                }
                val body = buildJsonObject { put("updates", updatesJson) }
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/cron/jobs/$jobId")
                        .put(body.toString().toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                response.handleResult(json, CronJob.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** Delete a cron job. */
    suspend fun cronDelete(jobId: String): NetworkResult<JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/cron/jobs/$jobId")
                        .delete()
                        .build()
                ).execute()
                response.handleResult(json, JsonObject.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    @Serializable
    data class CronDeliveryTarget(
        val id: String = "",
        val name: String? = null,
    )

    @Serializable
    data class CronDeliveryTargetsResult(
        val targets: List<CronDeliveryTarget> = emptyList(),
    )

    /** Delivery options for the cron editor dropdown (local + configured platforms). */
    suspend fun cronDeliveryTargets(): NetworkResult<CronDeliveryTargetsResult> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder().url("$restUrl/api/cron/delivery-targets").get().build()
                ).execute()
                response.handleResult(json, CronDeliveryTargetsResult.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** List skills. Response: JSON array of skill infos. */
    suspend fun skillsList(): NetworkResult<List<SkillInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder().url("$restUrl/api/skills").get().build()
                ).execute()
                response.handleResult(json, ListSerializer(SkillInfo.serializer()))
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** Read a skill's raw SKILL.md text. */
    suspend fun skillContent(name: String): NetworkResult<SkillContent> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$restUrl/api/skills/content?name=${java.net.URLEncoder.encode(name, "UTF-8")}"
                val response = httpClient.newCall(
                    Request.Builder().url(url).get().build()
                ).execute()
                response.handleResult(json, SkillContent.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** Enable/disable a skill. */
    suspend fun toggleSkill(name: String, enabled: Boolean): NetworkResult<JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val body = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("name", name)
                        put("enabled", enabled)
                    },
                )
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/skills/toggle")
                        .put(body.toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                response.handleResult(json, JsonObject.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** Read the raw config.yaml text. */
    suspend fun configRaw(): NetworkResult<ConfigRaw> =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(
                    Request.Builder().url("$restUrl/api/config/raw").get().build()
                ).execute()
                response.handleResult(json, ConfigRaw.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    /** Save config.yaml raw text. Server expects {yaml_text} (RawConfigUpdate). */
    suspend fun saveConfigRaw(yaml: String): NetworkResult<ConfigRaw> =
        withContext(Dispatchers.IO) {
            try {
                val body = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("yaml_text", yaml) },
                )
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$restUrl/api/config/raw")
                        .put(body.toRequestBody(mediaTypeJson))
                        .build()
                ).execute()
                response.handleResult(json, ConfigRaw.serializer())
            } catch (e: Exception) {
                NetworkResult.Error(e)
            }
        }

    // ── Authenticator: auto-relogin on 401 ──

    private class DashboardAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.url.encodedPath == "/auth/password-login") {
                return null
            }

            synchronized(this) {
                val currentPassword = dashboardPassword
                if (currentPassword.isEmpty()) return null

                DebugLog.log("AUTH", "Dashboard", "401 received — attempting re-login")
                Log.d("Hermex", "DashboardAuthenticator: 401 on ${response.request.url}, re-logging in")

                try {
                    val loginBody = LoginRequest(provider = "basic", username = dashboardUsername, password = currentPassword)
                    val bodyStr = json.encodeToString(LoginRequest.serializer(), loginBody)

                    val loginCall = httpClient.newCall(
                        Request.Builder()
                            .url("$restUrl/auth/password-login")
                            .post(bodyStr.toRequestBody(mediaTypeJson))
                            .build()
                    )
                    val loginResponse = loginCall.execute()

                    if (loginResponse.isSuccessful) {
                        DebugLog.log("AUTH", "Dashboard", "re-login success — retrying original request")
                        Log.d("Hermex", "DashboardAuthenticator: re-login OK")
                        return response.request
                    } else {
                        DebugLog.log("AUTH", "Dashboard", "re-login failed (${loginResponse.code}) — giving up")
                        Log.w("Hermex", "DashboardAuthenticator: re-login returned ${loginResponse.code}")
                        return null
                    }
                } catch (e: Exception) {
                    DebugLog.log("AUTH", "Dashboard", "re-login exception: ${e.message}")
                    Log.e("Hermex", "DashboardAuthenticator: re-login failed", e)
                    return null
                }
            }
        }
    }
}
