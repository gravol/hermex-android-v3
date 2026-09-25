package com.hermex.core.data.serialization

import com.hermex.core.data.models.Session
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true  // nullable defaults
    }

    @Test
    fun sessionRoundTrip_serializeThenDeserialize_returnsEqualObject() {
        val original = Session(
            sessionId = "sess_abc123",
            userId = "user_456",
            createdAt = "2026-07-15T12:00:00Z",
            isActive = true
        )

        val serialized = json.encodeToString(Session.serializer(), original)
        val deserialized = json.decodeFromString<Session>(serialized)

        assertEquals("sessionId mismatch", original.sessionId, deserialized.sessionId)
        assertEquals("userId mismatch", original.userId, deserialized.userId)
        assertEquals("createdAt mismatch", original.createdAt, deserialized.createdAt)
        assertEquals("isActive mismatch", original.isActive, deserialized.isActive)
    }

    @Test
    fun sessionRoundTrip_nullableDefaults_deserializeWithMissingFields() {
        val jsonWithMissingFields = """{"session_id":"sess_minimal"}"""

        val deserialized = json.decodeFromString<Session>(jsonWithMissingFields)

        assertEquals("sess_minimal", deserialized.sessionId)
        assertEquals(null, deserialized.userId)
        assertEquals(null, deserialized.createdAt)
        assertEquals(true, deserialized.isActive) // default
    }

    @Test
    fun sessionRoundTrip_extraUnknownKey_ignored() {
        val jsonWithExtra = """
            {
                "session_id": "sess_xyz",
                "user_id": "user_1",
                "new_field_we_dont_know": "should be ignored",
                "another_unknown": 42
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<Session>(jsonWithExtra)

        assertEquals("sess_xyz", deserialized.sessionId)
        assertEquals("user_1", deserialized.userId)
    }
}
