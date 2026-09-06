package com.TTT.Debug

import com.TTT.Config.TPipeConfig

class TracingBuilder
{
    private var config = TraceConfig()
    
    fun enabled(enabled: Boolean = true): TracingBuilder {
        config = config.copy(enabled = enabled)
        return this
    }
    
    fun maxHistory(count: Int): TracingBuilder {
        config = config.copy(maxHistory = count)
        return this
    }
    
    fun outputFormat(format: TraceFormat): TracingBuilder {
        config = config.copy(outputFormat = format)
        return this
    }
    
    fun detailLevel(level: TraceDetailLevel): TracingBuilder {
        config = config.copy(detailLevel = level)
        return this
    }
    
    /**
     * Enable automatic trace export.
     *
     * @param enabled Whether automatic export is enabled.
     * @param path Destination directory; defaults to the active instance's trace directory.
     * @return This builder for method chaining.
     */
    fun autoExport(enabled: Boolean = true, path: String = TPipeConfig.getTraceDir()): TracingBuilder {
        config = config.copy(autoExport = enabled, exportPath = path)
        return this
    }
    
    fun includeContext(include: Boolean = true): TracingBuilder {
        config = config.copy(includeContext = include)
        return this
    }
    
    fun includeMetadata(include: Boolean = true): TracingBuilder {
        config = config.copy(includeMetadata = include)
        return this
    }
    
    fun build(): TraceConfig {
        return config
    }
}
