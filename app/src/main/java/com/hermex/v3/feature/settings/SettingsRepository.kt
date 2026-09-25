package com.hermex.v3.feature.settings

import android.content.Context
import com.hermex.v3.di.AppModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persisted display preferences (UI zoom + text scale), stored as integer
 * percentages in the existing "hermex_settings" DataStore (via DataStoreManager).
 *
 * - uiZoomPercent scales the whole UI (dp) — e.g. 120 = 1.2× zoom
 * - textScalePercent scales text (sp) on top of zoom — e.g. 110 = 1.1× text
 * Both default to 100 (= unchanged). Applied at the app root in MainActivity
 * via a CompositionLocalProvider(LocalDensity) override.
 *
 * Appearance overrides (v0.1.49): per-UI-part color overrides as #RRGGBB hex,
 * null/blank = derive from accent (default behavior).
 * - uiBackgroundHex → app background + surface
 * - uiUserBubbleHex → user message bubbles (primaryContainer)
 * - uiAssistantBubbleHex → assistant bubbles + top bars / composer chrome (surfaceVariant)
 *
 * Extra chat surfaces (v0.1.95): separate per-surface colors so e.g. code
 * blocks can be dark while thinking stays subtle, independent of the assistant
 * bubble color:
 * - uiCodeBlockHex → code block header + highlighted content + inline code
 * - uiThinkingHex → THINKING box (finished) + live thinking ticker pill
 * - uiToolCardHex → TOOLS box, live activity panel, tool card surfaces
 * - uiGaugeHex → context-window gauge track (bar stays accent/error)
 *
 * Pinned sessions (v0.1.49): locally-pinned session ids (mirrors the desktop
 * app's client-side pinning — there is no server-side pin).
 */
class SettingsRepository(context: Context) {

    private val store = AppModule.provideDataStoreManager(context.applicationContext)

    val uiZoomPercent: Flow<Int> = store.getInt(KEY_UI_ZOOM).map { it ?: 100 }

    val textScalePercent: Flow<Int> = store.getInt(KEY_TEXT_SCALE).map { it ?: 100 }

    /** Accent color as #RRGGBB, or null/blank = system (follow wallpaper / dynamic). */
    val accentColorHex: Flow<String?> = store.getString(KEY_ACCENT_COLOR)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    /** UI-part overrides, null/blank = derive from accent. */
    val uiBackgroundHex: Flow<String?> = store.getString(KEY_UI_BACKGROUND)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    val uiUserBubbleHex: Flow<String?> = store.getString(KEY_UI_USER_BUBBLE)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    val uiAssistantBubbleHex: Flow<String?> = store.getString(KEY_UI_ASSISTANT_BUBBLE)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    /** Primary text color override (onSurface/onBackground; secondary = 72% alpha). */
    val uiTextHex: Flow<String?> = store.getString(KEY_UI_TEXT)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    // v0.1.95: extra chat surfaces — null/blank = derive from scheme
    val uiCodeBlockHex: Flow<String?> = store.getString(KEY_UI_CODE_BLOCK)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    val uiThinkingHex: Flow<String?> = store.getString(KEY_UI_THINKING)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    val uiToolCardHex: Flow<String?> = store.getString(KEY_UI_TOOL_CARD)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    val uiGaugeHex: Flow<String?> = store.getString(KEY_UI_GAUGE)
        .map { it?.takeIf { s -> s.isNotBlank() } }

    /** Monospace font everywhere (desktop terminal look). */
    val uiMonospace: Flow<Boolean> = store.getBoolean(KEY_UI_MONOSPACE).map { it ?: false }

    /** Show tool-call boxes/rows in chat (v0.1.96). Off hides finished tools
     *  box, live-panel tool rows and tool dialogs; thinking + response stay. */
    val showToolCalls: Flow<Boolean> = store.getBoolean(KEY_SHOW_TOOL_CALLS).map { it ?: true }

    /** Show thinking boxes in chat (v0.1.97). Off hides finished thinking box,
     *  live THINKING section and ticker; tools + response stay. */
    val showThinking: Flow<Boolean> = store.getBoolean(KEY_SHOW_THINKING).map { it ?: true }

    /** Locally pinned session ids (desktop-style client-side pinning). */
    val pinnedSessionIds: Flow<Set<String>> = store.getStringSet(KEY_PINNED_SESSIONS)

    suspend fun setUiZoomPercent(value: Int) = store.setInt(KEY_UI_ZOOM, value)

    suspend fun setTextScalePercent(value: Int) = store.setInt(KEY_TEXT_SCALE, value)

    suspend fun setAccentColorHex(value: String?) = store.setString(KEY_ACCENT_COLOR, value.orEmpty())

    suspend fun setUiBackgroundHex(value: String?) = store.setString(KEY_UI_BACKGROUND, value.orEmpty())

    suspend fun setUiUserBubbleHex(value: String?) = store.setString(KEY_UI_USER_BUBBLE, value.orEmpty())

    suspend fun setUiAssistantBubbleHex(value: String?) = store.setString(KEY_UI_ASSISTANT_BUBBLE, value.orEmpty())

    suspend fun setUiTextHex(value: String?) = store.setString(KEY_UI_TEXT, value.orEmpty())

    // v0.1.95: extra chat surface setters
    suspend fun setUiCodeBlockHex(value: String?) = store.setString(KEY_UI_CODE_BLOCK, value.orEmpty())

    suspend fun setUiThinkingHex(value: String?) = store.setString(KEY_UI_THINKING, value.orEmpty())

    suspend fun setUiToolCardHex(value: String?) = store.setString(KEY_UI_TOOL_CARD, value.orEmpty())

    suspend fun setUiGaugeHex(value: String?) = store.setString(KEY_UI_GAUGE, value.orEmpty())

    suspend fun setUiMonospace(value: Boolean) = store.setBoolean(KEY_UI_MONOSPACE, value)

    // v0.1.96: tool-call visibility
    suspend fun setShowToolCalls(value: Boolean) = store.setBoolean(KEY_SHOW_TOOL_CALLS, value)

    // v0.1.97: thinking visibility
    suspend fun setShowThinking(value: Boolean) = store.setBoolean(KEY_SHOW_THINKING, value)

    // v0.1.88: model picker — applied to NEW sessions (desktop-composer contract)
    val modelPick: Flow<String> = store.getString(KEY_MODEL_PICK).map { it ?: "" }
    val reasoningPick: Flow<String> = store.getString(KEY_REASONING_PICK).map { it ?: "" }

    suspend fun setModelPick(value: String) = store.setString(KEY_MODEL_PICK, value)
    suspend fun setReasoningPick(value: String) = store.setString(KEY_REASONING_PICK, value)

    suspend fun setPinnedSessionIds(ids: Set<String>) = store.setStringSet(KEY_PINNED_SESSIONS, ids)

    suspend fun togglePinned(sessionId: String) {
        val current = store.getStringSet(KEY_PINNED_SESSIONS).first()
        val next = if (sessionId in current) current - sessionId else current + sessionId
        store.setStringSet(KEY_PINNED_SESSIONS, next)
    }

    /** Apply a full appearance preset (accent + per-part overrides) atomically. */
    suspend fun applyAppearance(
        accentHex: String?,
        backgroundHex: String?,
        userBubbleHex: String?,
        assistantBubbleHex: String?,
        textHex: String?,
        // v0.1.95: extra chat surfaces — presets may set them or leave null (= derive)
        codeBlockHex: String? = null,
        thinkingHex: String? = null,
        toolCardHex: String? = null,
        gaugeHex: String? = null,
    ) {
        store.setString(KEY_ACCENT_COLOR, accentHex.orEmpty())
        store.setString(KEY_UI_BACKGROUND, backgroundHex.orEmpty())
        store.setString(KEY_UI_USER_BUBBLE, userBubbleHex.orEmpty())
        store.setString(KEY_UI_ASSISTANT_BUBBLE, assistantBubbleHex.orEmpty())
        store.setString(KEY_UI_TEXT, textHex.orEmpty())
        store.setString(KEY_UI_CODE_BLOCK, codeBlockHex.orEmpty())
        store.setString(KEY_UI_THINKING, thinkingHex.orEmpty())
        store.setString(KEY_UI_TOOL_CARD, toolCardHex.orEmpty())
        store.setString(KEY_UI_GAUGE, gaugeHex.orEmpty())
    }

    private companion object {
        const val KEY_UI_ZOOM = "ui_zoom_percent"
        const val KEY_TEXT_SCALE = "text_scale_percent"
        const val KEY_ACCENT_COLOR = "accent_color_hex"
        const val KEY_UI_BACKGROUND = "ui_background_hex"
        const val KEY_UI_USER_BUBBLE = "ui_user_bubble_hex"
        const val KEY_UI_ASSISTANT_BUBBLE = "ui_assistant_bubble_hex"
        const val KEY_UI_TEXT = "ui_text_hex"
        const val KEY_UI_CODE_BLOCK = "ui_code_block_hex"
        const val KEY_UI_THINKING = "ui_thinking_hex"
        const val KEY_UI_TOOL_CARD = "ui_tool_card_hex"
        const val KEY_UI_GAUGE = "ui_gauge_hex"
        const val KEY_UI_MONOSPACE = "ui_monospace"
        const val KEY_SHOW_TOOL_CALLS = "show_tool_calls"
        const val KEY_SHOW_THINKING = "show_thinking"
        const val KEY_PINNED_SESSIONS = "pinned_session_ids"
        const val KEY_MODEL_PICK = "model_pick"
        const val KEY_REASONING_PICK = "reasoning_pick"
    }
}
