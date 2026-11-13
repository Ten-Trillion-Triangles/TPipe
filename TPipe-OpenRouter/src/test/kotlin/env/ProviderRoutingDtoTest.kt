package env

import com.TTT.Util.deserialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-D: OpenRouter's current `provider` routing object has 14 fields; `prefer_suffix` is stale. */
class ProviderRoutingDtoTest
{
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun newRoutingFieldsRoundTrip()
    {
        val prefs = ProviderPreferences(
            order = listOf("openai", "anthropic"),
            allowFallbacks = true,
            only = listOf("openai"),
            ignore = listOf("mistral"),
            quantizations = listOf("fp8"),
            requireParameters = true,
            enforceDistillableText = false,
            zdr = true,
            maxPrice = MaxPrice(prompt = "0.01", completion = "0.03"),
            options = buildJsonObject { put("openai", buildJsonObject { put("site", "us") }) },
            preferredMaxLatency = buildJsonObject { put("p90", 2.0) },
            preferredMinThroughput = buildJsonObject { put("p50", 5000) },
            sort = JsonPrimitive("price")
        )
        val encoded = json.encodeToString(ProviderPreferences.serializer(), prefs)
        val decoded = json.decodeFromString(ProviderPreferences.serializer(), encoded)

        assertEquals(listOf("openai", "anthropic"), decoded.order)
        assertEquals(true, decoded.allowFallbacks)
        assertEquals(listOf("openai"), decoded.only)
        assertEquals(listOf("mistral"), decoded.ignore)
        assertEquals(listOf("fp8"), decoded.quantizations)
        assertEquals(true, decoded.requireParameters)
        assertEquals(false, decoded.enforceDistillableText)
        assertEquals(true, decoded.zdr)
        assertEquals("0.01", decoded.maxPrice?.prompt)
        assertEquals("0.03", decoded.maxPrice?.completion)
        assertEquals(buildJsonObject { put("site", "us") }, decoded.options?.get("openai"))
        assertEquals(JsonPrimitive("price"), decoded.sort)
    }

    @Test
    fun preferSuffixNoLongerSerialized()
    {
        val prefs = ProviderPreferences(order = listOf("openai"))
        val encoded = json.encodeToString(ProviderPreferences.serializer(), prefs)
        assertEquals(false, encoded.contains("prefer_suffix"), "stale prefer_suffix must be gone")
    }

    @Test
    fun routingDeserializesFromSpecShape()
    {
        val raw = """{"order":["a"],"only":["x"],"ignore":["y"],"quantizations":["q"],"require_parameters":true,"enforce_distillable_text":false,"zdr":true,"max_price":{"prompt":"1","completion":"2","audio":"3","image":"4","request":"5"},"options":{"openai":{"site":"us"}},"preferred_max_latency":{"p90":1},"preferred_min_throughput":{"p50":2},"sort":{"by":"price","partition":"auto"},"allow_fallbacks":false,"data_collection":"deny"}"""
        val p = deserialize<ProviderPreferences>(raw)
        assertNotNull(p)
        assertEquals(true, p.requireParameters)
        assertEquals(false, p.enforceDistillableText)
        assertEquals(true, p.zdr)
        assertEquals("1", p.maxPrice?.prompt)
        assertEquals("deny", p.dataCollection)
        assertEquals(false, p.allowFallbacks)
    }
}
