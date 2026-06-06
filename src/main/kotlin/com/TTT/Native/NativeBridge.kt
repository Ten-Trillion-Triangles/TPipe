package com.TTT.Native

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.LoreBook
import com.TTT.Context.MiniBank
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.Splitter
import com.TTT.Native.BinaryHandle.BinaryVariant
import com.TTT.Native.EnumMappings.LibraryState
import com.TTT.Native.EnumMappings.OperationStatus
import com.TTT.Native.EnumMappings.ProviderName
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * NativeBridge — Kotlin-side helper that exposes HandleRegistry, ContentHandle,
 * and other internal Kotlin state to the Java TPipeBootstrap shim.
 *
 * The Java @CEntryPoint methods use only long/int/Object parameters (no Word
 * or CCharPointer), so they cannot access Kotlin singletons directly. This
 * bridge provides a stable Kotlin API for the bootstrap to call.
 */
object NativeBridge {

    //====================================================================
    // Library state (mirrors LibraryState enum)
    //====================================================================

    private val stateLock = ReentrantLock()
    private val libraryStateRef = AtomicInteger(LibraryState.UNINITIALIZED.cValue)
    private val lastError = ThreadLocal<String?>()

    @JvmStatic fun getState(): Int = libraryStateRef.get()
    @JvmStatic fun setState(s: Int) { libraryStateRef.set(s) }
    @JvmStatic fun isReady(): Boolean = getState() == LibraryState.READY.cValue
    @JvmStatic fun getLastError(): String? = lastError.get()
    @JvmStatic fun setLastError(msg: String?) { lastError.set(msg) }

    @JvmStatic
    @Synchronized
    fun init(): Int {
        stateLock.lock()
        try {
            val s = getState()
            if (s == LibraryState.READY.cValue) return 0
            if (s != LibraryState.UNINITIALIZED.cValue && s != LibraryState.SHUTDOWN.cValue) {
                return -0x12 // INVALID_STATE
            }
            setState(LibraryState.INITIALIZING.cValue)
            HandleRegistry.closeAll()
            setState(LibraryState.READY.cValue)
            return 0
        } finally {
            stateLock.unlock()
        }
    }

    @JvmStatic
    @Synchronized
    fun shutdown(): Int {
        stateLock.lock()
        try {
            val s = getState()
            if (s == LibraryState.SHUTDOWN.cValue) return 0
            if (s != LibraryState.READY.cValue) return -0x12
            setState(LibraryState.SHUTTING_DOWN.cValue)
            HandleRegistry.closeAll()
            setState(LibraryState.SHUTDOWN.cValue)
            return 0
        } finally {
            stateLock.unlock()
        }
    }

    //====================================================================
    // Content
    //====================================================================

    @JvmStatic fun contentCreate(text: String?): Long {
        val ch = ContentHandle(text ?: "")
        return HandleRegistry.allocate(HandleTypes.CONTENT, ch)
    }

    @JvmStatic fun contentGetText(handle: Long): String? =
        HandleRegistry.getData(handle)?.let { (it as ContentHandle).text }

    @JvmStatic fun contentSetText(handle: Long, text: String?) {
        val ch = HandleRegistry.getData(handle) as? ContentHandle ?: return
        ch.text = text ?: ""
    }

    @JvmStatic fun contentGetContext(handle: Long): String? =
        (HandleRegistry.getData(handle) as? ContentHandle)?.context

    @JvmStatic fun contentSetContext(handle: Long, ctx: String?) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.context = ctx
    }

    @JvmStatic fun contentGetMiniBank(handle: Long): String? =
        (HandleRegistry.getData(handle) as? ContentHandle)?.miniBank

    @JvmStatic fun contentSetMiniBank(handle: Long, mb: String?) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.miniBank = mb
    }

    @JvmStatic fun contentGetJumpTo(handle: Long): String? =
        (HandleRegistry.getData(handle) as? ContentHandle)?.jump

    @JvmStatic fun contentSetJumpTo(handle: Long, jump: String?) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.jump = jump
    }

    @JvmStatic fun contentClearJumpTo(handle: Long) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.jump = null
    }

    @JvmStatic fun contentSetTerminate(handle: Long, terminate: Boolean) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.terminate = terminate
    }

    @JvmStatic fun contentGetTerminate(handle: Long): Boolean =
        (HandleRegistry.getData(handle) as? ContentHandle)?.terminate ?: false

    @JvmStatic fun contentSetPass(handle: Long, pass: Boolean) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.pass = pass
    }

    @JvmStatic fun contentSetRepeat(handle: Long, repeat: Boolean) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.repeat = repeat
    }

    @JvmStatic fun contentSetSkipReasoning(handle: Long, skip: Boolean) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.skip = skip
    }

    @JvmStatic fun contentClearRepeat(handle: Long) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.repeat = false
    }

    @JvmStatic fun contentGetRepeat(handle: Long): Boolean =
        (HandleRegistry.getData(handle) as? ContentHandle)?.repeat ?: false

    @JvmStatic fun contentGetSkip(handle: Long): Boolean =
        (HandleRegistry.getData(handle) as? ContentHandle)?.skip ?: false

    @JvmStatic fun contentGetJump(handle: Long): Boolean {
        val j = (HandleRegistry.getData(handle) as? ContentHandle)?.jump
        return j != null && j.isNotEmpty()
    }

    @JvmStatic fun contentSetJump(handle: Long, jump: Boolean) {
        val ch = HandleRegistry.getData(handle) as? ContentHandle ?: return
        ch.jump = if (jump) (ch.jump?.takeIf { it.isNotEmpty() } ?: "default") else null
    }

    @JvmStatic fun contentSetRepeatPipe(handle: Long, pipeName: String?) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.jump = pipeName
    }

    @JvmStatic fun contentSetJumpToPipe(handle: Long, pipeName: String?) {
        (HandleRegistry.getData(handle) as? ContentHandle)?.jump = pipeName
    }

    @JvmStatic fun contentAddBinary(
        handle: Long, variant: Int, data: ByteArray?, mime: String?, filename: String?
    ): Int {
        val ch = HandleRegistry.getData(handle) as? ContentHandle ?: return -0x03
        val v = BinaryVariant.values().getOrNull(variant) ?: return -0x04
        if (data != null && data.size.toLong() > BinaryHandle.MAX_BINARY_SIZE) return -0x1D
        val bh = BinaryHandle(
            variant = v,
            bytes = if (v == BinaryVariant.BYTES) data else null,
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = mime ?: "application/octet-stream",
            filename = filename
        )
        ch.binaryContent.add(bh)
        return 0
    }

    @JvmStatic fun contentGetBinaryJson(handle: Long, index: Int): String? {
        val ch = HandleRegistry.getData(handle) as? ContentHandle ?: return null
        val bh = ch.binaryContent.getOrNull(index) ?: return null
        return """{"variant":${EnumMappings.BinaryVariant.toInt(bh.variant)},"size":${bh.bytes?.size ?: 0}}"""
    }

    @JvmStatic fun contentGetBinariesJson(handle: Long): String? {
        val ch = HandleRegistry.getData(handle) as? ContentHandle ?: return null
        val parts = ch.binaryContent.joinToString(",") { bh ->
            """{"variant":${EnumMappings.BinaryVariant.toInt(bh.variant)},"size":${bh.bytes?.size ?: 0}}"""
        }
        return "[$parts]"
    }

    @JvmStatic fun contentClearBinary(handle: Long) {
        val ch = HandleRegistry.getData(handle) as? ContentHandle ?: return
        ch.binaryContent.forEach { it.sanitize() }
        ch.binaryContent.clear()
    }

    @JvmStatic fun contentClone(handle: Long): Long {
        val src = HandleRegistry.getData(handle) as? ContentHandle ?: return -1L
        val copy = ContentHandle(src.text)
        copy.terminate = src.terminate
        copy.repeat = src.repeat
        copy.pass = src.pass
        copy.skip = src.skip
        copy.jump = src.jump
        copy.errorMessage = src.errorMessage
        copy.context = src.context
        copy.miniBank = src.miniBank
        copy.modelReasoning = src.modelReasoning
        for (bh in src.binaryContent) copy.binaryContent.add(bh.clone())
        return HandleRegistry.allocate(HandleTypes.CONTENT, copy)
    }

    //====================================================================
    // Binary
    //====================================================================

    @JvmStatic fun binaryCreate(
        variant: Int, data: ByteArray?, mime: String?, filename: String?
    ): Long {
        val v = BinaryVariant.values().getOrNull(variant) ?: return -1L
        if (data != null && data.size.toLong() > BinaryHandle.MAX_BINARY_SIZE) return -1L
        val bh = BinaryHandle(
            variant = v,
            bytes = if (v == BinaryVariant.BYTES) data else null,
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = mime ?: "application/octet-stream",
            filename = filename
        )
        return HandleRegistry.allocate(HandleTypes.BINARY, bh)
    }

    @JvmStatic fun binaryCreateEmpty(): Long {
        val bh = BinaryHandle(
            variant = BinaryVariant.BYTES,
            bytes = ByteArray(0),
            base64Data = null, cloudRef = null, textDocRef = null,
            mimeType = "application/octet-stream", filename = null
        )
        return HandleRegistry.allocate(HandleTypes.BINARY, bh)
    }

    @JvmStatic fun binaryGetVariant(handle: Long): Int {
        val bh = HandleRegistry.getData(handle) as? BinaryHandle ?: return -0x03
        return EnumMappings.BinaryVariant.toInt(bh.variant)
    }

    @JvmStatic fun binaryGetBytes(handle: Long): ByteArray? =
        (HandleRegistry.getData(handle) as? BinaryHandle)?.bytes

    //====================================================================
    // Pipe
    //====================================================================

    @JvmStatic fun pipeCreate(
        provider: Int, model: String, region: String, settingsHandle: Long
    ): Long {
        val pn = ProviderName.fromInt(provider)
        val ps = if (settingsHandle == 0L) {
            PipeSettingsHandle.create().setModel(model).setRegion(region).setProvider(pn.name)
        } else {
            (HandleRegistry.getData(settingsHandle) as? PipeSettingsHandle)
                ?.setModel(model)?.setRegion(region)?.setProvider(pn.name)
                ?: return -1L
        }
        val pipe: com.TTT.Pipe.Pipe = when (pn) {
            ProviderName.OLLAMA -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val ollamaCls = Class.forName("ollamaPipe.OllamaPipe")
                            as Class<out com.TTT.Pipe.Pipe>
                    val ctor = ollamaCls.getDeclaredConstructor()
                    val ollama = ctor.newInstance()
                    ollama.setModel(model)
                } catch (e: ClassNotFoundException) {
                    lastError.set("OllamaPipe class not found: ${e.message}")
                    return -1L
                } catch (e: NoSuchMethodException) {
                    lastError.set("OllamaPipe constructor not found: ${e.message}")
                    return -1L
                } catch (e: Exception) {
                    lastError.set("OllamaPipe construction failed: ${e.message}")
                    return -1L
                }
            }
            ProviderName.BEDROCK -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val bedrockCls = Class.forName("bedrockPipe.BedrockPipe")
                            as Class<out com.TTT.Pipe.Pipe>
                    val ctor = bedrockCls.getDeclaredConstructor()
                    val bedrock = ctor.newInstance() as com.TTT.Pipe.Pipe
                    bedrock.setModel(model)
                    // setRegion is a BedrockPipe-specific method (not on base Pipe);
                    // call via reflection so the compiler does not need to see the symbol.
                    bedrockCls.getMethod("setRegion", String::class.java)
                        .invoke(bedrock, region)
                    bedrock
                } catch (e: ClassNotFoundException) {
                    lastError.set("BedrockPipe class not found: ${e.message}")
                    return -1L
                } catch (e: NoSuchMethodException) {
                    lastError.set("BedrockPipe method not found: ${e.message}")
                    return -1L
                } catch (e: Exception) {
                    lastError.set("BedrockPipe construction failed: ${e.message}")
                    return -1L
                }
            }
            ProviderName.OPENROUTER -> {
                try
                {
                    @Suppress("UNCHECKED_CAST")
                    val openRouterCls = Class.forName("openrouterPipe.OpenRouterPipe")
                            as Class<out com.TTT.Pipe.Pipe>
                    val ctor = openRouterCls.getDeclaredConstructor()
                    val openRouter = ctor.newInstance()
                    openRouter.setModel(model)
                }
                catch(e: ClassNotFoundException)
                {
                    lastError.set("OpenRouterPipe class not found: ${e.message}")
                    return -1L
                }
                catch(e: NoSuchMethodException)
                {
                    lastError.set("OpenRouterPipe constructor not found: ${e.message}")
                    return -1L
                }
                catch(e: Exception)
                {
                    lastError.set("OpenRouterPipe construction failed: ${e.message}")
                    return -1L
                }
            }
            ProviderName.GENERIC_OPENAI -> {
                try
                {
                    @Suppress("UNCHECKED_CAST")
                    val genericCls = Class.forName("genericOpenAIPipe.GenericOpenAIPipe")
                            as Class<out com.TTT.Pipe.Pipe>
                    val ctor = genericCls.getDeclaredConstructor()
                    val generic = ctor.newInstance()
                    generic.setModel(model)
                }
                catch(e: ClassNotFoundException)
                {
                    lastError.set("GenericOpenAIPipe class not found: ${e.message}")
                    return -1L
                }
                catch(e: NoSuchMethodException)
                {
                    lastError.set("GenericOpenAIPipe constructor not found: ${e.message}")
                    return -1L
                }
                catch(e: Exception)
                {
                    lastError.set("GenericOpenAIPipe construction failed: ${e.message}")
                    return -1L
                }
            }
            else -> {
                val stub = com.TTT.Pipe.DummyPipe()
                try {
                    val modelF = com.TTT.Pipe.DummyPipe::class.java.getDeclaredField("model")
                    modelF.isAccessible = true
                    modelF.set(stub, model)
                } catch (_: Exception) { /* field may be absent on subclasses; ignore */ }
                stub
            }
        }
        val ph = PipeHandle(pipe, ps)
        return HandleRegistry.allocate(HandleTypes.PIPE, ph)
    }

    @JvmStatic fun pipeSetProvider(handle: Long, provider: Int) {
        val ph = HandleRegistry.getData(handle) as? PipeHandle ?: return
        ph.settings.setProvider(ProviderName.fromInt(provider).name)
    }

    @JvmStatic fun pipeSetTemperature(handle: Long, t: Float) {
        (HandleRegistry.getData(handle) as? PipeHandle)?.settings?.setTemperature(t)
    }

    @JvmStatic fun pipeSetRepetitionPenalty(handle: Long, p: Float) {
        (HandleRegistry.getData(handle) as? PipeHandle)?.settings?.setRepetitionPenalty(p)
    }

    @JvmStatic fun pipeSetReasoning(handle: Long, r: Int) {
        (HandleRegistry.getData(handle) as? PipeHandle)?.settings?.setReasoning(r)
    }

    @JvmStatic fun pipeInit(pipe: Long, content: Long, context: Long): Int {
        if (HandleRegistry.getData(pipe) !is PipeHandle) return -0x03
        if (content != 0L && HandleRegistry.getType(content) != HandleTypes.CONTENT) return -0x13
        if (context != 0L && HandleRegistry.getType(context) != HandleTypes.CONTEXT) return -0x13
        return 0
    }

    @JvmStatic fun pipeExecute(pipe: Long, content: Long): Long {
        val ph = HandleRegistry.getData(pipe) as? PipeHandle ?: return 0L
        val ch = HandleRegistry.getData(content) as? ContentHandle ?: return 0L
        return try {
            val r = ph.execute(ch)
            when (r) {
                is PipeHandle.Result.Success -> {
                    val op = OperationHandle(OperationStatus.COMPLETE, r.handleId, null)
                    HandleRegistry.allocate(HandleTypes.OPERATION, op)
                }
                is PipeHandle.Result.Error -> {
                    lastError.set(r.message)
                    val op = OperationHandle(OperationStatus.FAILED, 0L, r.message)
                    HandleRegistry.allocate(HandleTypes.OPERATION, op)
                }
            }
        } catch (e: Exception) {
            lastError.set(e.message)
            val op = OperationHandle(OperationStatus.FAILED, 0L, e.message)
            HandleRegistry.allocate(HandleTypes.OPERATION, op)
        }
    }

    @JvmStatic fun pipeExecuteAsync(pipe: Long, content: Long): Long {
        val ph = HandleRegistry.getData(pipe) as? PipeHandle ?: return 0L
        val ch = HandleRegistry.getData(content) as? ContentHandle ?: return 0L
        return try {
            val r = ph.executeAsync(ch)
            when (r) {
                is PipeHandle.Result.Success -> r.handleId
                is PipeHandle.Result.Error -> {
                    lastError.set(r.message)
                    val op = OperationHandle(OperationStatus.FAILED, 0L, r.message)
                    HandleRegistry.allocate(HandleTypes.OPERATION, op)
                }
            }
        } catch (e: Exception) {
            lastError.set(e.message)
            val op = OperationHandle(OperationStatus.FAILED, 0L, e.message)
            HandleRegistry.allocate(HandleTypes.OPERATION, op)
        }
    }

    @JvmStatic fun pipeGetTokenUsage(pipe: Long): IntArray {
        val ph = HandleRegistry.getData(pipe) as? PipeHandle ?: return IntArray(4)
        return intArrayOf(0, 0, 0, 0) // DummyPipe does not expose token usage
    }

    @JvmStatic fun operationGetResult(operationHandle: Long): Long {
        val op = HandleRegistry.getData(operationHandle) as? OperationHandle ?: return 0L
        return op.resultHandle
    }

    //====================================================================
    // PipeSettings
    //====================================================================

    @JvmStatic fun pipeSettingsCreate(): Long {
        val ps = PipeSettingsHandle.create()
        return HandleRegistry.allocate(HandleTypes.PIPE_SETTINGS, ps)
    }

    @JvmStatic fun pipeSettingsSetModel(handle: Long, model: String) {
        (HandleRegistry.getData(handle) as? PipeSettingsHandle)?.setModel(model)
    }

    @JvmStatic fun pipeSettingsSetTemperature(handle: Long, t: Float) {
        (HandleRegistry.getData(handle) as? PipeSettingsHandle)?.setTemperature(t)
    }

    @JvmStatic fun pipeSettingsSetMaxTokens(handle: Long, max: Int) {
        (HandleRegistry.getData(handle) as? PipeSettingsHandle)?.setMaxTokens(max)
    }

    @JvmStatic fun pipeSettingsSetTimeout(handle: Long, ms: Int) {
        (HandleRegistry.getData(handle) as? PipeSettingsHandle)?.setTimeout(ms)
    }

    @JvmStatic fun pipeSettingsSetProvider(handle: Long, provider: Int) {
        (HandleRegistry.getData(handle) as? PipeSettingsHandle)
            ?.setProvider(ProviderName.fromInt(provider).name)
    }

    @JvmStatic fun pipeSettingsSetString(handle: Long, key: String, value: String) {
        val ps = HandleRegistry.getData(handle) as? PipeSettingsHandle ?: return
        when (key) {
            "model" -> ps.setModel(value)
            "system" -> ps.setSystemPrompt(value)
            "json" -> ps.setJsonOutput(value)
            "region" -> ps.setRegion(value)
            else -> ps.setStopSequences(listOf(value))
        }
    }

    @JvmStatic fun pipeSettingsSetInt(handle: Long, key: String, value: Int) {
        val ps = HandleRegistry.getData(handle) as? PipeSettingsHandle ?: return
        when (key) {
            "maxTokens" -> ps.setMaxTokens(value)
            "timeoutMs" -> ps.setTimeout(value)
            "reasoning" -> ps.setReasoning(value)
        }
    }

    @JvmStatic fun pipeSettingsSetFloat(handle: Long, key: String, value: Float) {
        val ps = HandleRegistry.getData(handle) as? PipeSettingsHandle ?: return
        when (key) {
            "temperature" -> ps.setTemperature(value)
            "topP" -> ps.setTopP(value)
            "repetitionPenalty" -> ps.setRepetitionPenalty(value)
        }
    }

    @JvmStatic fun pipeSettingsSetBool(@Suppress("UNUSED_PARAMETER") handle: Long, @Suppress("UNUSED_PARAMETER") key: String, @Suppress("UNUSED_PARAMETER") value: Boolean) {
        // No boolean setters on PipeSettingsHandle today; ignore for forward compatibility
    }

    //====================================================================
    // Pipeline
    //====================================================================

    @JvmStatic fun pipelineCreate(): Long {
        val ph = PipelineHandle(Pipeline(), "CABI-Pipeline")
        return HandleRegistry.allocate(HandleTypes.PIPELINE, ph)
    }

    @JvmStatic fun pipelineAdd(pipeline: Long, pipe: Long): Int {
        val ph = HandleRegistry.getData(pipeline) as? PipelineHandle ?: return -0x03
        val p = HandleRegistry.getData(pipe) as? PipeHandle ?: return -0x03
        return try {
            ph.pipeline.add(p.pipe)
            0
        } catch (e: Exception) {
            lastError.set(e.message)
            -0x01
        }
    }

    @JvmStatic fun pipelineExecute(pipeline: Long, content: Long): Long {
        val ph = HandleRegistry.getData(pipeline) as? PipelineHandle ?: return 0L
        val ch = HandleRegistry.getData(content) as? ContentHandle ?: return 0L
        return try {
            val r = ph.execute(ch)
            when (r) {
                is PipelineHandle.Result.Success -> {
                    val op = OperationHandle(OperationStatus.COMPLETE, r.handleId, null)
                    HandleRegistry.allocate(HandleTypes.OPERATION, op)
                }
                is PipelineHandle.Result.Error -> {
                    lastError.set(r.message)
                    val op = OperationHandle(OperationStatus.FAILED, 0L, r.message)
                    HandleRegistry.allocate(HandleTypes.OPERATION, op)
                }
            }
        } catch (e: Exception) {
            lastError.set(e.message)
            val op = OperationHandle(OperationStatus.FAILED, 0L, e.message)
            HandleRegistry.allocate(HandleTypes.OPERATION, op)
        }
    }

    @JvmStatic fun pipelineGetOutcome(handle: Long): String? =
        (HandleRegistry.getData(handle) as? PipelineHandle)?.getOutcome()

    @JvmStatic fun pipelineGetName(handle: Long): String? =
        (HandleRegistry.getData(handle) as? PipelineHandle)?.getName()

    @JvmStatic fun pipelineSetName(handle: Long, name: String) {
        (HandleRegistry.getData(handle) as? PipelineHandle)?.setName(name)
    }

    @JvmStatic fun pipelineGetContextWindow(handle: Long): Long {
        val ph = HandleRegistry.getData(handle) as? PipelineHandle ?: return 0L
        val ch = ContextHandle(ph.getContextWindow())
        return HandleRegistry.allocate(HandleTypes.CONTEXT, ch)
    }

    @JvmStatic fun pipelineGetMiniBank(handle: Long): Long {
        val ph = HandleRegistry.getData(handle) as? PipelineHandle ?: return 0L
        val mb = MiniBankHandle(ph.getMiniBank())
        return HandleRegistry.allocate(HandleTypes.MINIBANK, mb)
    }

    //====================================================================
    // Context
    //====================================================================

    @JvmStatic fun loreBookCreate(): Long {
        val lb = LoreBook()
        lb.key = "default"
        lb.value = ""
        val lh = LoreBookHandle(lb)
        return HandleRegistry.allocate(HandleTypes.LOREBOOK, lh)
    }

    /**
     * Add or replace a LoreBook entry. The C ABI uses this 3-arg shape
     * (key + value only); weight is exposed separately via
     * [loreBookSetWeight] / `TPipe_LoreBook_setWeight`.
     */
    @JvmStatic fun loreBookAddEntry(handle: Long, key: String, value: String)
    {
        val lh = HandleRegistry.getData(handle) as? LoreBookHandle ?: return
        lh.setKey(key)
        lh.setValue(value)
    }

    /**
     * Backwards-compatible 4-arg overload retained for any in-VM callers
     * that still pass an explicit weight. Weight defaults to 0 when the
     * caller has not previously set one.
     */
    @JvmStatic fun loreBookAddEntry(handle: Long, key: String, value: String, weight: Int)
    {
        val lh = HandleRegistry.getData(handle) as? LoreBookHandle ?: return
        lh.setKey(key)
        lh.setValue(value)
        lh.setWeight(weight)
    }

    //====================================================================
    // LoreBook field accessors (Phase 7 — full LoreBookHandle coverage)
    //====================================================================

    /**
     * Set the key of a LoreBook entry.
     *
     * @param handle The LOREBOOK handle.
     * @param key New key value (UTF-8).
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch.
     */
    @JvmStatic fun loreBookSetKey(handle: Long, key: String): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.run { setKey(key); 0 } ?: -0x03

    /**
     * Write the LoreBook key into the caller's byte buffer.
     *
     * @param handle The LOREBOOK handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return Number of bytes written, or TPIPE_ERR_INVALID_HANDLE on type mismatch.
     */
    @JvmStatic fun loreBookGetKey(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val v = (HandleRegistry.getData(handle) as? LoreBookHandle)?.getKey() ?: return -0x03
        val bytes = v.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Set the value (context body) of a LoreBook entry.
     */
    @JvmStatic fun loreBookSetValue(handle: Long, value: String): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.run { setValue(value); 0 } ?: -0x03

    /**
     * Write the LoreBook value into the caller's byte buffer.
     */
    @JvmStatic fun loreBookGetValue(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val v = (HandleRegistry.getData(handle) as? LoreBookHandle)?.getValue() ?: return -0x03
        val bytes = v.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Set the weight of a LoreBook entry.
     */
    @JvmStatic fun loreBookSetWeight(handle: Long, weight: Int): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.run { setWeight(weight); 0 } ?: -0x03

    /**
     * Get the weight of a LoreBook entry.
     *
     * @return The weight value, or TPIPE_ERR_INVALID_HANDLE (-0x03) on type
     *   mismatch. The Java shim disambiguates these via its own [int*] output
     *   pointer; tests can rely on the sentinel value.
     */
    @JvmStatic fun loreBookGetWeight(handle: Long): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.getWeight() ?: -0x03

    /**
     * Append a linked key to the LoreBook entry. Idempotent.
     */
    @JvmStatic fun loreBookAddLinkedKey(handle: Long, key: String): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.run { addLinkedKey(key); 0 } ?: -0x03

    /**
     * Write the linked keys as a JSON array (e.g. `["a","b","c"]`) to the
     * caller's byte buffer.
     */
    @JvmStatic fun loreBookGetLinkedKeys(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val list = (HandleRegistry.getData(handle) as? LoreBookHandle)?.getLinkedKeys() ?: return -0x03
        val json = list.joinToString(",", "[", "]") { "\"${escapeJsonField(it)}\"" }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Append an alias key to the LoreBook entry. Idempotent.
     */
    @JvmStatic fun loreBookAddAliasKey(handle: Long, key: String): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.run { addAliasKey(key); 0 } ?: -0x03

    /**
     * Write the alias keys as a JSON array to the caller's byte buffer.
     */
    @JvmStatic fun loreBookGetAliasKeys(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val list = (HandleRegistry.getData(handle) as? LoreBookHandle)?.getAliasKeys() ?: return -0x03
        val json = list.joinToString(",", "[", "]") { "\"${escapeJsonField(it)}\"" }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Append a required key to the LoreBook entry. Idempotent.
     */
    @JvmStatic fun loreBookAddRequiredKey(handle: Long, key: String): Int =
        (HandleRegistry.getData(handle) as? LoreBookHandle)?.run { addRequiredKey(key); 0 } ?: -0x03

    /**
     * Write the required keys as a JSON array to the caller's byte buffer.
     */
    @JvmStatic fun loreBookGetRequiredKeys(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val list = (HandleRegistry.getData(handle) as? LoreBookHandle)?.getRequiredKeys() ?: return -0x03
        val json = list.joinToString(",", "[", "]") { "\"${escapeJsonField(it)}\"" }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Combine the [otherHandle] LoreBook into [handle] via [LoreBookHandle.combine].
     *
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE if [handle] is not a
     *   LoreBookHandle; TPIPE_ERR_TYPE_MISMATCH (-0x13) if [otherHandle] is not
     *   a LoreBookHandle.
     */
    @JvmStatic fun loreBookCombine(handle: Long, otherHandle: Long): Int {
        val a = HandleRegistry.getData(handle) as? LoreBookHandle ?: return -0x03
        val b = HandleRegistry.getData(otherHandle) as? LoreBookHandle ?: return -0x13
        a.combine(b)
        return 0
    }

    /**
     * Serialize the LoreBook entry to a JSON string and write it into the
     * caller's byte buffer.
     */
    @JvmStatic fun loreBookToJson(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val json = (HandleRegistry.getData(handle) as? LoreBookHandle)?.toJson() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    private fun escapeJsonField(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    @JvmStatic fun converseHistoryCreate(): Long {
        val ch = ConverseHistoryHandle(ConverseHistory())
        return HandleRegistry.allocate(HandleTypes.CONVERSE_HISTORY, ch)
    }

    @JvmStatic fun converseHistoryAdd(handle: Long, role: Int, content: String) {
        val ch = HandleRegistry.getData(handle) as? ConverseHistoryHandle ?: return
        val mapped = when (role) {
            0 -> ConverseRole.user
            1 -> ConverseRole.assistant
            2 -> ConverseRole.system
            3 -> ConverseRole.agent
            4 -> ConverseRole.supervisor
            else -> ConverseRole.user
        }
        ch.add(mapped, ContentHandle(content))
    }

    //====================================================================
    // ConverseHistory field accessors (Phase 8 — full ConverseHistoryHandle coverage)
    //====================================================================

    /**
     * Add a conversation turn using a string role name (e.g. "user",
     * "assistant", "system"). The role string is resolved by
     * [ConverseHistoryHandle.add] against [ConverseRole].
     *
     * @param handle   The CONVERSE_HISTORY handle.
     * @param role     Role name (UTF-8). Unknown values default to "user".
     * @param content  Content text (UTF-8). Wrapped in a fresh [ContentHandle].
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch.
     */
    @JvmStatic fun converseHistoryAddString(handle: Long, role: String, content: String): Int {
        val ch = HandleRegistry.getData(handle) as? ConverseHistoryHandle ?: return -0x03
        ch.add(role, ContentHandle(content))
        return 0
    }

    /**
     * Get the number of conversation turns.
     *
     * @return The size, or TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch.
     */
    @JvmStatic fun converseHistorySize(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ConverseHistoryHandle)?.size() ?: -0x03

    /**
     * Check whether the conversation history is empty.
     *
     * @return 1 if empty, 0 if non-empty, or TPIPE_ERR_INVALID_HANDLE (-0x03)
     *   on type mismatch.
     */
    @JvmStatic fun converseHistoryIsEmpty(handle: Long): Int {
        val ch = HandleRegistry.getData(handle) as? ConverseHistoryHandle ?: return -0x03
        return if (ch.isEmpty()) 1 else 0
    }

    /**
     * Clear all conversation turns.
     *
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch.
     */
    @JvmStatic fun converseHistoryClear(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ConverseHistoryHandle)?.run { clear(); 0 } ?: -0x03

    /**
     * Get a single conversation turn at [index] as a JSON object
     * (e.g. `{"role":"user","content":"hi"}`). Writes a null-terminated
     * UTF-8 string into the caller's buffer.
     *
     * @param handle  The CONVERSE_HISTORY handle.
     * @param index   Zero-based turn index.
     * @param buf     Caller-provided byte buffer.
     * @param offset  Offset into [buf] at which to begin writing.
     * @param maxLen  Maximum number of bytes to write.
     * @return Number of bytes written (excluding the null terminator), or
     *   TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch, or
     *   TPIPE_ERR_INVALID_ARGUMENT (-0x04) when [index] is out of range.
     */
    @JvmStatic fun converseHistoryGetAt(handle: Long, index: Int, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val ch = HandleRegistry.getData(handle) as? ConverseHistoryHandle ?: return -0x03
        val turn = ch.get(index) ?: return -0x04
        val json = """{"role":"${turn.role.name}","content":"${turn.content.text.escapeJsonForCh()}"}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Serialize the entire conversation history to JSON and write it into
     * the caller's byte buffer. Same shape as [ConverseHistoryHandle.toJson]:
     * `{"history":[{"role":"<name>","content":"<text>"}, ...]}`.
     *
     * @return Number of bytes written, or TPIPE_ERR_INVALID_HANDLE on type
     *   mismatch.
     */
    @JvmStatic fun converseHistoryToJson(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val json = (HandleRegistry.getData(handle) as? ConverseHistoryHandle)?.toJson() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    private fun String.escapeJsonForCh(): String =
        this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    @JvmStatic fun miniBankCreate(): Long {
        val mb = MiniBankHandle(MiniBank())
        return HandleRegistry.allocate(HandleTypes.MINIBANK, mb)
    }

    @JvmStatic fun miniBankSet(handle: Long, key: String, value: String) {
        val mb = HandleRegistry.getData(handle) as? MiniBankHandle ?: return
        val page = mb.getOrCreatePage(key)
        page.addLoreBookEntry(key, value, 0, emptyList(), emptyList(), emptyList())
    }

    //====================================================================
    // MiniBank field accessors (Phase 9 — full MiniBankHandle coverage)
    //====================================================================

    /**
     * Check whether the MiniBank contains no context pages.
     *
     * @param handle The MINIBANK handle.
     * @return 1 if the bank has zero pages, 0 if it has at least one, or
     *   TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch.
     */
    @JvmStatic fun miniBankIsEmpty(handle: Long): Int {
        val mb = HandleRegistry.getData(handle) as? MiniBankHandle ?: return -0x03
        return if (mb.isEmpty()) 1 else 0
    }

    /**
     * Clear every context page in the MiniBank.
     *
     * @param handle The MINIBANK handle.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch.
     */
    @JvmStatic fun miniBankClear(handle: Long): Int {
        val mb = HandleRegistry.getData(handle) as? MiniBankHandle ?: return -0x03
        mb.clear()
        return 0
    }

    /**
     * Get the number of context pages currently in the MiniBank.
     *
     * @param handle The MINIBANK handle.
     * @return The page count (>= 0), or TPIPE_ERR_INVALID_HANDLE (-0x03) on
     *   type mismatch.
     */
    @JvmStatic fun miniBankPageCount(handle: Long): Int {
        val mb = HandleRegistry.getData(handle) as? MiniBankHandle ?: return -0x03
        return mb.pageCount()
    }

    /**
     * Write the MiniBank's page keys as a JSON array string (e.g. `["a","b"]`)
     * into the caller's byte buffer.
     *
     * @param handle The MINIBANK handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return Number of bytes written, or TPIPE_ERR_INVALID_HANDLE (-0x03) on
     *   type mismatch.
     */
    @JvmStatic fun miniBankGetPageKeys(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val mb = HandleRegistry.getData(handle) as? MiniBankHandle ?: return -0x03
        val keys = mb.getPageKeys()
        val json = keys.joinToString(",", "[", "]") { "\"${escapeJsonField(it)}\"" }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Snapshot a single MiniBank page to a JSON object describing the page.
     *
     * The JSON has the shape:
     * ```
     * {
     *   "key": "<pageKey>",
     *   "version": <long>,
     *   "isInitialized": <bool>,
     *   "loreBookKeysCount": <int>,
     *   "contextElementsCount": <int>,
     *   "converseHistorySize": <int>
     * }
     * ```
     * If [key] is not present in the MiniBank, an empty object `{}` is
     * written.
     *
     * @param handle The MINIBANK handle.
     * @param key Page key to look up.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return Number of bytes written, or TPIPE_ERR_INVALID_HANDLE (-0x03) on
     *   type mismatch.
     */
    @JvmStatic fun miniBankGetPageJson(handle: Long, key: String, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val mb = HandleRegistry.getData(handle) as? MiniBankHandle ?: return -0x03
        // Avoid creating the page on lookup; if absent, write an empty object.
        val page = mb.miniBank.contextMap[key]
        val json = if (page == null) {
            "{}"
        } else {
            """{"key":"${escapeJsonField(key)}","version":${page.version},"isInitialized":${page.isInitialized},"loreBookKeysCount":${page.loreBookKeys.size},"contextElementsCount":${page.contextElements.size},"converseHistorySize":${page.converseHistory.history.size}}"""
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Merge another MiniBank into this one.
     *
     * @param handle         Destination MINIBANK handle (mutated in place).
     * @param otherHandle    Source MINIBANK handle (read-only).
     * @param emplaceLorebookKeys  Emplace (replace) existing lorebook keys on conflict.
     * @param appendKeys     Append to existing lorebook key values rather than replacing.
     * @param emplaceConverseHistory  Merge converse history from [otherHandle].
     * @param onlyEmplaceIfNull  When emplaceConverseHistory is true, only copy history
     *   if the destination's history is empty.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch
     *   for [handle]; TPIPE_ERR_TYPE_MISMATCH (-0x13) on type mismatch for
     *   [otherHandle].
     */
    @JvmStatic fun miniBankMerge(
        handle: Long,
        otherHandle: Long,
        emplaceLorebookKeys: Boolean,
        appendKeys: Boolean,
        emplaceConverseHistory: Boolean,
        onlyEmplaceIfNull: Boolean
    ): Int {
        val dst = HandleRegistry.getData(handle) as? MiniBankHandle ?: return -0x03
        val src = HandleRegistry.getData(otherHandle) as? MiniBankHandle ?: return -0x13
        dst.merge(src, emplaceLorebookKeys, appendKeys, emplaceConverseHistory, onlyEmplaceIfNull)
        return 0
    }

    @JvmStatic fun contextWindowCreate(): Long {
        val ch = ContextHandle(ContextWindow())
        return HandleRegistry.allocate(HandleTypes.CONTEXT, ch)
    }

    //====================================================================
    // ContextHandle field accessors (Phase 10 — full ContextHandle coverage)
    //====================================================================

    /**
     * Write the [ContextHandle.getLoreBookKeys] list as a JSON array string
     * (e.g. `["a","b"]`) into the caller's byte buffer.
     *
     * @param handle The CONTEXT handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return Number of bytes written, or TPIPE_ERR_INVALID_HANDLE (-0x03) on
     *   type mismatch.
     */
    @JvmStatic fun contextGetLoreBookKeys(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val ch = HandleRegistry.getData(handle) as? ContextHandle ?: return -0x03
        val keys = ch.getLoreBookKeys()
        val json = keys.joinToString(",", "[", "]") { "\"${escapeJsonField(it)}\"" }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Get the number of context elements (raw context strings).
     *
     * @param handle The CONTEXT handle.
     * @return The element count (>= 0), or TPIPE_ERR_INVALID_HANDLE (-0x03) on
     *   type mismatch.
     */
    @JvmStatic fun contextGetContextElementsCount(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ContextHandle)?.getContextElementsCount() ?: -0x03

    /**
     * Get the number of conversation turns stored in this context window.
     *
     * @param handle The CONTEXT handle.
     * @return The conversation history size (>= 0), or
     *   TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch.
     */
    @JvmStatic fun contextGetConverseHistorySize(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ContextHandle)?.getConverseHistorySize() ?: -0x03

    /**
     * Get the monotonic version counter of the context window. Writes the
     * 64-bit value into [out] at index 0.
     *
     * @param handle The CONTEXT handle.
     * @param out A LongArray of size 1; on success, `out[0]` is set to the
     *   version value.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE (-0x03) on type mismatch.
     */
    @JvmStatic fun contextGetVersion(handle: Long, out: LongArray): Int {
        val ch = HandleRegistry.getData(handle) as? ContextHandle ?: return -0x03
        if (out.isEmpty()) return -0x04
        out[0] = ch.getVersion()
        return 0
    }

    /**
     * Snapshot the context window to a JSON object describing its
     * lorebook-keys, context-elements, converse-history, and version, and
     * write it into the caller's byte buffer.
     *
     * @param handle The CONTEXT handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return Number of bytes written, or TPIPE_ERR_INVALID_HANDLE (-0x03) on
     *   type mismatch.
     */
    @JvmStatic fun contextGetContextJson(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val json = (HandleRegistry.getData(handle) as? ContextHandle)?.getContextJson() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    //====================================================================
    // PCP
    //====================================================================

    @JvmStatic fun pcpCreate(): Long {
        return HandleRegistry.allocate(HandleTypes.PCP, PCPHandle())
    }

    @JvmStatic fun pcpExecute(handle: Long, functionName: String, parametersJson: String?): String? {
        val p = HandleRegistry.getData(handle) as? PCPHandle ?: return null
        val params = parseFlatJson(parametersJson)
        return when (val r = p.execute(functionName, params)) {
            is PCPHandle.Result.Success -> r.returnValue
            is PCPHandle.Result.Error -> """{"error":"${r.message.replace("\"", "\\\"")}"}"""
        }
    }

    private fun parseFlatJson(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        val trimmed = json.trim().let { if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else return emptyMap() }
        val out = HashMap<String, String>()
        for (pair in trimmed.split(",")) {
            val colon = pair.indexOf(':')
            if (colon > 0) {
                val k = pair.substring(0, colon).trim().removePrefix("\"").removeSuffix("\"")
                val v = pair.substring(colon + 1).trim().removePrefix("\"").removeSuffix("\"")
                out[k] = v
            }
        }
        return out
    }

    //====================================================================
    // P2P
    //====================================================================

    @JvmStatic fun p2pCreate(): Long {
        return HandleRegistry.allocate(HandleTypes.P2P, P2PHandle())
    }

    /**
     * Register an agent with a P2P handle, including a free-form metadata
     * JSON document. The C ABI surface is
     * {@code TPipe_P2PHandle_registerAgent(p2p, agentId, metadata)}.
     *
     * <p>P2PHandle.registerAgent() requires a fully-configured P2PInterface
     * implementation. The C ABI cannot construct one without bringing in
     * agent code. Storing the agent name on the handle lets isRegistered()
     * and getAgentId() return the registered name without requiring a full
     * P2PInterface.
     *
     * @param handle   The P2P handle.
     * @param agentId  The agent identifier (UTF-8).
     * @param metadata Optional JSON metadata (UTF-8); may be null.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_NOT_IMPLEMENTED (-0x10) on internal reflection failure.
     */
    @JvmStatic fun p2pRegisterAgent(handle: Long, agentId: String, metadata: String?): Int
    {
        val p = HandleRegistry.getData(handle) as? P2PHandle ?: return -0x03
        return try
        {
            val f = P2PHandle::class.java.getDeclaredField("agentId")
            f.isAccessible = true
            f.set(p, agentId)
            0
        } catch (e: Exception)
        {
            lastError.set(e.message)
            -0x10
        }
    }

    /**
     * Backwards-compatible 2-arg overload retained for any in-VM callers
     * that do not pass metadata. The C ABI shim uses the 3-arg form.
     */
    @JvmStatic fun p2pRegisterAgent(handle: Long, agentId: String): Int =
        p2pRegisterAgent(handle, agentId, null)

    @JvmStatic fun p2pConnect(handle: Long, @Suppress("UNUSED_PARAMETER") remoteAddress: String): Int
    {
        if (HandleRegistry.getData(handle) !is P2PHandle) return -0x03
        // Real connect requires P2PInterface registration via the registry.
        return 0
    }

    /**
     * Send a message to a peer. The C ABI surface is
     * {@code TPipe_P2PHandle_send(p2p, peerId, message, response)}: the
     * message is a CONTENT handle, and the response is written into the
     * caller's {@code TPipe_ContentHandle*} out-param.
     *
     * <p>Real send requires P2PInterface registration via the registry, which
     * the C ABI cannot construct on its own. The current implementation
     * validates the handle types and returns 0 (a null content handle for
     * the response) on success. The Java shim writes 0 to the out-param
     * when the result is null.
     *
     * @param handle      The P2P handle.
     * @param peerId      The peer agent identifier (UTF-8).
     * @param message     The message CONTENT handle. Must be a valid CONTENT
     *   handle. A handle of 0 or a non-CONTENT type returns
     *   TPIPE_ERR_INVALID_ARGUMENT.
     * @return A CONTENT handle for the peer's response, or 0 if the peer
     *   produced no response. Returns a negative TPIPE_ERR_* code on
     *   invalid handle type.
     */
    @JvmStatic fun p2pSend(
        handle: Long, @Suppress("UNUSED_PARAMETER") peerId: String, message: Long
    ): Long
    {
        if (HandleRegistry.getData(handle) !is P2PHandle) return -0x03L
        if (message == 0L) return -0x04L // INVALID_ARGUMENT
        if (HandleRegistry.getType(message) != HandleTypes.CONTENT) return -0x13L
        // Real send requires P2PInterface registration via the registry. The
        // C ABI cannot construct a fully-wired P2PInterface; return 0 (no
        // response content) on success.
        return 0L
    }

    /**
     * Backwards-compatible 3-arg overload retained for any in-VM callers
     * that still pass a request text string. The C ABI shim uses the
     * 3-arg content-handle form.
     */
    @JvmStatic fun p2pSend(
        handle: Long, targetAgent: String, @Suppress("UNUSED_PARAMETER") request: String
    ): Int
    {
        if (HandleRegistry.getData(handle) !is P2PHandle) return -0x03
        return 0
    }

    //====================================================================
    // List / Map / Async
    //====================================================================

    @JvmStatic fun listCreate(): Long = ListHandle.create().build()
    @JvmStatic fun listAppend(@Suppress("UNUSED_PARAMETER") list: Long, @Suppress("UNUSED_PARAMETER") item: Long): Int = 0
    @JvmStatic fun listGet(handle: Long, index: Int): Long? =
        (HandleRegistry.getData(handle) as? ListHandle)?.get(index)
    @JvmStatic fun listSize(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ListHandle)?.size() ?: -0x03

    @JvmStatic fun mapCreate(): Long = MapHandle.create().build()
    @JvmStatic fun mapSet(@Suppress("UNUSED_PARAMETER") map: Long, @Suppress("UNUSED_PARAMETER") key: String, @Suppress("UNUSED_PARAMETER") value: Long): Int = 0
    @JvmStatic fun mapGet(handle: Long, key: String): Long? =
        (HandleRegistry.getData(handle) as? MapHandle)?.get(key)
    @JvmStatic fun mapSize(handle: Long): Int =
        (HandleRegistry.getData(handle) as? MapHandle)?.size() ?: -0x03

    /**
     * Check whether [key] exists in the map.
     *
     * @param map The MAP handle.
     * @param key Key to look up (UTF-8).
     * @param hasOut Single-element output array; on success, hasOut[0] is 1 if
     *   the key exists, 0 otherwise.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INVALID_ARGUMENT when [hasOut] is empty.
     */
    @JvmStatic fun mapHas(map: Long, key: String, hasOut: IntArray): Int
    {
        val m = HandleRegistry.getData(map) as? MapHandle ?: return -0x03
        if (hasOut.isEmpty()) return -0x04
        hasOut[0] = if (m.has(key)) 1 else 0
        return 0
    }

    @JvmStatic fun asyncCreate(): Long
    {
        val op = OperationHandle(OperationStatus.PENDING, 0L, null)
        return HandleRegistry.allocate(HandleTypes.OPERATION, op)
    }

    @JvmStatic fun asyncCancel(handle: Long): Int {
        val op = HandleRegistry.getData(handle) as? OperationHandle ?: return -0x03
        return if (op.cancel()) 0 else -0x1C
    }

    @JvmStatic fun asyncIsDone(handle: Long): Boolean =
        (HandleRegistry.getData(handle) as? OperationHandle)?.isDone() ?: false

    @JvmStatic fun asyncWait(handle: Long, timeoutMs: Int): Int {
        val op = HandleRegistry.getData(handle) as? OperationHandle ?: return -0x03
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
        while (!op.isDone()) {
            if (System.nanoTime() >= deadline) return -0x15
            try { Thread.sleep(1) } catch (e: InterruptedException) { return -0x1C }
        }
        return if (op.isSuccessful()) 0 else -0x01
    }

    /**
     * Poll the status of an async operation. Mirrors the C ABI's
     * [TPipe_AsyncHandle_poll] entry point.
     *
     * @param handle The OPERATION handle.
     * @param statusOut Single-element output array; on success, statusOut[0]
     *   is the C ABI status code (0=PENDING, 1=COMPLETE, 2=FAILED).
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INVALID_ARGUMENT when [statusOut] is empty.
     */
    @JvmStatic fun asyncPoll(handle: Long, statusOut: IntArray): Int
    {
        val op = HandleRegistry.getData(handle) as? OperationHandle ?: return -0x03
        if (statusOut.isEmpty()) return -0x04
        statusOut[0] = op.poll().cValue
        return 0
    }

    /**
     * Get the result handle of a completed async operation. Mirrors the C
     * ABI's [TPipe_AsyncHandle_getResult] entry point.
     *
     * @param handle The OPERATION handle.
     * @param resultOut Single-element output array; on success, resultOut[0]
     *   is the result handle (or 0 if the operation is still pending or
     *   failed).
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INVALID_ARGUMENT when [resultOut] is empty.
     */
    @JvmStatic fun asyncGetResult(handle: Long, resultOut: LongArray): Int
    {
        val op = HandleRegistry.getData(handle) as? OperationHandle ?: return -0x03
        if (resultOut.isEmpty()) return -0x04
        resultOut[0] = op.getResult()
        return 0
    }

    //====================================================================
    // Manifold
    //====================================================================

    /**
     * Allocate a new ManifoldHandle wrapping a fresh [com.TTT.Pipeline.Manifold].
     *
     * @return The new handle, or -1 on handle limit exceeded.
     */
    @JvmStatic fun manifoldCreate(): Long {
        val mh = ManifoldHandle(Manifold())
        return HandleRegistry.allocate(HandleTypes.MANIFOLD, mh)
    }

    /**
     * Release a manifold handle.
     *
     * @param handle The MANIFOLD handle to release.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE if the handle is not a
     *   ManifoldHandle.
     */
    @JvmStatic fun manifoldRelease(handle: Long): Int {
        if (HandleRegistry.getData(handle) !is ManifoldHandle) return -0x03
        return HandleRegistry.release(handle)
    }

    /**
     * Initialize the wrapped [com.TTT.Pipeline.Manifold].
     *
     * @param handle The MANIFOLD handle.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INTERNAL on init failure.
     */
    @JvmStatic fun manifoldInit(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ManifoldHandle)?.init() ?: -0x03

    /**
     * Execute the wrapped manifold with the given input content.
     *
     * @param handle The MANIFOLD handle.
     * @param contentHandle The CONTENT handle whose MultimodalContent is fed
     *   into the manifold's execute().
     * @return A new CONTENT handle wrapping the output MultimodalContent, or
     *   0 on failure.
     */
    @JvmStatic fun manifoldExecute(handle: Long, contentHandle: Long): Long {
        val mh = HandleRegistry.getData(handle) as? ManifoldHandle ?: return 0L
        val ch = HandleRegistry.getData(contentHandle) as? ContentHandle ?: return 0L
        return mh.execute(ch)
    }

    /**
     * Register a worker Pipe on the manifold under the given name.
     *
     * @param handle The MANIFOLD handle.
     * @param name Worker identifier.
     * @param pipeHandle The PIPE handle backing the worker.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_TYPE_MISMATCH (-0x13) on pipe handle type mismatch;
     *   TPIPE_ERR_INTERNAL on failure.
     */
    @JvmStatic fun manifoldAddWorker(handle: Long, name: String, pipeHandle: Long): Int {
        val mh = HandleRegistry.getData(handle) as? ManifoldHandle ?: return -0x03
        val ph = HandleRegistry.getData(pipeHandle) as? PipeHandle ?: return -0x13
        return mh.addWorker(name, ph.pipe)
    }

    /**
     * Get the number of workers currently registered on the manifold.
     *
     * @param handle The MANIFOLD handle.
     * @return The worker count, or TPIPE_ERR_INVALID_HANDLE on type mismatch.
     */
    @JvmStatic fun manifoldGetWorkerCount(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ManifoldHandle)?.getWorkerCount() ?: -0x03

    /**
     * Set the manifold's max loop iterations.
     *
     * @param handle The MANIFOLD handle.
     * @param limit Maximum loop iterations (0 means default 100).
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INTERNAL on failure.
     */
    @JvmStatic fun manifoldSetMaxLoopIterations(handle: Long, limit: Int): Int =
        (HandleRegistry.getData(handle) as? ManifoldHandle)?.setMaxLoopIterations(limit) ?: -0x03

    /**
     * Serialize the manifold state to a JSON string and copy it into the
     * caller's buffer.
     *
     * @param handle The MANIFOLD handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write (must be >= 1).
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun manifoldSerialize(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val json = (HandleRegistry.getData(handle) as? ManifoldHandle)?.serialize() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    //====================================================================
    // DistributionGrid (Phase 11 — stub-level handle exposure)
    //====================================================================

    /**
     * Allocate a new DistributionGridHandle wrapping a fresh
     * [com.TTT.Pipeline.DistributionGrid].
     *
     * @return The new handle, or -1 on handle limit exceeded.
     */
    @JvmStatic fun distributionGridCreate(): Long {
        val dgh = DistributionGridHandle(DistributionGrid())
        return HandleRegistry.allocate(HandleTypes.DISTRIBUTION_GRID, dgh)
    }

    /**
     * Release a DistributionGrid handle.
     *
     * @param handle The DISTRIBUTION_GRID handle to release.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE if the handle is not a
     *   DistributionGridHandle.
     */
    @JvmStatic fun distributionGridRelease(handle: Long): Int {
        if (HandleRegistry.getData(handle) !is DistributionGridHandle) return -0x03
        return HandleRegistry.release(handle)
    }

    /**
     * Get the node count of the wrapped DistributionGrid. Returns 0 because grid
     * introspection is not wired into the C ABI.
     *
     * @param handle The DISTRIBUTION_GRID handle.
     * @return The node count, or TPIPE_ERR_INVALID_HANDLE (-0x03) on type
     *   mismatch.
     */
    @JvmStatic fun distributionGridGetNodeCount(handle: Long): Int =
        (HandleRegistry.getData(handle) as? DistributionGridHandle)?.getNodeCount() ?: -0x03

    /**
     * Phase 6: returns the timestamp (ms since epoch) of the most
     * recent rebalance call on the grid, or 0 if none has happened
     * yet. The C ABI entry point writes this into the caller's
     * int64_t* via {@code writePtr}.
     */
    @JvmStatic fun distributionGridGetLastRebalanceMs(handle: Long): Long =
        (HandleRegistry.getData(handle) as? DistributionGridHandle)?.lastRebalanceMs() ?: -0x03L

    /**
     * Serialize the DistributionGrid state to a JSON string and copy it
     * into the caller's buffer.
     *
     * @param handle The DISTRIBUTION_GRID handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun distributionGridSerialize(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val json = (HandleRegistry.getData(handle) as? DistributionGridHandle)?.serialize() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Get a health string describing the DistributionGrid. Returns "ok"
     * because no health probing is wired into the C ABI.
     *
     * @param handle The DISTRIBUTION_GRID handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun distributionGridGetHealth(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val str = (HandleRegistry.getData(handle) as? DistributionGridHandle)?.getHealth() ?: return -0x03
        val bytes = str.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    /**
     * Stub rebalance operation. Returns a fixed string indicating the
     * operation is not yet implemented.
     *
     * @param handle The DISTRIBUTION_GRID handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun distributionGridRebalanceStub(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int {
        val str = (HandleRegistry.getData(handle) as? DistributionGridHandle)?.rebalanceStub() ?: return -0x03
        val bytes = str.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    //====================================================================
    // Junction (discussion harness C ABI surface)
    //====================================================================

    /**
     * Allocate a new JunctionHandle wrapping a fresh
     * [com.TTT.Pipeline.Junction].
     *
     * @return The new handle, or -1 on handle limit exceeded.
     */
    @JvmStatic fun junctionCreate(): Long
    {
        val jh = JunctionHandle(Junction())
        return HandleRegistry.allocate(HandleTypes.JUNCTION, jh)
    }

    /**
     * Release a junction handle.
     *
     * @param handle The JUNCTION handle to release.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE if the handle is not
     *   a JunctionHandle.
     */
    @JvmStatic fun junctionRelease(handle: Long): Int
    {
        if (HandleRegistry.getData(handle) !is JunctionHandle) return -0x03
        return HandleRegistry.release(handle)
    }

    /**
     * Initialize the wrapped [com.TTT.Pipeline.Junction].
     *
     * @param handle The JUNCTION handle.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INTERNAL on init failure.
     */
    @JvmStatic fun junctionInit(handle: Long): Int =
        (HandleRegistry.getData(handle) as? JunctionHandle)?.init() ?: -0x03

    /**
     * Execute the wrapped junction with the given input content.
     *
     * @param handle The JUNCTION handle.
     * @param contentHandle The CONTENT handle whose MultimodalContent is
     *   fed into the junction's execute().
     * @return A new CONTENT handle wrapping the output MultimodalContent,
     *   or 0 on failure.
     */
    @JvmStatic fun junctionExecute(handle: Long, contentHandle: Long): Long
    {
        val jh = HandleRegistry.getData(handle) as? JunctionHandle ?: return 0L
        val ch = HandleRegistry.getData(contentHandle) as? ContentHandle ?: return 0L
        return jh.execute(ch)
    }

    /**
     * Serialize the junction state to a JSON string and copy it into the
     * caller's buffer.
     *
     * @param handle The JUNCTION handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write (must be >= 1).
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun junctionSerialize(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int
    {
        val json = (HandleRegistry.getData(handle) as? JunctionHandle)?.serialize() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    //====================================================================
    // Connector (branching container C ABI surface)
    //====================================================================

    /**
     * Allocate a new ConnectorHandle wrapping a fresh
     * [com.TTT.Pipeline.Connector].
     *
     * @return The new handle, or -1 on handle limit exceeded.
     */
    @JvmStatic fun connectorCreate(): Long
    {
        val ch = ConnectorHandle(Connector())
        return HandleRegistry.allocate(HandleTypes.CONNECTOR, ch)
    }

    /**
     * Release a connector handle.
     *
     * @param handle The CONNECTOR handle to release.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE if the handle is not
     *   a ConnectorHandle.
     */
    @JvmStatic fun connectorRelease(handle: Long): Int
    {
        if (HandleRegistry.getData(handle) !is ConnectorHandle) return -0x03
        return HandleRegistry.release(handle)
    }

    /**
     * Initialize the wrapped [com.TTT.Pipeline.Connector]. Connector has
     * no public init() method, so this is always a no-op success.
     *
     * @param handle The CONNECTOR handle.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch.
     */
    @JvmStatic fun connectorInit(handle: Long): Int =
        (HandleRegistry.getData(handle) as? ConnectorHandle)?.init() ?: -0x03

    /**
     * Execute the wrapped connector with the given input content. The
     * connector's executeLocal reads the branch path from the content.
     *
     * @param handle The CONNECTOR handle.
     * @param contentHandle The CONTENT handle whose MultimodalContent is
     *   fed into the connector's executeLocal().
     * @return A new CONTENT handle wrapping the output MultimodalContent,
     *   or 0 on failure.
     */
    @JvmStatic fun connectorExecute(handle: Long, contentHandle: Long): Long
    {
        val ch = HandleRegistry.getData(handle) as? ConnectorHandle ?: return 0L
        val ih = HandleRegistry.getData(contentHandle) as? ContentHandle ?: return 0L
        return ch.execute(ih)
    }

    /**
     * Serialize the connector state to a JSON string and copy it into the
     * caller's buffer.
     *
     * @param handle The CONNECTOR handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun connectorSerialize(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int
    {
        val json = (HandleRegistry.getData(handle) as? ConnectorHandle)?.serialize() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    //====================================================================
    // Splitter (parallel-fanout container C ABI surface)
    //====================================================================

    /**
     * Allocate a new SplitterHandle wrapping a fresh
     * [com.TTT.Pipeline.Splitter].
     *
     * @return The new handle, or -1 on handle limit exceeded.
     */
    @JvmStatic fun splitterCreate(): Long
    {
        val sh = SplitterHandle(Splitter())
        return HandleRegistry.allocate(HandleTypes.SPLITTER, sh)
    }

    /**
     * Release a splitter handle.
     *
     * @param handle The SPLITTER handle to release.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE if the handle is not
     *   a SplitterHandle.
     */
    @JvmStatic fun splitterRelease(handle: Long): Int
    {
        if (HandleRegistry.getData(handle) !is SplitterHandle) return -0x03
        return HandleRegistry.release(handle)
    }

    /**
     * Initialize the wrapped [com.TTT.Pipeline.Splitter].
     *
     * @param handle The SPLITTER handle.
     * @return 0 on success; TPIPE_ERR_INVALID_HANDLE on type mismatch;
     *   TPIPE_ERR_INTERNAL on init failure.
     */
    @JvmStatic fun splitterInit(handle: Long): Int =
        (HandleRegistry.getData(handle) as? SplitterHandle)?.init() ?: -0x03

    /**
     * Execute the wrapped splitter with the given input content. The
     * splitter's executeLocal fans the content out to all bound
     * pipelines in parallel and returns the aggregated content.
     *
     * @param handle The SPLITTER handle.
     * @param contentHandle The CONTENT handle whose MultimodalContent is
     *   fed into the splitter's executeLocal().
     * @return A new CONTENT handle wrapping the output MultimodalContent,
     *   or 0 on failure.
     */
    @JvmStatic fun splitterExecute(handle: Long, contentHandle: Long): Long
    {
        val sh = HandleRegistry.getData(handle) as? SplitterHandle ?: return 0L
        val ch = HandleRegistry.getData(contentHandle) as? ContentHandle ?: return 0L
        return sh.execute(ch)
    }

    /**
     * Serialize the splitter state to a JSON string and copy it into the
     * caller's buffer.
     *
     * @param handle The SPLITTER handle.
     * @param buf Caller-provided byte buffer.
     * @param offset Offset into [buf] at which to begin writing.
     * @param maxLen Maximum number of bytes to write.
     * @return The number of bytes written, or TPIPE_ERR_INVALID_HANDLE on
     *   type mismatch.
     */
    @JvmStatic fun splitterSerialize(handle: Long, buf: ByteArray, offset: Int, maxLen: Int): Int
    {
        val json = (HandleRegistry.getData(handle) as? SplitterHandle)?.serialize() ?: return -0x03
        val bytes = json.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, buf, offset, n)
        return n
    }

    //====================================================================
    // Top-level C entry point
    //====================================================================

    /**
     * Top-level entry point that lets a C program act as a TPipe host.
     * Bootstraps the library (if not already) and dispatches to the
     * requested mode, mirroring the JVM-side `Application.main()`.
     *
     * Supported modes (case-insensitive):
     *  - "stdio-once"      → P2PStdioHost.runOnce()
     *  - "stdio-loop"      → P2PStdioHost.runLoop()
     *  - "pcp-stdio-once"  → PcpStdioHost.runOnce()
     *  - "pcp-stdio-loop"  → PcpStdioHost.runLoop()
     *  - "http" (default)  → Embedded Ktor HTTP server
     *  - null/empty        → Defaults to "http"
     *
     * @param args Single-element array whose [0] is the mode string. The
     *   C ABI uses a flat `const char*` for portability; we wrap it here.
     * @return 0 on success; negative TPIPE_ERR_* on failure.
     */
    @JvmStatic
    fun tpipeMain(args: Array<String>): Int {
        val mode = args.firstOrNull()?.trim().orEmpty().lowercase()
        return try {
            when (mode) {
                "stdio-once" -> {
                    com.TTT.P2P.P2PStdioHost.runOnce()
                    0
                }
                "stdio-loop" -> {
                    com.TTT.P2P.P2PStdioHost.runLoop()
                    0
                }
                "pcp-stdio-once" -> {
                    com.TTT.PipeContextProtocol.PcpStdioHost.runOnce()
                    0
                }
                "pcp-stdio-loop" -> {
                    com.TTT.PipeContextProtocol.PcpStdioHost.runLoop()
                    0
                }
                "http", "" -> {
                    // Embedded Ktor server. Run it inline (this blocks until shutdown).
                    val appClass = Class.forName("com.TTT.ApplicationKt")
                    val mainMethod = appClass.getMethod("main", Array<String>::class.java)
                    mainMethod.invoke(null, arrayOf<String>("--http"))
                    0
                }
                else -> {
                    System.err.println(
                        "TPipe_main: unknown mode '$mode'. " +
                        "Use stdio-once, stdio-loop, pcp-stdio-once, pcp-stdio-loop, or http."
                    )
                    -0x04 // TPIPE_ERR_INVALID_ARGUMENT
                }
            }
        } catch (e: Exception) {
            lastError.set("Failed to start mode '$mode': ${e.message}")
            -0x0E
        }
    }
}
