package Defaults.providers

import Defaults.GenericOpenAIConfiguration
import genericOpenAIPipe.api.GenericOpenAIEndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Verifies that the Defaults factory carries endpoint profile selection into
 * each configured GenericOpenAI pipe.
 */
class GenericOpenAIDefaultsEndpointProfileTest
{

    @Test
    fun configuredProfilePropagatesToFactoryPipe()
    {
        val pipe = GenericOpenAIDefaults.createGenericOpenAIPipe(
            GenericOpenAIConfiguration(
                model = "test-model",
                baseUrl = "http://127.0.0.1:8080",
                apiMode = "Anthropic",
                endpointProfile = GenericOpenAIEndpointProfile.localV1()
            )
        )

        assertEquals("/v1/messages", pipe.internalGetEndpointForTest())
        assertFalse(pipe.internalGetAuthHeadersForTest().containsKey("x-api-key"))
    }

    @Test
    fun omittedProfilePreservesHostedDefault()
    {
        val pipe = GenericOpenAIDefaults.createGenericOpenAIPipe(
            GenericOpenAIConfiguration(model = "test-model", apiKey = "test-key")
        )

        assertEquals("/chat/completions", pipe.internalGetEndpointForTest())
    }
}
