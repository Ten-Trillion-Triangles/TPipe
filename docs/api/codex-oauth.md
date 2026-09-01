# Codex OAuth API

## `CodexAuthManager`

`CodexAuthManager` owns the single active OAuth profile, device login,
proactive refresh, 401 recovery, and credential-store coordination.

```kotlin
val manager = CodexAuthManager.default()
val account = manager.currentAccount()
val headers = manager.authorizationHeaders()
```

`authorizationHeaders()` returns a bearer header plus optional account and
FedRAMP headers. It throws `CodexAuthenticationException` when an interactive
login is required. `recoverUnauthorized()` is a bounded-refresh hook for the
GenericOpenAI transport and does not issue the inference retry itself.

## `CodexPipes`

```kotlin
val pipe = CodexPipes.create("gpt-5-codex", manager)
```

The factory returns `GenericOpenAIPipe`, configured with the Codex base URL and
`ApiMode.OpenAIResponses`. Existing TPipe pipeline, streaming callback, tracing,
and PCP orchestration APIs remain unchanged.

For an existing pipe, use `useCodexOAuth(manager, model = "gpt-5-codex")`
before its first request.

## Credential storage

`FileCodexCredentialStore` stores the OAuth token set and non-secret account
metadata in the TPipe-owned path. `TPIPE_CODEX_AUTH_FILE` takes precedence over
the default path. `CodexCliCredentialImporter` only reads the file-backed
`tokens` object from the Codex CLI auth file and refuses to overwrite an
existing TPipe profile.

## `CodexModelCatalogClient`

`listModels(forceRefresh = false)` returns normalized `CodexModelInfo` records.
The client uses the same manager, requests `client_version`, retains the last
successful ETag in `lastCatalog`, and uses a five-minute in-memory cache.

## GenericOpenAI access-profile contract

`GenericOpenAIAccessProfile` is a transient seam for request-time auth and
optional Responses wire policy. Precedence is Mantle auth, access profile, then
the existing API-key/environment path. Profiles are locked after the first
request and are omitted from pipe serialization. The default policy is empty,
so existing GenericOpenAI, MiniMax, Anthropic, OpenRouter, Ollama, Bedrock, and
Mantle behavior retains its prior wire shape.
