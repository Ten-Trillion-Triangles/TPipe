package com.TTT.Debug

import com.TTT.Context.ContextWindow

data class FailureAnalysis(
    val lastSuccessfulPipe: String?,
    val failurePoint: TraceEvent?,
    val failureReason: String,
    val contextAtFailure: ContextWindow?,
    val suggestedFixes: List<String>,
    val executionPath: List<String>
)