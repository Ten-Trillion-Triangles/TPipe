package com.TTT.Enums

/**
 * Selects where a pipe's todo list is injected when [com.TTT.Pipe.Pipe.setTodoListPageKey] is configured.
 *
 * Mirrors the ReasoningInjector target set from TPipe-Defaults so todo content
 * can be placed anywhere a reasoning stream can, keeping the two injection
 * systems consistent for coders configuring both.
 *
 * [SystemPrompt] is the default and preserves the legacy applySystemPrompt
 * composition behavior of setTodoListPageKey.
 */
@kotlinx.serialization.Serializable
enum class TodoInjectionPoint
{
    SystemPrompt,
    BeforeUserPrompt,
    BeforeUserPromptWithConverse,
    AfterUserPrompt,
    AfterUserPromptWithConverse,
    AsContext
}
