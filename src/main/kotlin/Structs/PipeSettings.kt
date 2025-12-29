package com.TTT.Structs

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.PromptMode
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.PcpContext

data class PipeSettings(
    var pipeName: String = "",
    var provider: ProviderName = ProviderName.Aws,
    var model: String = "",
    var promptMode: PromptMode = PromptMode.singlePrompt,
    var systemPrompt: String = "",
    var userPrompt: String = "",
    var multimodalInput: MultimodalContent = MultimodalContent(),
    var jsonInput: String = "",
    var jsonOutput: String = "",
    var pcpContext: PcpContext = PcpContext(),
    var supportsNativeJson: Boolean = true,
    var temperature: Double = 1.0,
    var topP: Double = 0.7,
    var topK: Int = 1000,
    var maxTokens: Int = 2048,
    var contextWindowSize: Int = 10000,
    var contextWindow: ContextWindow = ContextWindow(),
    var miniContextBank: MiniBank = MiniBank(),
    var readFromGlobalContext: Boolean = false,
    var readFromPipelineContext: Boolean = false,
    var updatePipelineContextOnExit: Boolean = false,
    var autoInjectContext: Boolean = false,
    var autoTruncateContext: Boolean = false,
    var emplaceLorebook: Boolean = true,
    var appendLoreBook: Boolean = false,
    var useModelReasoning: Boolean = false,
    var modelReasoningSettingsV2: Int = 5000,
    var modelReasoningSettingsV3: String = "",
    var pageKey: String = "",
    var pageKeyList: MutableList<String> = mutableListOf(),
    var contextWindowTruncation: ContextWindowSettings = ContextWindowSettings.TruncateTop,
    var truncateContextAsString: Boolean = false,
    var repetitionPenalty: Double = 0.0,
    var stopSequences: List<String> = listOf(),
    var multiplyWindowSizeBy: Int = 0,
    var countSubWordsInFirstWord: Boolean = true,
    var favorWholeWords: Boolean = true,
    var countOnlyFirstWordFound: Boolean = false,
    var splitForNonWordChar: Boolean = true,
    var alwaysSplitIfWholeWordExists: Boolean = false,
    var countSubWordsIfSplit: Boolean = false,
    var nonWordSplitCount: Int = 4,
    var tracingEnabled: Boolean = false,
    var pipeId: String = "",
    var currentPipelineId: String? = null,
    var tokenBudgetSettings: TokenBudgetSettings? = null
)

//Todo: Add pipe settings for bedrock pipe class, and ollama.
