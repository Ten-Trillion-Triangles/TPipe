package com.TTT.Pipe

import com.TTT.PipeContextProtocol.*
import com.TTT.P2P.AgentDescriptor
import com.TTT.Util.buildSemanticDecompressionInstructions
import kotlin.test.*

/**
 * Baseline tests for system prompt injection behavior.
 * These tests validate existing PCP-only and JSON-only modes before implementing merged mode.
 */
class SystemPromptInjectionTest
{
    /**
     * Test helper to create a minimal pipe for testing prompt injection.
     */
    private fun createTestPipe(): TestPipe
    {
        return TestPipe()
    }

    // ==================== PCP-ONLY MODE TESTS ====================

    @Test
    fun `PCP-only mode injects tool context correctly`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunction"
            description = "Test function description"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setSystemPrompt("You are a test assistant.")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("You are a test assistant."))
        assertTrue(prompt.contains("Pipe Context Protocol"))
        assertTrue(prompt.contains("testFunction"))
        assertTrue(prompt.contains("IMPORTANT - How to pass arguments when calling tools"))
        assertTrue(prompt.contains("callParams"))
        assertTrue(prompt.contains("argumentsOrFunctionParams"))
    }

    @Test
    fun `PCP-only mode with stdio tools`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addStdioOption(StdioContextOptions().apply {
            command = "ls"
            args = mutableListOf("-la")
            description = "List directory"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setSystemPrompt("You are a test assistant.")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("Pipe Context Protocol"))
        assertTrue(prompt.contains("ls"))
    }

    @Test
    fun `PCP-only mode with http endpoints`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addHttpOption(HttpContextOptions().apply {
            baseUrl = "https://api.example.com"
            endpoint = "/search"
            method = "GET"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setSystemPrompt("You are a test assistant.")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("Pipe Context Protocol"))
        assertTrue(prompt.contains("api.example.com"))
    }

    @Test
    fun `PCP-only mode with custom pcpDescription override`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunction"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setPcPDescription("Custom PCP instructions here")
        pipe.applySystemPrompt()
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("Custom PCP instructions here"))
        assertFalse(prompt.contains("Pipe Context Protocol"))
    }

    // ==================== JSON-ONLY MODE TESTS ====================

    @Test
    fun `JSON-only mode injects output schema correctly`()
    {
        val pipe = createTestPipe()
        
        pipe.setJsonOutput("""{"answer": "string", "confidence": 0.0}""")
        pipe.setSystemPrompt("You are a test assistant.")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("You are a test assistant."))
        assertTrue(prompt.contains("Json format"))
        assertTrue(prompt.contains("answer"))
        assertTrue(prompt.contains("confidence"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `JSON-only mode with jsonInput and jsonOutput`()
    {
        val pipe = createTestPipe()
        
        pipe.setJsonInput("""{"query": "string"}""")
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("You are a test assistant.")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("user will provide input in the form of Json"))
        assertTrue(prompt.contains("query"))
        assertTrue(prompt.contains("result"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `JSON-only mode with custom jsonOutputInstructions`()
    {
        val pipe = createTestPipe()
        
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setJsonOutputInstructions("Custom JSON output instructions")
        pipe.applySystemPrompt()
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("Custom JSON output instructions"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `JSON input instructions auto-enable prompt injection`()
    {
        val pipe = createTestPipe()

        pipe.setJsonInputInstructions("Custom JSON input instructions")
        pipe.setJsonInput("""{"query": "string"}""")
        pipe.setSystemPrompt("You are a test assistant.")
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Explicit JSON strip mode still works`()
    {
        val pipe = createTestPipe()
        
        pipe.requireJsonPromptInjection(stripExternalText = true)
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("You are a test assistant.")
        
        assertFalse(pipe.isNativeJsonSupported())
        assertTrue(pipe.isJsonStripEnabled())
    }

    // ==================== CONFLICTING MODE TESTS (CURRENT BEHAVIOR) ====================

    @Test
    fun `Conflicting mode now uses merged mode instead`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunction"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("You are a test assistant.")
        
        val prompt = pipe.getPrompt()
        
        // New behavior: merged mode instructions
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("You may also take actions using the Pipe Context Protocol"))
        assertTrue(prompt.contains("Tool calls are optional"))
        assertTrue(prompt.contains("testFunction"))
        assertTrue(prompt.contains("result"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    // ==================== MERGED MODE TESTS ====================

    @Test
    fun `Merged mode injects unified instructions`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "searchDatabase"
            description = "Search customer database"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"answer": "string", "confidence": 0.0}""")
        pipe.setSystemPrompt("You are a helpful assistant.")
        
        val prompt = pipe.getPrompt()
        
        // Check for merged mode markers
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("answer"))
        assertTrue(prompt.contains("confidence"))
        assertTrue(prompt.contains("You may also take actions using the Pipe Context Protocol"))
        assertTrue(prompt.contains("searchDatabase"))
        assertTrue(prompt.contains("Tool calls are optional"))
        assertTrue(prompt.contains("IMPORTANT - How to pass arguments when calling tools"))
        assertTrue(prompt.contains("callParams"))
        assertTrue(prompt.contains("argumentsOrFunctionParams"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Merged mode with applySystemPrompt`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addStdioOption(StdioContextOptions().apply {
            command = "ls"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"files": []}""")
        pipe.setSystemPrompt("List files")
        pipe.applySystemPrompt()
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("files"))
        assertTrue(prompt.contains("You may also take actions using the Pipe Context Protocol"))
        assertTrue(prompt.contains("ls"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Merged mode with custom mergedPcpJsonInstructions`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunc"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setMergedPcpJsonInstructions("Custom merged mode instructions")
        pipe.applySystemPrompt()
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("Custom merged mode instructions"))
        assertFalse(prompt.contains("You must return your output in Json format"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Merged PCP JSON instructions auto-enable prompt injection`()
    {
        val pipe = createTestPipe()

        pipe.setMergedPcpJsonInstructions("Custom merged mode instructions")
        assertFalse(pipe.isNativeJsonSupported())
        pipe.setPcPContext(PcpContext().apply {
            addTPipeOption(TPipeContextOptions().apply {
                functionName = "testFunc"
            })
        })
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("Test")
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Merged mode with all tool types`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addStdioOption(StdioContextOptions().apply {
            command = "ls"
        })
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "search"
        })
        pcpContext.addHttpOption(HttpContextOptions().apply {
            baseUrl = "https://api.example.com"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"status": "string"}""")
        pipe.setSystemPrompt("Test")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("You may also take actions using the Pipe Context Protocol"))
        assertTrue(prompt.contains("ls"))
        assertTrue(prompt.contains("search"))
        assertTrue(prompt.contains("api.example.com"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Merged mode JSON output is required`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunc"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("Test")
        
        val prompt = pipe.getPrompt()
        
        // JSON output is mandatory
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("All variables in the json output must have valid values"))
        
        // Tool calls are optional
        assertTrue(prompt.contains("Tool calls are optional"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Merged mode auto-activates without explicit gate call`()
    {
        val pipe = createTestPipe()
        
        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunc"
        })
        
        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("Test")
        
        val prompt = pipe.getPrompt()
        
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("You may also take actions using the Pipe Context Protocol"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `P2P schema helpers auto-enable prompt injection`()
    {
        val pipe = createTestPipe()

        pipe.setP2PDescription("Custom agent request instructions")
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `P2P agent lists auto-enable prompt injection`()
    {
        val pipe = createTestPipe()

        pipe.setP2PAgentList(
            mutableListOf(
                AgentDescriptor(
                    agentName = "assistant",
                    description = "Assistant agent"
                )
            )
        )
        assertFalse(pipe.isNativeJsonSupported())
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.setSystemPrompt("Test")
        assertFalse(pipe.isNativeJsonSupported())
    }

    // ==================== ORDER INDEPENDENCE TESTS ====================

    @Test
    fun `applySystemPrompt rebuilds from rawSystemPrompt`()
    {
        val pipe = createTestPipe()
        
        pipe.setSystemPrompt("Original prompt")
        val firstPrompt = pipe.getPrompt()
        
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.applySystemPrompt()
        
        val secondPrompt = pipe.getPrompt()
        
        assertTrue(secondPrompt.contains("Original prompt"))
        assertTrue(secondPrompt.contains("Json format"))
        assertNotEquals(firstPrompt, secondPrompt)
        assertFalse(pipe.isNativeJsonSupported())
    }

    @Test
    fun `Configuration order does not matter with applySystemPrompt`()
    {
        val pipe1 = createTestPipe()
        pipe1.setJsonOutput("""{"result": "string"}""")
        pipe1.setSystemPrompt("Test prompt")
        val prompt1 = pipe1.getPrompt()
        
        val pipe2 = createTestPipe()
        pipe2.setSystemPrompt("Test prompt")
        pipe2.setJsonOutput("""{"result": "string"}""")
        pipe2.applySystemPrompt()
        val prompt2 = pipe2.getPrompt()
        
        // Both should produce same result after applySystemPrompt
        assertEquals(prompt1, prompt2)
        assertFalse(pipe1.isNativeJsonSupported())
        assertFalse(pipe2.isNativeJsonSupported())
    }

    @Test
    fun `Semantic decompression prelude stays ahead of merged injections`()
    {
        val pipe = createTestPipe()
        val prelude = buildSemanticDecompressionInstructions()

        val pcpContext = PcpContext()
        pcpContext.addTPipeOption(TPipeContextOptions().apply {
            functionName = "testFunction"
        })

        pipe.setPcPContext(pcpContext)
        pipe.setJsonOutput("""{"result": "string"}""")
        pipe.enableSemanticCompression()
        pipe.enableSemanticDecompression()
        pipe.setSystemPrompt("You are a test assistant.")
        pipe.applySystemPrompt()

        val prompt = pipe.getPrompt()

        assertTrue(prompt.startsWith(prelude), "The decompression prelude must be the first injected block")
        assertTrue(prompt.indexOf("You are a test assistant.") > prompt.indexOf(prelude))
        assertTrue(prompt.contains("You must return your output in Json format"))
        assertTrue(prompt.contains("Pipe Context Protocol"))
        assertFalse(pipe.isNativeJsonSupported())
    }

    // ==================== HELPER CLASS ====================

    /**
     * Minimal test pipe implementation for testing prompt injection.
     */
    private class TestPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String
        {
            return ""
        }

        override fun truncateModuleContext(): Pipe
        {
            return this
        }

        fun getPrompt(): String = systemPrompt

        fun isNativeJsonSupported(): Boolean = supportsNativeJson

        fun isJsonStripEnabled(): Boolean = stripNonJson
    }
}
