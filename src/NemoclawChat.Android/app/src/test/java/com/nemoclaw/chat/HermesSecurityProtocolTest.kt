package com.nemoclaw.chat

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comportamentali puri: HermesUrlSecurity (no android.*) + HermesHubProtocol. */
class HermesSecurityProtocolTest {

    // ---- sameHttpOrigin ----

    @Test fun sameOrigin_matchesExactly() {
        assertTrue(sameHttpOrigin(URI("http://10.0.0.2:8642/v1/models"), URI("http://10.0.0.2:8642")))
    }

    @Test fun sameOrigin_rejectsDifferentHost() {
        assertFalse(sameHttpOrigin(URI("http://10.0.0.3:8642/v1/models"), URI("http://10.0.0.2:8642")))
    }

    @Test fun sameOrigin_rejectsSchemeMismatch() {
        assertFalse(sameHttpOrigin(URI("https://10.0.0.2:8642/v1/models"), URI("http://10.0.0.2:8642")))
    }

    @Test fun sameOrigin_treatsDefaultPortsAsEqual() {
        assertTrue(sameHttpOrigin(URI("http://10.0.0.2/v1/models"), URI("http://10.0.0.2:80")))
        assertTrue(sameHttpOrigin(URI("https://10.0.0.2/v1/models"), URI("https://10.0.0.2:443")))
    }

    @Test fun sameOrigin_isCaseInsensitive() {
        assertTrue(sameHttpOrigin(URI("HTTP://10.0.0.2:8642/v1/models"), URI("http://10.0.0.2:8642")))
    }

    @Test fun sameOrigin_rejectsNonHttpSchemes() {
        assertFalse(sameHttpOrigin(URI("ws://10.0.0.2:8642/v1/models"), URI("ws://10.0.0.2:8642")))
    }

    // ---- shouldAuthenticateHermesUrl ----

    private fun settingsAt(root: String) = AppSettings(gatewayUrl = root)

    @Test fun auth_requiredForV1OnConfiguredOrigin() {
        assertTrue(shouldAuthenticateHermesUrl(settingsAt("http://10.0.0.2:8642"), "http://10.0.0.2:8642/v1/models"))
    }

    @Test fun auth_notRequiredOffV1Path() {
        assertFalse(shouldAuthenticateHermesUrl(settingsAt("http://10.0.0.2:8642"), "http://10.0.0.2:8642/health"))
    }

    @Test fun auth_notRequiredForForeignOrigin() {
        assertFalse(shouldAuthenticateHermesUrl(settingsAt("http://10.0.0.2:8642"), "http://169.254.169.254/v1/models"))
    }

    @Test fun auth_acceptsAdditionalGatewayRoot() {
        val s = settingsAt("http://10.0.0.2:8642")
        assertTrue(shouldAuthenticateHermesUrl(s, "http://192.168.1.6:8642/v1/models", listOf("http://192.168.1.6:8642")))
    }

    // ---- HermesHubProtocol ----

    private fun validEnvelopeJson() = JSONObject()
        .put("protocol_version", 1)
        .put("type", "message")
        .put("payload", JSONObject().put("text", "ciao"))
        .put("sequence", 3)
        .put("event_id", "evt_1")
        .put("request_id", "req_abc")
        .put("correlation_id", "corr_abc")
        .put("source_type", "hermes-gateway")

    @Test fun envelope_acceptsValidEvent() {
        val env = HermesHubProtocol.readEventEnvelope(validEnvelopeJson())
        assertNotNull(env)
        assertEquals(3L, env!!.sequence)
        assertEquals("message", env.type)
        assertEquals("hermes-gateway", env.sourceType)
    }

    @Test fun envelope_rejectsWrongProtocolVersion() {
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("protocol_version", 2)))
    }

    @Test fun envelope_rejectsMissingPayload() {
        val json = validEnvelopeJson()
        json.remove("payload")
        assertNull(HermesHubProtocol.readEventEnvelope(json))
    }

    @Test fun envelope_rejectsNegativeAndFractionalSequence() {
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("sequence", -1)))
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("sequence", 1.5)))
    }

    @Test fun envelope_rejectsBadWireIds() {
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("event_id", "")))
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("request_id", "has space")))
    }

    @Test fun envelope_rejectsUnknownSourceTypeAndOversizeType() {
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("source_type", "evil")))
        assertNull(HermesHubProtocol.readEventEnvelope(validEnvelopeJson().put("type", "x".repeat(161))))
    }

    @Test fun correlationContext_hasPrefixedDistinctIds() {
        val a = HermesHubProtocol.newCorrelationContext()
        val b = HermesHubProtocol.newCorrelationContext()
        assertTrue(a.requestId.startsWith("req_"))
        assertTrue(a.correlationId.startsWith("corr_"))
        assertEquals(a.requestId.removePrefix("req_"), a.correlationId.removePrefix("corr_"))
        assertNotEquals(a.requestId, b.requestId)
    }
}
