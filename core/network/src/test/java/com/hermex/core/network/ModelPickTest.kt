package com.hermex.core.network

import org.junit.Assert.*
import org.junit.Test

class ModelPickTest {
    @Test fun cloudSwitchPinsProviderInsteadOfInheritingLocal() {
        assertEquals("gpt-5.6-terra --provider openai-codex", ModelPick("gpt-5.6-terra", "openai-codex").switchValue())
    }
    @Test fun freshCloudSessionSendsSeparateProviderField() {
        assertEquals(mapOf("source" to "api", "model" to "gpt-5.6-terra", "provider" to "openai-codex", "reasoning_effort" to "high"),
            ModelPick.createParams(ModelPick("gpt-5.6-terra", "openai-codex").switchValue(), "high"))
    }
    @Test fun savedLocalAliasSurvivesRoundTrip() {
        val pick = ModelPick("qwen3.8-flash-next-iq3_s", "custom:clerk-xestrata")
        assertEquals(pick, ModelPick.decode(pick.switchValue()))
        assertEquals("custom:clerk-xestrata", ModelPick.createParams(pick.switchValue(), null)["provider"])
    }
    @Test fun openRouterModelNamespaceIsNotMistakenForProvider() {
        val pick = ModelPick("anthropic/claude-opus-5", "openrouter")
        assertEquals(pick, ModelPick.decode(pick.switchValue()))
        assertEquals("anthropic/claude-opus-5", ModelPick.createParams(pick.switchValue(), null)["model"])
    }
    @Test fun sameModelOnDifferentProvidersIsDifferentPick() {
        assertNotEquals(ModelPick("shared-model", "openrouter"), ModelPick("shared-model", "custom:local"))
    }
    @Test fun legacyBarePickRemainsCompatible() {
        assertEquals(ModelPick("deepseek-flash"), ModelPick.decode("deepseek-flash"))
        assertEquals(mapOf("source" to "api", "model" to "deepseek-flash"), ModelPick.createParams("deepseek-flash", null))
    }
    @Test fun noPickUsesServerDefaultWithoutSendingEmptyValues() {
        assertEquals(mapOf("source" to "api"), ModelPick.createParams(null, ""))
        assertEquals(mapOf("source" to "api"), ModelPick.createParams("", null))
    }
    @Test fun modelColonAndThinkingOffArePreserved() {
        assertEquals(mapOf("source" to "api", "model" to "qwen3-4b:latest", "provider" to "custom:ollama-gemma4", "reasoning_effort" to "off"),
            ModelPick.createParams(ModelPick("qwen3-4b:latest", "custom:ollama-gemma4").switchValue(), "off"))
    }
}
