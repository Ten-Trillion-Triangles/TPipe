package com.TTT.Enums


/**
 * Denotes how TPipe should handle prompts. Some AI models support chat with internal context. Others, only support
 * single prompt interfaces. TPipe also has support to storing and handling context directly and supplying it
 * to the AI model.
 */
enum class PromptMode
{
    /**Single prompt mode. Context is not stored or remembered by the AI model.*/
    singlePrompt,

    /**Chat mode. Supported by some AI models. Context is stored and remembered by the AI model.*/
    chat,

    /**Designate TPipe to simulate the internal context of the AI model for models that do not support it.*/
    internalContext
}