package com.TTT.AgentCore.LiveSmoke

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSmokeCaseCatalogTest
{
    @Test
    fun supportedScopeContainsPreviouslyUncoveredFeaturesAndExcludesOnlyPaymentsAndA2a()
    {
        assertTrue(LiveSmokeCaseCatalog.isSupported("registry.lifecycle"))
        assertTrue(LiveSmokeCaseCatalog.isSupported("gateway.forwarding"))
        assertTrue(LiveSmokeCaseCatalog.isSupported("credentials.oauth-api-key"))
        assertTrue(LiveSmokeCaseCatalog.isSupported("policy.temporal"))
        assertTrue(LiveSmokeCaseCatalog.isSupported("evaluation.insights"))
        assertTrue(LiveSmokeCaseCatalog.isSupported("runtime.capacity-provider-session-delete"))
        assertFalse(LiveSmokeCaseCatalog.isSupported("payments.lifecycle"))
        assertFalse(LiveSmokeCaseCatalog.isSupported(LiveSmokeCaseCatalog.unsupportedA2aCaseId))
        assertTrue(LiveSmokeCaseCatalog.isExplicitlyExcluded("payments.lifecycle"))
    }
}
