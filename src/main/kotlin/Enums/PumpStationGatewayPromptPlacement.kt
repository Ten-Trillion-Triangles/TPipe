package com.TTT.Enums

/**
 * Selects where the gateway's injectable user prompt is placed in the assembled prompt.
 *
 * [SystemPrompt] appends the text to the gateway agent's system prompt, after the contract
 * prompt and the saved criteria string. The remaining values place the text inside the
 * gateway's user message, relative to the conversation-history block.
 */
enum class PumpStationGatewayPromptPlacement
{
    SystemPrompt,
    BeforeHistory,
    AfterHistory,
    Suffix
}
