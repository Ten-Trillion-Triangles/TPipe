package com.TTT.Structs

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.PromptMode
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.PcpContext

/**
 * Detached pipe configuration snapshot.
 *
 * Mutable members are expected to be deep-copied when the snapshot is exported or applied so the source pipe and
 * any caller that holds on to the settings object do not end up sharing live state.
 */
data class PipeSettings(
    var pipeName: String? = null,
    var provider: ProviderName? = null,
    var model: String? = null,
    var promptMode: PromptMode? = null,
    var systemPrompt: String? = null,
    var userPrompt: String? = null,
    var multimodalInput: MultimodalContent? = null,
    var jsonInput: String? = null,
    var jsonOutput: String? = null,
    var pcpContext: PcpContext? = null,
    var supportsNativeJson: Boolean? = null,
    var temperature: Double? = null,
    var topP: Double? = null,
    var topK: Int? = null,
    var maxTokens: Int? = null,
    var contextWindowSize: Int? = null,
    var contextWindow: ContextWindow? = null,
    var miniContextBank: MiniBank? = null,
    var readFromGlobalContext: Boolean? = null,
    var readFromPipelineContext: Boolean? = null,
    var updatePipelineContextOnExit: Boolean? = null,
    var autoInjectContext: Boolean? = null,
    var autoTruncateContext: Boolean? = null,
    var emplaceLorebook: Boolean? = null,
    var appendLoreBook: Boolean? = null,
    var loreBookFillMode: Boolean? = null,
    var loreBookFillAndSplitMode: Boolean? = null,
    var useModelReasoning: Boolean? = null,
    var modelReasoningSettingsV2: Int? = null,
    var modelReasoningSettingsV3: String? = null,
    var pageKey: String? = null,
    var pageKeyList: MutableList<String>? = null,
    var contextWindowTruncation: ContextWindowSettings? = null,
    var truncateContextAsString: Boolean? = null,
    var repetitionPenalty: Double? = null,
    var stopSequences: List<String>? = null,
    var multiplyWindowSizeBy: Int? = null,
    var countSubWordsInFirstWord: Boolean? = null,
    var favorWholeWords: Boolean? = null,
    var countOnlyFirstWordFound: Boolean? = null,
    var splitForNonWordChar: Boolean? = null,
    var alwaysSplitIfWholeWordExists: Boolean? = null,
    var countSubWordsIfSplit: Boolean? = null,
    var nonWordSplitCount: Int? = null,
    var tracingEnabled: Boolean? = null,
    var pipeId: String? = null,
    var currentPipelineId: String? = null,
    var tokenBudgetSettings: TokenBudgetSettings? = null
)

//Todo: Add pipe settings for bedrock pipe class, and ollama.
