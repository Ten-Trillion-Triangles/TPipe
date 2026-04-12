package com.TTT.Enums

/**
 * Denotes provider name for AI services TPipe supports. Primarily used for logic that needs to be different
 * depending on the provider. Must be defined by the module that implements the provider.
 */
enum class ProviderName
{
    Aws, //Aws bedrock. Supports direct api calls, and step functions.
    Nai, //Novel AI. Supports rest api only.
    Gemini, //Google Gemini. Supports rest api only.
    Gpt, //Open AI models.  Supports sdk, and rest api.
    Ollama, //Ollama server. Supports all ollama api calls for local ollama servers.
    OpenRouter //OpenRouter. Supports v1/chat/completions with OpenAI-compatible API.
}