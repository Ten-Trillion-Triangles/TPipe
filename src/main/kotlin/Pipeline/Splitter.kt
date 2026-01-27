package com.TTT.Pipeline

import com.TTT.Debug.*
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.*

/**
 * Contains map of pipeline name to the multimodal content object it generated through it's llm call. Used as a
 * "drip pan" to collect the flow of all the pipelines on the splitter.
 */
@kotlinx.serialization.Serializable
data class MultimodalCollection(
    var contents: MutableMap<String, MultimodalContent> = ConcurrentHashMap()
)
{
    /**Clear out this object so that it can be resued. This is assigned as val inside the Splitter class which this
     * object was made to support.
     */
    fun flush()
    {
        contents.clear()
    }
}

/**
 * Container class that allows parallel and independent execution of multiple pipelines. Pipeline results are dispatched
 * and stored inside a MultimodalCollection that can be gathered as each pipeline finishes or after all of them have finished.
 * Splitter supports deferred async await patterns, or fire and forget mechanism with callback functions.
 * 
 * Tracing Support:
 * - Container-level orchestration events (SPLITTER_START/END/SUCCESS/FAILURE)
 * - Content distribution tracking (SPLITTER_CONTENT_DISTRIBUTION)
 * - Pipeline dispatch and completion events (SPLITTER_PIPELINE_DISPATCH/COMPLETION)
 * - Callback execution tracing (SPLITTER_PIPELINE_CALLBACK/COMPLETION_CALLBACK)
 * - Parallel execution coordination (SPLITTER_PARALLEL_START/AWAIT)
 * 
 * Enable tracing with: splitter.enableTracing(config).init(config)
 */
class Splitter: P2PInterface
{
//----------------------------------------------Interface---------------------------------------------------------------

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        val result = executePipelines()
        result.awaitAll()

        val content = MultimodalContent()
        val collection = results

        for(result in collection.contents)
        {
            content.metadata[result.key] = result.value
        }

        return content
    }

//-------------------------------------------Properties--------------------------------------------------------------------


    /**
     * Collection to store results from all executed pipelines
     */
    val results = MultimodalCollection()

    /**
     * Tracing system properties for debugging and monitoring Splitter execution.
     */
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    private val splitterId = UUID.randomUUID().toString()

    /**
     * Optional delegate function that will be called anytime a pipeline exits in this splitter.
     */
    var onPipeLineFinish: (suspend (splitter: Splitter, pipeline: Pipeline, content: MultimodalContent) -> Unit)? = null

    /**
     * Optional delegate function that will be called when this splitter finishes running all pipelines it has bound
     * to it.
     */
    var onSplitterFinish: (suspend (splitter: Splitter) -> Unit)? = null

    /**
     * Required because the kotlin developers thought that a pair that has only immutable values was somehow useful
     * to anyone...
     */
    private data class ActivatorValue(
        var content: MultimodalContent,
        val pipelines: MutableList<Pipeline> = mutableListOf()
    )


    /**
     * Used to start up and activate any pipelines that are connected to this splitter. Each key maps a pair that
     * holds the multimodal content object, and a list of pipelines that will be activated and executed using this
     * key. Each pipeline can only be present once in this object.
     */
  private val activatorKeys: MutableMap<Any, ActivatorValue> = mutableMapOf()

    /**
     * Mutex for thread-safe callback execution and completion tracking
     */
    private val executionMutex = Mutex()

    /**
     * Flag to track if splitter is currently executing pipelines
     */
    private var isExecuting = false

    /**
     * Atomic counter to track completed pipelines for callback triggering
     */
    private var completedPipelines = AtomicInteger(0)

    /**
     * Total number of pipelines to execute, set before execution starts
     */
    private var totalPipelines = 0

    /**
     * Flag to ensure onSplitterFinish callback is called only once
     */
    private var splitterCompleted = false

//-------------------------------------------Constructor----------------------------------------------------------------

//-------------------------------------------Methods--------------------------------------------------------------------

    /**
     * Add a new multimodal content object for the splitter to manage. This content will be used as input
     * for all pipelines bound to the specified key during execution.
     *
     * @param key The activation key to associate with this content
     * @param content The multimodal content to be processed by pipelines
     *
     * @return This Splitter object for method chaining
     */
    fun addContent(key: Any, content: MultimodalContent) : Splitter
    {
        //Get existing activator value or create new one with the provided content.
        val activatorValue = activatorKeys[key] ?: ActivatorValue(content = content)
        
        //Update content even if key exists to allow content replacement.
        activatorValue.content = content
        activatorKeys[key] =  activatorValue
        return this
    }

    /**
     * Retrieves a list of all child pipelines managed by this splitter.
     * This aggregates pipelines from all activation keys.
     *
     * @return List of all child [Pipeline] objects.
     */
    fun getAllChildPipelines(): List<Pipeline> {
        return activatorKeys.values.flatMap { it.pipelines }
    }

    /**
     * Retrieves a map of pipeline names to their trace IDs for all child pipelines.
     * Useful for retrieving independent traces when [TraceConfig.mergeSplitterTraces] is false.
     *
     * @return Map where Key = Pipeline Name, Value = Trace ID
     */
    fun getChildTraceIds(): Map<String, String> {
        return getAllChildPipelines().associate { pipeline ->
            val name = if (pipeline.pipelineName.isNotEmpty()) pipeline.pipelineName else "UnknownPipeline-${pipeline.hashCode()}"
            name to pipeline.getTraceId()
        }
    }



    /**
     * Add pipeline to mapped key. Each pipeline can only exist once across all activation keys to prevent
     * duplicate execution. Multiple pipelines can be bound to the same key for parallel execution.
     *
     * @param key The activation key to bind this pipeline to
     * @param pipeline The pipeline to be executed when this key is activated
     *
     * @return This Splitter object for method chaining
     */
    fun addPipeline(key: Any, pipeline: Pipeline) : Splitter
    {
        //Check all existing keys to prevent duplicate pipeline binding across different keys.
        for(it in activatorKeys)
        {
            if(it.value.pipelines.contains(pipeline)) return this
        }

        //Get or create activator value for this key with empty content as default.
        val activatorValue = activatorKeys[key] ?: ActivatorValue(MultimodalContent())
        
        //Prevent duplicate binding to same key.
        if(activatorValue.pipelines.contains(pipeline)) return this
        
        //Add pipeline to the key's pipeline list.
        activatorValue.pipelines.add(pipeline)
        activatorKeys[key] = activatorValue
        return this
    }

    /**
     * Remove pipeline from all bound keys. Since pipelines can only exist once across all keys,
     * this method sweeps through all activator keys to find and remove the specified pipeline.
     *
     * @param pipeline The pipeline to remove from all activation keys
     *
     * @return This Splitter object for method chaining
     */
    fun removePipeline(pipeline: Pipeline) : Splitter
    {
        //Iterate through all activator keys to find and remove the pipeline.
        for(it in activatorKeys)
        {
            try
            {
                //Remove pipeline from this key's pipeline list.
                it.value.pipelines.remove(pipeline)
            }
            catch(e: Exception)
            {
                //Ignore removal errors and continue with other keys.
            }
        }

        return this
    }

    /**
     * Remove the entire activation key and all associated pipelines and content from the splitter.
     * This completely removes the key from execution consideration.
     *
     * @param key The activation key to remove completely
     *
     * @return This Splitter object for method chaining
     */
    fun removeKey(key: Any) : Splitter
    {
        //Remove the entire key entry including all pipelines and content.
        activatorKeys.remove(key)
        return this
    }

    /**
     * Enables tracing for this Splitter with the specified configuration.
     * Propagates tracing settings to all child pipelines.
     * 
     * @param config The tracing configuration to use
     * @return This Splitter object for method chaining
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)) : Splitter
    {
        this.tracingEnabled = true
        this.traceConfig = config
        PipeTracer.enable()
        return this
    }

    /**
     * Disables tracing for this Splitter.
     * @return This Splitter object for method chaining
     */
    fun disableTracing(): Splitter
    {
        this.tracingEnabled = false
        return this
    }

    /**
     * Internal tracing method for Splitter events.
     */
    private fun trace(
        eventType: TraceEventType,
        phase: TracePhase,
        content: MultimodalContent? = null,
        metadata: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    ) {
        if (!tracingEnabled) return
        
        if (!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return
        
        val enhancedMetadata = buildSplitterMetadata(metadata, traceConfig.detailLevel, eventType, error)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = splitterId,
            pipeName = "Splitter-${activatorKeys.size}keys",
            eventType = eventType,
            phase = phase,
            content = if (shouldIncludeContent(traceConfig.detailLevel)) content else null,
            contextSnapshot = null,
            metadata = if (traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
            error = error
        )
        
        PipeTracer.addEvent(splitterId, event)
    }

    /**
     * Builds Splitter-specific metadata based on verbosity level.
     */
    private fun buildSplitterMetadata(
        baseMetadata: Map<String, Any>,
        detailLevel: TraceDetailLevel,
        eventType: TraceEventType,
        error: Throwable?
    ): Map<String, Any> {
        val metadata = baseMetadata.toMutableMap()
        
        when (detailLevel) {
            TraceDetailLevel.MINIMAL -> {
                if (error != null) {
                    metadata["error"] = error.message ?: "Unknown error"
                }
            }
            
            TraceDetailLevel.NORMAL -> {
                metadata["splitterId"] = splitterId
                metadata["activatorKeyCount"] = activatorKeys.size
                metadata["totalPipelines"] = activatorKeys.values.sumOf { it.pipelines.size }
                if (error != null) {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                }
            }
            
            TraceDetailLevel.VERBOSE -> {
                metadata["splitterClass"] = this::class.qualifiedName ?: "Splitter"
                metadata["splitterId"] = splitterId
                metadata["activatorKeys"] = activatorKeys.keys.toList()
                metadata["pipelinesByKey"] = activatorKeys.mapValues { it.value.pipelines.size }
                metadata["hasOnPipelineFinish"] = (onPipeLineFinish != null).toString()
                metadata["hasOnSplitterFinish"] = (onSplitterFinish != null).toString()
                if (error != null) {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                }
            }
            
            TraceDetailLevel.DEBUG -> {
                metadata["splitterClass"] = this::class.qualifiedName ?: "Splitter"
                metadata["splitterId"] = splitterId
                metadata["activatorKeys"] = activatorKeys.keys.toList()
                metadata["pipelineDetails"] = activatorKeys.mapValues { entry ->
                    entry.value.pipelines.map { "${it.javaClass.simpleName}:${it.pipelineName}" }
                }
                metadata["resultCount"] = results.contents.size
                metadata["hasOnPipelineFinish"] = (onPipeLineFinish != null).toString()
                metadata["hasOnSplitterFinish"] = (onSplitterFinish != null).toString()
                if (error != null) {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                    metadata["stackTrace"] = error.stackTraceToString()
                }
            }
        }
        
        return metadata
    }

    private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean {
        return when (detailLevel) {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }

    /**
     * Gets trace report for this Splitter in the specified format.
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String {
        return PipeTracer.exportTrace(splitterId, format)
    }

    /**
     * Gets failure analysis for this Splitter if tracing is enabled.
     */
    fun getFailureAnalysis(): FailureAnalysis? {
        return if (tracingEnabled) PipeTracer.getFailureAnalysis(splitterId) else null
    }

    /**
     * Gets the unique trace ID for this Splitter.
     */
    fun getTraceId(): String = splitterId


    /**
     * Bind the callback delegate that will be invoked when any individual pipeline completes execution.
     * This callback is called for both successful and failed pipeline executions.
     *
     * @param func Suspend function that receives the splitter, completed pipeline, and result content
     *
     * @return This Splitter object for method chaining
     */
    fun setOnPipelineFinish(func: (suspend (splitter: Splitter, pipeline: Pipeline, content: MultimodalContent) -> Unit)) : Splitter
    {
        //Bind the pipeline completion callback function.
        onPipeLineFinish = func
        return this
    }

    /**
     * Bind the callback delegate that will be invoked when all pipelines in the splitter have completed
     * execution. This callback is guaranteed to be called only once, even with concurrent pipeline completions.
     *
     * @param func Suspend function that receives the splitter instance when all pipelines complete
     *
     * @return This Splitter object for method chaining
     */
    fun setOnSplitterFinish(func: (suspend (splitter: Splitter) -> Unit)) : Splitter
    {
        //Bind the splitter completion callback function.
        onSplitterFinish = func
        return this
    }


//-------------------------------------------------Main-----------------------------------------------------------------

    /**
     * Initialize all bound pipelines with the provided trace configuration. This must be called before
     * executing pipelines. Sets up pipeline container references and applies tracing if enabled.
     *
     * @param config The trace configuration to apply to all pipelines
     */
    suspend fun init(config: TraceConfig = TraceConfig())
    {
        // Initialize tracing if enabled
        if (tracingEnabled) {
            PipeTracer.startTrace(splitterId)
            trace(TraceEventType.SPLITTER_START, TracePhase.INITIALIZATION,
                  metadata = mapOf(
                      "activatorKeyCount" to activatorKeys.size,
                      "totalPipelines" to activatorKeys.values.sumOf { it.pipelines.size }
                  ))
        }
        
        //Iterate through all activator keys to initialize their bound pipelines.
        for(it in activatorKeys)
        {
            val pipelines = it.value.pipelines
            
            // Trace content distribution
            if (tracingEnabled) {
                trace(TraceEventType.SPLITTER_CONTENT_DISTRIBUTION, TracePhase.INITIALIZATION,
                      it.value.content,
                      metadata = mapOf(
                          "activatorKey" to it.key,
                          "pipelineCount" to pipelines.size,
                          "contentSize" to (it.value.content?.text?.length ?: 0)
                      ))
            }
            
            //Initialize each pipeline bound to this key.
            for(pipeline in pipelines)
            {
                //Set this splitter as the pipeline's container for context.
                pipeline.pipelineContainer = this
                
                //Apply tracing configuration if tracing is enabled.
                if(tracingEnabled)
                {
                    pipeline.enableTracing(traceConfig)
                    // Merge splitter traces if configured to do so
                    if (traceConfig.mergeSplitterTraces) {
                        // Set splitter ID as additional trace ID - this makes events appear in BOTH traces
                        for (pipe in pipeline.getPipes()) {
                            pipe.addTraceId(splitterId)
                            
                            // Prefix pipe name with pipeline name for clarity in trace visualization
                            val prefix = if (pipeline.pipelineName.isNotEmpty()) pipeline.pipelineName else it.key.toString()
                            if (prefix.isNotEmpty() && !pipe.pipeName.startsWith("$prefix:")) {
                                pipe.pipeName = "$prefix:${pipe.pipeName}"
                            }
                        }
                    }
                }
                
                //Initialize the pipeline for execution.
                pipeline.init(true)
            }
        }
    }

    /**
     * Core pipeline execution loop that launches all bound pipelines asynchronously in parallel.
     * Each pipeline executes with its associated content and results are collected in the results collection.
     * Pipeline failures are handled gracefully without affecting other pipeline executions.
     *
     * @return List of Deferred objects representing all launched pipeline executions
     */
    suspend fun executePipelines(): List<Deferred<Unit>>
    {
        results.flush() //Clear out before we start so that we aren't holding stale data here.

        if (tracingEnabled) {
            trace(TraceEventType.SPLITTER_PARALLEL_START, TracePhase.EXECUTION,
                  metadata = mapOf("totalJobs" to activatorKeys.values.sumOf { it.pipelines.size }))
        }

        return coroutineScope {
            val jobs = mutableListOf<Deferred<Unit>>()
            
            //Iterate through all activator keys and their bound pipelines.
            for ((key, activatorValue) in activatorKeys)
            {
                for (pipeline in activatorValue.pipelines)
                {
                    if (tracingEnabled) {
                        trace(TraceEventType.SPLITTER_PIPELINE_DISPATCH, TracePhase.EXECUTION,
                              activatorValue.content,
                              metadata = mapOf(
                                  "activatorKey" to key,
                                  "pipelineName" to pipeline.pipelineName
                              ))
                    }
                    
                    //Launch each pipeline in its own coroutine for parallel execution.
                    val job = async {
                        try
                        {
                            if (tracingEnabled) {
                                // Add pipeline start event to Splitter trace
                                trace(TraceEventType.PIPE_START, TracePhase.EXECUTION,
                                      activatorValue.content,
                                      metadata = mapOf(
                                          "pipelineName" to pipeline.pipelineName,
                                          "activatorKey" to key,
                                          "pipeCount" to pipeline.getPipes().size
                                      ))
                            }
                            
                            /**
                             * We need to copy the content to prevent any races or other unexpected behaviors. In kotlin objects being
                             * passed in variables is always a reference. So we need to do a full copy by using serialization.
                             *
                             * todo: Replace with a reflection based copy asap.
                             */
                            val originalContentAsJson = serialize(activatorValue.content)
                            val copiedContent = deserialize<MultimodalContent>(originalContentAsJson) ?: MultimodalContent()
                            
                            //Execute pipeline with the content associated with this key.
                            val result = pipeline.execute(copiedContent)
                            
                            if (tracingEnabled) {
                                // Add pipeline success event to Splitter trace
                                trace(TraceEventType.PIPE_SUCCESS, TracePhase.EXECUTION,
                                      result,
                                      metadata = mapOf(
                                          "pipelineName" to pipeline.pipelineName,
                                          "activatorKey" to key,
                                          "resultLength" to result.text.length,
                                          "pipeCount" to pipeline.getPipes().size
                                      ))
                            }
                            
                            //Store successful result in results collection.
                            storeResult(key, pipeline, result)
                            
                            if (tracingEnabled) {
                                trace(TraceEventType.SPLITTER_PIPELINE_COMPLETION, TracePhase.EXECUTION,
                                      result,
                                      metadata = mapOf(
                                          "pipelineName" to pipeline.pipelineName,
                                          "success" to true,
                                          "resultSize" to result.text.length
                                      ))
                            }
                            
                            //Invoke pipeline completion callback if set.
                            onPipeLineFinish?.let { callback ->
                                if (tracingEnabled) {
                                    trace(TraceEventType.SPLITTER_PIPELINE_CALLBACK, TracePhase.POST_PROCESSING,
                                          result,
                                          metadata = mapOf("pipelineName" to pipeline.pipelineName))
                                }
                                callback(this@Splitter, pipeline, result)
                            }
                        }
                        catch (e: Exception)
                        {
                            //Handle pipeline execution failure by creating error content.
                            val errorContent = MultimodalContent("Pipeline execution failed: ${e.message}")
                            
                            //Store error result in results collection.
                            storeResult(key, pipeline, errorContent)
                            
                            if (tracingEnabled) {
                                trace(TraceEventType.SPLITTER_FAILURE, TracePhase.EXECUTION,
                                      activatorValue.content,
                                      error = e,
                                      metadata = mapOf(
                                          "pipelineName" to pipeline.pipelineName,
                                          "activatorKey" to key
                                      ))
                            }
                            
                            //Invoke pipeline completion callback with error content.
                            onPipeLineFinish?.let { callback ->
                                if (tracingEnabled) {
                                    trace(TraceEventType.SPLITTER_PIPELINE_CALLBACK, TracePhase.POST_PROCESSING,
                                          errorContent,
                                          metadata = mapOf("pipelineName" to pipeline.pipelineName))
                                }
                                callback(this@Splitter, pipeline, errorContent)
                            }
                        }
                        Unit
                    }
                    jobs.add(job)
                }
            }
            
            // Launch completion monitoring
            if (tracingEnabled) {
                launch {
                    trace(TraceEventType.SPLITTER_PARALLEL_AWAIT, TracePhase.POST_PROCESSING,
                          metadata = mapOf("jobCount" to jobs.size))
                    
                    jobs.awaitAll()
                    
                    trace(TraceEventType.SPLITTER_RESULT_COLLECTION, TracePhase.POST_PROCESSING,
                          metadata = mapOf(
                              "resultCount" to results.contents.size,
                              "totalJobs" to jobs.size
                          ))
                    
                    // Execute splitter completion callback
                    onSplitterFinish?.let { callback ->
                        trace(TraceEventType.SPLITTER_COMPLETION_CALLBACK, TracePhase.CLEANUP,
                              metadata = mapOf("resultCount" to results.contents.size))
                        callback(this@Splitter)
                    }
                    
                    val finalEventType = if (results.contents.isEmpty()) {
                        TraceEventType.SPLITTER_FAILURE
                    } else {
                        TraceEventType.SPLITTER_SUCCESS
                    }
                    
                    trace(finalEventType, TracePhase.CLEANUP,
                          metadata = mapOf(
                              "totalResults" to results.contents.size,
                              "successfulPipelines" to results.contents.size,
                              "totalPipelines" to jobs.size
                          ))
                    
                    trace(TraceEventType.SPLITTER_END, TracePhase.CLEANUP)
                }
            }
            
            jobs
        }
    }

    /**
     * Store pipeline execution result in the thread-safe results collection and track completion progress.
     * Uses pipeline name if available, otherwise falls back to hashCode for unique identification.
     * Triggers splitter completion callback when all pipelines have finished.
     *
     * @param key The activation key associated with this pipeline execution
     * @param pipeline The pipeline that produced this result
     * @param result The multimodal content result from pipeline execution
     */
    private suspend fun storeResult(key: Any, pipeline: Pipeline, result: MultimodalContent)
    {
        var uniqueKey = ""

        //Use pipeline name if available for better result identification.
        if(pipeline.pipelineName.isNotEmpty())
        {
            uniqueKey = pipeline.pipelineName
        }
        else
        {
            //Fall back to hashCode if no pipeline name is set.
            uniqueKey = "${pipeline.hashCode()}"
        }

        //Store result in thread-safe ConcurrentHashMap.
        results.contents[uniqueKey] = result
        
        //Atomically increment completion counter.
        val completed = completedPipelines.incrementAndGet()
        
        //Check if all pipelines have completed to trigger splitter completion.
        if (completed >= totalPipelines)
        {
            handleSplitterCompletion()
        }
    }

    /**
     * Handle splitter completion by invoking the completion callback in a thread-safe manner.
     * Uses mutex locking to ensure the callback is invoked only once, even with concurrent
     * pipeline completions racing to trigger this method.
     */
    private suspend fun handleSplitterCompletion()
    {
        //Use mutex to ensure thread-safe callback execution.
        executionMutex.withLock {
            //Check completion flag to prevent multiple callback invocations.
            if (!splitterCompleted)
            {
                //Mark splitter as completed.
                splitterCompleted = true
                
                //Invoke splitter completion callback if set.
                onSplitterFinish?.invoke(this@Splitter)
            }
        }
    }

    companion object {

        /**
         * Convert standard content object to a collection object. This is useful for
         * routing the splitter through a pipe internally.
         */
        fun MultimodalContent.toMultimodalCollection() : MultimodalCollection
        {
            val newCollection = MultimodalCollection()

            for(it in metadata)
            {
                if(it.value is MultimodalContent && it.key is String)
                {
                    newCollection.contents[it.key as String] = it.value as MultimodalContent
                }
            }

            return newCollection
        }
    }

}