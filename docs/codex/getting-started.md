# Getting Started with Codex OAuth

`TPipe-Codex` adds ChatGPT subscription-backed OAuth access to the existing
`GenericOpenAIPipe` and `ApiMode.OpenAIResponses` transport. It does not add a
new provider enum or a separate agent engine.

## Dependency

```kotlin
// settings.gradle.kts
include(":TPipe-Codex")

// build.gradle.kts
dependencies {
    implementation(project(":TPipe-Codex"))
}
```

## Create a pipe

```kotlin
import codexPipe.CodexPipes
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val pipe = CodexPipes.create("gpt-5-codex")
        .setSystemPrompt("You are a concise assistant.")
        .init()

    println(pipe.execute("Say hello in one sentence.").text)
}
```

The factory selects the Codex backend URL, `ApiMode.OpenAIResponses`, forced
SSE transport, `store=false`, and encrypted-reasoning inclusion. The access
profile supplies OAuth headers at request time, so tokens are not serialized
with the pipe. Reuse one `CodexAuthManager` across a pipeline's pipes.

## Device login

```kotlin
val auth = CodexAuthManager.default()
val device = auth.requestDeviceCode()
println("Open ${device.verificationUrl} and enter ${device.userCode}")
auth.completeDeviceLogin(device)
```

The device flow is cancellable. Credentials are written only after the
authorization-code exchange succeeds. A pending device poll is recognized as
HTTP 403 or 404 and honors the server-provided interval, with a 15-minute
bound.

## Credential files and import

TPipe owns `~/.tpipe/codex/auth.json`. Set `TPIPE_CODEX_AUTH_FILE` to choose a
different TPipe path. When the TPipe file is missing, the manager can perform a
one-way, file-backed import from `$CODEX_HOME/auth.json` or `~/.codex/auth.json`.
The source file is never modified. Keyring-only credentials, API keys, PATs,
and agent identity files are not imported.

The store is written through a temporary file, flushed, and atomically moved
into place where the filesystem supports it. The directory and file are
restricted to the current user on POSIX filesystems.

## Refresh and account headers

The manager refreshes proactively within five minutes of a known access-token
expiry, or after eight days when the token has no decodable expiry. A 401 from
inference or model discovery causes one refresh attempt and one request retry.
Rotated refresh tokens replace the stored value atomically. Requests include
`ChatGPT-Account-ID` only when the credential metadata provides it, and include
`X-OpenAI-Fedramp: true` only for a FedRAMP account.

## Model discovery

```kotlin
val catalog = CodexModelCatalogClient(auth)
val models = catalog.listModels()
models.forEach { println("${it.slug}: ${it.displayName}") }
println("ETag: ${catalog.lastCatalog?.etag}")
```

The model endpoint is bounded to approximately five seconds and cached in
memory for five minutes. The result preserves the response ETag and exposes
reasoning levels, context-window metadata, input modalities, visibility,
priority, verbosity support, and service tiers when advertised.

## Defaults integration

`TPipe-Defaults` provides the same transport through `CodexConfiguration`:

```kotlin
val manifold = ManifoldDefaults.withCodex(
    CodexConfiguration(model = "gpt-5-codex")
)
```

The defaults DSL also supports `defaults { codex(configuration) }`, and
`PumpStationDefaults.withCodex(...)` wires one shared auth manager into its
judge and dispatch agents.

## Scope boundary

TPipe PCP remains TPipe's sandboxed tool execution system. Codex native tool
translation is intentionally not performed by this module, and no OAuth
tokens are logged or placed in serialized pipe configuration.
