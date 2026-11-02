package com.hermex.core.network

/** Keep a pick's provider with its model in the existing sticky string setting.
 * config.set accepts /model flags; session.create requires separate fields.
 */
data class ModelPick(val model: String, val provider: String? = null) {
    fun switchValue(): String = if (provider.isNullOrBlank()) model else "$model --provider $provider"

    companion object {
        private const val SEPARATOR = " --provider "
        fun decode(value: String): ModelPick {
            val index = value.lastIndexOf(SEPARATOR)
            if (index < 0) return ModelPick(value)
            return ModelPick(value.substring(0, index), value.substring(index + SEPARATOR.length))
        }

        fun createParams(value: String?, reasoningEffort: String?): Map<String, Any> {
            val params = mutableMapOf<String, Any>("source" to "api")
            if (!value.isNullOrBlank()) {
                val pick = decode(value)
                params["model"] = pick.model
                if (!pick.provider.isNullOrBlank()) params["provider"] = pick.provider
            }
            if (!reasoningEffort.isNullOrBlank()) params["reasoning_effort"] = reasoningEffort
            return params
        }
    }
}
