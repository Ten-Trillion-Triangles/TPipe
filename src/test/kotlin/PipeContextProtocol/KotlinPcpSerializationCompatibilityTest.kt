package com.TTT.PipeContextProtocol

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Pins production-path JSON for the public PCP Kotlin-related models. */
class KotlinPcpSerializationCompatibilityTest
{
    private val json = Json
    private val serializationGoldens by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(
            "PipeContextProtocol/kotlin-scripting/serialization-goldens.json"
        ))
        stream.use { input ->
            json.parseToJsonElement(input.reader().readText()).jsonObject
        }
    }

    @Test
    fun `default and populated Kotlin PCP models round trip without wire changes`() {
        val kotlinContext = KotlinContext().apply {
            allowedImports += "kotlin.math.*"
            allowHostApplicationAccess = true
            exposedBindings["host"] = "host fixture"
            timeoutMs = 1234
        }
        val pcpContext = PcpContext().apply {
            transport = Transport.Kotlin
            kotlinOptions = kotlinContext
            currentUserId = "compatibility-user"
        }
        val defaultPcpContext = PcpContext().apply {
            currentUserId = "compatibility-user"
        }
        val request = PcPRequest(
            kotlinContextOptions = kotlinContext,
            argumentsOrFunctionParams = listOf("host")
        )
        val output = BufferedOutput(
            stdout = "out",
            stderr = "err",
            binary = null,
            totalBytes = 6,
            truncated = false
        )
        val requestResult = PcpRequestResult(
            success = true,
            output = "Result: 4",
            executionTimeMs = 321,
            transport = Transport.Kotlin,
            outputBuffer = output
        )

        val defaultKotlinContext = KotlinContext()
        val defaultRequest = PcPRequest()
        val defaultOutput = BufferedOutput(null, null, null, 0, false)
        val defaultResult = PcpRequestResult(false, "", 0, Transport.Kotlin)

        assertGolden("defaultKotlinContext", serialize(defaultKotlinContext, true))
        assertGolden("populatedKotlinContext", serialize(kotlinContext, true))
        assertGolden("defaultPcpContext", serialize(defaultPcpContext, true))
        assertGolden("populatedPcpContext", serialize(pcpContext, true))
        assertGolden("defaultRequest", serialize(defaultRequest, true))
        assertGolden("populatedRequest", serialize(request, true))
        assertGolden("defaultOutput", serialize(defaultOutput, true))
        assertGolden("populatedOutput", serialize(output, true))
        assertGolden("defaultResult", serialize(defaultResult, true))
        assertGolden("populatedResult", serialize(requestResult, true))

        assertRoundTrip(
            serialize(defaultKotlinContext, true),
            serialize(deserialize<KotlinContext>(serialize(defaultKotlinContext, true)), true)
        )
        assertRoundTrip(
            serialize(kotlinContext, true),
            serialize(deserialize<KotlinContext>(serialize(kotlinContext, true)), true)
        )
        assertRoundTrip(
            serialize(defaultPcpContext, true),
            serialize(deserialize<PcpContext>(serialize(defaultPcpContext, true)), true)
        )
        assertRoundTrip(
            serialize(pcpContext, true),
            serialize(deserialize<PcpContext>(serialize(pcpContext, true)), true)
        )
        assertRoundTrip(
            serialize(defaultRequest, true),
            serialize(deserialize<PcPRequest>(serialize(defaultRequest, true)), true)
        )
        assertRoundTrip(
            serialize(request, true),
            serialize(deserialize<PcPRequest>(serialize(request, true)), true)
        )
        assertEquals(
            normalizeExecutionTime(serialize(defaultResult, true)),
            normalizeExecutionTime(serialize(deserialize<PcpRequestResult>(serialize(defaultResult, true))!!, true))
        )
        assertRoundTrip(
            serialize(defaultOutput, true),
            serialize(deserialize<BufferedOutput>(serialize(defaultOutput, true)), true)
        )
        assertRoundTrip(
            serialize(output, true),
            serialize(deserialize<BufferedOutput>(serialize(output, true)), true)
        )
    }

    private fun assertGolden(id: String, actual: String)
    {
        assertEquals(serializationGoldens.getValue(id), json.parseToJsonElement(actual), id)
    }

    private fun assertRoundTrip(original: String, decoded: String?)
    {
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    private fun normalizeExecutionTime(serialized: String): String
    {
        val objectValue = json.parseToJsonElement(serialized).jsonObject.toMutableMap()
        objectValue["executionTimeMs"] = JsonPrimitive(0)
        return JsonObject(objectValue).toString()
    }
}
