package com.TTT.Util

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.ContextWindow
import com.TTT.Context.TodoList
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.InputSchema
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.Transport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-cutting serialization boundary test for every `@Serializable` data class
 * in `com.TTT.P2P` and `com.TTT.PipeContextProtocol` that the Kotlin 2.3
 * readiness sweep surfaced as undefined-behavior under kotlinx-serialization-json
 * 1.9.0 + Kotlin 2.2.20.
 *
 * What this pins:
 *  - `Util.serialize` / `Util.deserialize` round-trip every `@Serializable` data
 *    class that TPipe ships in the wire-protocol layer.
 *  - Body-level mutable state (defaults, `MutableMap`, `MutableList`, nullable
 *    nested objects) is preserved, including the `null`-vs-default distinction.
 *  - `Map<String, String>` and `Map<String, JsonElement>` shapes round-trip,
 *    which is the canonical reified-intersection-type call site (KTLC-13).
 *
 * Why this test is Kotlin 2.3 relevant:
 *  - Kotlin 2.3 promotes `UPPER_BOUND_VIOLATED` on implicit type arguments from
 *    warning to error (KTLC-287). The reified helpers in `Util.serialize<T>` and
 *    `Util.deserialize<T>` will surface this diagnostic if any model class has
 *    a type parameter bound that is no longer satisfiable under 2.3.
 *  - The kotlinx-serialization-core version mismatch (the root cause of the
 *    `CoercionTest` / `JsonRepairTest` quarantine in `build.gradle.kts:200-204`)
 *    must surface as a real parse failure under 2.3.0+ — i.e. if the upgrade
 *    fixes the underlying mismatch, every model in this file must round-trip
 *    without regression.
 */
class TpipeSerializationBoundaryTest
{
    //================================================ PipeProtocol / Transport

    @Test
    fun `P2PTransport round-trips keeping equals contract`() {
        val original = P2PTransport(
            transportMethod = Transport.Http,
            transportAddress = "https://agent.example.com/run",
            transportAuthBody = "sk-supersecret-do-not-leak"
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<P2PTransport>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(original.transportMethod, restored.transportMethod)
        assertEquals(original.transportAddress, restored.transportAddress)
        assertEquals(original.transportAuthBody, restored.transportAuthBody)
        // `P2PTransport.equals` is identity-by-(method,address) — wire-format
        // equality must hold even after going through JSON.
        assertEquals(original, restored)
    }

    @Test
    fun `P2PRequest with all nested fields populated round-trips`() {
        val original = P2PRequest(
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = "agent://primary"
            ),
            returnAddress = P2PTransport(
                transportMethod = Transport.Http,
                transportAddress = "https://caller.example.com/result"
            ),
            prompt = MultimodalContent("solve the bridge patching problem"),
            authBody = "bearer-token-xyz",
            contextExplanationMessage = "this context is a backup",
            context = ContextWindow().apply { contextElements.add("caller-supplied") },
            customContextDescriptions = mutableMapOf("echo" to "echoes the prompt"),
            pcpRequest = PcPRequest().apply {
                stdioContextOptions = StdioContextOptions().apply {
                    command = "ls -la"
                }
            },
            inputSchema = null,
            outputSchema = null
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<P2PRequest>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(original.transport, restored.transport)
        assertEquals(original.returnAddress, restored.returnAddress)
        assertEquals(original.prompt.text, restored.prompt.text)
        assertEquals(original.authBody, restored.authBody)
        assertEquals(original.contextExplanationMessage, restored.contextExplanationMessage)
        assertNotNull(restored.context)
        assertEquals(
            original.context!!.contextElements,
            restored.context!!.contextElements
        )
        assertEquals(original.customContextDescriptions, restored.customContextDescriptions)
        assertNotNull(restored.pcpRequest)
        assertEquals(
            original.pcpRequest!!.stdioContextOptions.command,
            restored.pcpRequest!!.stdioContextOptions.command
        )
    }

    @Test
    fun `P2PRequest with null nested fields round-trips preserving nullability`() {
        val original = P2PRequest() // every field at default, including nulls

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<P2PRequest>(json, useRepair = false)

        assertNotNull(restored)
        assertNull(restored.context)
        assertNull(restored.customContextDescriptions)
        assertNull(restored.pcpRequest)
        assertNull(restored.inputSchema)
        assertNull(restored.outputSchema)
    }

    @Test
    fun `AgentRequest with default fields round-trips`() {
        val original = AgentRequest(
            agentName = "summarizer",
            promptSchema = InputSchema.json,
            prompt = "summarize the doc",
            content = "doc body",
            pcpRequest = PcPRequest()
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<AgentRequest>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(original.agentName, restored.agentName)
        assertEquals(original.promptSchema, restored.promptSchema)
        assertEquals(original.prompt, restored.prompt)
        assertEquals(original.content, restored.content)
    }

    //================================================ P2PResponse

    @Test
    fun `P2PResponse with only output round-trips`() {
        val original = P2PResponse(
            output = MultimodalContent("here is the answer")
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<P2PResponse>(json, useRepair = false)

        assertNotNull(restored)
        assertNotNull(restored.output)
        assertEquals("here is the answer", restored.output!!.text)
        assertNull(restored.rejection)
    }

    @Test
    fun `P2PResponse with only rejection round-trips`() {
        val original = P2PResponse(
            rejection = P2PRejection(
                errorType = P2PError.auth,
                reason = "token expired"
            )
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<P2PResponse>(json, useRepair = false)

        assertNotNull(restored)
        assertNull(restored.output)
        assertNotNull(restored.rejection)
        assertEquals(P2PError.auth, restored.rejection!!.errorType)
        assertEquals("token expired", restored.rejection!!.reason)
    }

    @Test
    fun `P2PRejection with every enum value round-trips`() {
        for(errorType in P2PError.values()) {
            val original = P2PRejection(errorType = errorType, reason = "x")
            val json = serialize(original, encodedefault = true)
            val restored = deserialize<P2PRejection>(json, useRepair = false)
            assertNotNull(restored, "Failed to deserialize P2PError.$errorType")
            assertEquals(errorType, restored.errorType, "Round-trip lost enum value for $errorType")
        }
    }

    //================================================ P2PDescriptor

    @Test
    fun `P2PDescriptor with all required fields populated round-trips`() {
        val original = P2PDescriptor(
            agentName = "alpha",
            agentDescription = "alpha agent",
            transport = P2PTransport(Transport.Http, "https://alpha"),
            requiresAuth = true,
            usesConverse = true,
            allowsAgentDuplication = true,
            allowsCustomContext = true,
            allowsCustomAgentJson = true,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = true,
            contextProtocol = ContextProtocol.pcp,
            supportedContentTypes = mutableListOf(
                SupportedContentTypes.text,
                SupportedContentTypes.image
            ),
            agentSkills = mutableListOf(
                P2PSkills(skillName = "summarize", skillDescription = "summarize a doc")
            )
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<P2PDescriptor>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(original.agentName, restored.agentName)
        assertEquals(original.transport, restored.transport)
        assertEquals(original.requiresAuth, restored.requiresAuth)
        assertEquals(original.usesConverse, restored.usesConverse)
        assertEquals(original.contextProtocol, restored.contextProtocol)
        assertEquals(original.supportedContentTypes, restored.supportedContentTypes)
        assertEquals(original.agentSkills?.size, restored.agentSkills?.size)
        assertEquals(
            original.agentSkills?.first()?.skillName,
            restored.agentSkills?.first()?.skillName
        )
    }

    //================================================ PipeContextProtocol

    @Test
    fun `PcpContext round-trips with nested StdioContextOptions list`() {
        val original = PcpContext().apply {
            stdioOptions.add(
                StdioContextOptions().apply {
                    command = "cat /etc/hostname"
                    description = "hostname"
                }
            )
        }

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<PcpContext>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(1, restored.stdioOptions.size)
        assertEquals("hostname", restored.stdioOptions.first().description)
        assertEquals("cat /etc/hostname", restored.stdioOptions.first().command)
    }

    @Test
    fun `PcPRequest default round-trips`() {
        val original = PcPRequest()

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<PcPRequest>(json, useRepair = false)

        assertNotNull(restored)
        assertNotNull(restored.stdioContextOptions)
    }

    //================================================ Context

    @Test
    fun `ContextWindow with mutable body state round-trips with the right shape`() {
        val original = ContextWindow().apply {
            contextElements.add("alpha")
            contextElements.add("beta")
            converseHistory = ConverseHistory(
                mutableListOf(
                    ConverseData(
                        role = ConverseRole.user,
                        content = MultimodalContent("ping")
                    )
                )
            )
        }

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<ContextWindow>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(original.contextElements, restored.contextElements)
        assertEquals(1, restored.converseHistory?.history?.size)
        assertEquals("ping", restored.converseHistory?.history?.first()?.content?.text)
    }

    @Test
    fun `TodoList with a populated workHistory round-trips`() {
        val original = TodoList(
            workHistory = ConverseHistory(
                mutableListOf(
                    ConverseData(
                        role = ConverseRole.assistant,
                        content = MultimodalContent("done")
                    )
                )
            )
        )

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<TodoList>(json, useRepair = false)

        assertNotNull(restored)
        assertEquals(1, restored.workHistory.history.size)
        assertEquals("done", restored.workHistory.history.first().content.text)
    }

    //================================================ Cross-class reified inference

    @Test
    fun `serialize and deserialize of a JsonObject reified type round-trips`() {
        // KTLC-13 shape: `JsonElement` is an intersection type, and the reified
        // helper `Util.serialize<JsonElement>` must remain valid under Kotlin 2.3.
        val original = buildJsonObject {
            put("string", JsonPrimitive("value"))
            put("int", JsonPrimitive(42))
            put("bool", JsonPrimitive(true))
            put("list", buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            })
            put("null", JsonNull)
        }

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<JsonElement>(json, useRepair = false)

        assertNotNull(restored)
        val restoredObj = restored.jsonObject
        assertEquals("value", restoredObj["string"]?.jsonPrimitive?.content)
        assertEquals("42", restoredObj["int"]?.jsonPrimitive?.content)
        assertEquals("true", restoredObj["bool"]?.jsonPrimitive?.content)
        val listArr = restoredObj["list"]?.jsonArray
            ?: fail("'list' should be a JsonArray")
        assertEquals(2, listArr.size)
        assertEquals("a", listArr[0].jsonPrimitive.content)
        assertTrue(restoredObj.containsKey("null"))
        assertEquals(JsonNull, restoredObj["null"])
    }

    @Test
    fun `serialize and deserialize of a reified JsonArray round-trips`() {
        val original = buildJsonArray {
            add(JsonPrimitive("a"))
            add(JsonPrimitive(1))
            add(JsonPrimitive(true))
            add(buildJsonArray {
                add(JsonPrimitive("nested"))
            })
        }

        val json = serialize(original, encodedefault = true)
        val restored = deserialize<JsonElement>(json, useRepair = false)

        assertNotNull(restored)
        val arr = restored.jsonArray
        assertEquals(4, arr.size)
        assertEquals("a", arr[0].jsonPrimitive.content)
        assertEquals("1", arr[1].jsonPrimitive.content)
        assertEquals("true", arr[2].jsonPrimitive.content)
        assertEquals("nested", arr[3].jsonArray[0].jsonPrimitive.content)
    }
}
