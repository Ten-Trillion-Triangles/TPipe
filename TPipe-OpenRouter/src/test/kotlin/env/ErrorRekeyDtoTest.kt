@file:Suppress("DEPRECATION")

package env

import com.TTT.Util.deserialize
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-G: current OpenRouter errors carry an integer `code` + `metadata`; legacy string codes must still parse. */
class ErrorRekeyDtoTest
{
    @Test
    fun integerCodeAndMetadataDeserialize()
    {
        val s = """{"error":{"message":"rate limited","code":429,"metadata":{"retry_after_seconds":5,"provider_name":"prov","limit_source":"rate_limit"}}}"""
        val r = deserialize<OpenRouterErrorResponse>(s)
        assertNotNull(r)
        assertEquals(JsonPrimitive(429), r.error.code, "integer code must deserialize as JsonPrimitive")
        assertEquals("rate_limit", r.error.metadata?.get("limit_source")?.let { (it as? JsonPrimitive)?.content })
        assertEquals("5", r.error.metadata?.get("retry_after_seconds")?.let { (it as? JsonPrimitive)?.content })
    }

    @Test
    fun legacyStringCodeStillDeserializes()
    {
        val s = """{"error":{"message":"bad key","code":"invalid_api_key","type":"auth_error"}}"""
        val r = deserialize<OpenRouterErrorResponse>(s)
        assertNotNull(r)
        assertEquals(JsonPrimitive("invalid_api_key"), r.error.code)
        assertEquals("auth_error", r.error.type)
    }

    @Test
    fun paymentRequiredErrorDeserializes()
    {
        val s = """{"error":{"message":"insufficient credits","code":402,"metadata":{"provider_name":"prov"}}}"""
        val r = deserialize<OpenRouterErrorResponse>(s)
        assertNotNull(r)
        assertEquals(JsonPrimitive(402), r.error.code)
    }
}
