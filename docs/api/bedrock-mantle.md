# Bedrock Mantle Package API

## Table of Contents
- [Overview](#overview)
- [BedrockMantleConfiguration](#bedrockmantleconfiguration)
- [BedrockMantleAuth](#bedrockmantleauth)
  - [Bearer](#bearer)
  - [SigV4](#sigv4)
  - [Streaming](#streaming)
- [BedrockMantleEnv](#bedrockmantleenv)
- [Next Steps](#next-steps)

## Overview

The Bedrock Mantle package lives under `TPipe-GenericOpenAI/src/main/kotlin/genericOpenAIPipe/`. It exposes three public types:

```kotlin
package genericOpenAIPipe.mantle

sealed class BedrockMantleAuth { … }
data class  BedrockMantleConfiguration(region: String, modelId: String, apiMode: ApiMode = ApiMode.OpenAI)

package genericOpenAIPipe.env

object BedrockMantleEnv { … }
```

The pipe-level builders (`GenericOpenAIPipe.setBedrockMantle(...)`, `setBedrockMantleWithResponses(...)`, `setBedrockMantleAuth(...)`) live on `GenericOpenAIPipe` and are documented in [`docs/api/generic-openai-pipe.md`](generic-openai-pipe.md). This page covers the underlying types those builders construct and consume.

## BedrockMantleConfiguration

```kotlin
@kotlinx.serialization.Serializable
data class BedrockMantleConfiguration(
    val region: String,
    val modelId: String,
    val apiMode: ApiMode = ApiMode.OpenAI,
)
```

Immutable configuration record for the regional Mantle endpoint. Constructed by the `setBedrockMantle(...)` and `setBedrockMantleWithResponses(...)` builders.

**Constructor parameters:**

- `region` — AWS region code (e.g. `us-east-1`, `us-east-2`, `us-west-2`). Used to build the regional Mantle URL. Must be non-blank; the constructor throws `IllegalArgumentException("region cannot be blank")` otherwise.
- `modelId` — Bedrock model identifier (e.g. `google.gemma-4-31b`). Passed verbatim on the request body. Must be non-blank; the constructor throws `IllegalArgumentException("modelId cannot be blank")` otherwise.
- `apiMode` — Which OpenAI-shaped API surface to dispatch against. Defaults to `ApiMode.OpenAI` (Chat Completions). Set to `ApiMode.OpenAIResponses` to drive the Responses wire format at the same endpoint.

**Public functions:**

| Function | Returns | Description |
|----------|---------|-------------|
| `endpoint()` | `String` | Fully-qualified Mantle endpoint URL: `https://bedrock-mantle.{region}.api.aws/openai/v1`. Trailing whitespace is trimmed from `region`. |

**Companion-object factories:**

| Function | Returns | Description |
|----------|---------|-------------|
| `forRegion(region: String, modelId: String): BedrockMantleConfiguration` | configuration with `apiMode = ApiMode.OpenAI` | Construct for the OpenAI Chat Completions wire format. |
| `forRegionWithResponses(region: String, modelId: String): BedrockMantleConfiguration` | configuration with `apiMode = ApiMode.OpenAIResponses` | Construct for the OpenAI Responses wire format. |

## BedrockMantleAuth

```kotlin
sealed class BedrockMantleAuth
{
    abstract fun authHeaders(
        method: String,
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): Map<String, String>

    data class Bearer(val apiKey: String) : BedrockMantleAuth()
    data class SigV4(val signer: SigV4Signer) : BedrockMantleAuth()
    data class Streaming(
        val initialSigner: SigV4Signer,
        val chunkedSigner: ChunkedSigV4Signer,
        val decodedContentLength: Long,
    ) : BedrockMantleAuth()
}
```

Auth-header provider for Mantle requests. The sealed class has three variants — one per authentication mode Mantle supports. The pipe selects the variant automatically based on what `init()` resolves, but callers may inject any shape explicitly via `GenericOpenAIPipe.setBedrockMantleAuth(...)`.

**Common method — `authHeaders(method, url, body, headers): Map<String, String>`**

Computes the HTTP headers required to authenticate the given request. The returned map is added on top of any caller-supplied non-auth headers. The `authorization` header key is always lowercase, matching the casing conventions of the rest of the `GenericOpenAI` module.

- `method` — HTTP method in uppercase (e.g. `POST`).
- `url` — Full request URL (scheme + host + path + query).
- `body` — Request payload as bytes. May be empty.
- `headers` — Caller-supplied headers. Authentication-specific headers (Host, X-Amz-Date, X-Amz-Security-Token, etc.) are merged in by the underlying implementation as required.

### Bearer

```kotlin
data class Bearer(val apiKey: String) : BedrockMantleAuth()
```

Bearer-token authentication using a Bedrock API key. Sends `Authorization: Bearer <apiKey>` on every request. The API key is a long-term or short-term Bedrock API key, not an IAM access key id.

- Constructor: throws `IllegalArgumentException("Bearer apiKey cannot be blank")` when `apiKey` is blank.

### SigV4

```kotlin
data class SigV4(val signer: SigV4Signer) : BedrockMantleAuth()
```

AWS SigV4 authentication for non-streaming requests. The `signer` is created once and reused across requests; each call to `authHeaders` recomputes the signature for the current request shape.

The signed request headers are:

```
authorization: AWS4-HMAC-SHA256 Credential=<key>/<date>/<region>/bedrock-mantle/aws4_request, SignedHeaders=..., Signature=...
content-type: application/json
host: bedrock-mantle.<region>.api.aws
x-amz-content-sha256: <sha256(body)>
x-amz-date: <YYYYMMDDTHHMMSSZ>
```

For temporary credentials (`AWS_SESSION_TOKEN` or `BEDROCK_MANTLE_SESSION_TOKEN` set), `x-amz-security-token` is added automatically.

### Streaming

```kotlin
data class Streaming(
    val initialSigner: SigV4Signer,
    val chunkedSigner: ChunkedSigV4Signer,
    val decodedContentLength: Long,
) : BedrockMantleAuth()
```

AWS SigV4 chunked-encoding authentication for streaming requests. Carries both the `initialSigner` (used to compute the seed signature for the initial request) and the `chunkedSigner` (used to compute the per-chunk signatures as bytes flow to the wire). The transport layer calls `signChunk(...)` per body chunk.

- `decodedContentLength` — The size of the body in bytes (the `x-amz-decoded-content-length` header value). Use `0` for streaming requests where the length is unknown up front. Must be non-negative; the constructor throws `IllegalArgumentException("decodedContentLength must be non-negative (got <value>)")` otherwise.

**Initial request headers** carry the seed signature (SigV4 over the canonical request whose payload hash is the streaming-payload marker) plus:

```
transfer-encoding: chunked
content-encoding: aws-chunked
x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD
x-amz-decoded-content-length: <body bytes>
```

**Body wire format** is a sequence of chunked blocks:

```
<size_hex>;<signature>\r\n
<chunk_body>\r\n
```

where `<size_hex>` is the chunk byte length as a 5-character lowercase hex and `<signature>` is the per-chunk signature chained from the seed signature. The terminator chunk is a 0-byte block (`0;<terminator-sig>\r\n\r\n`).

**Public method — `signChunk(previousSignatureHex: String, chunkBytes: ByteArray): ChunkedSigV4Signer.ChunkSignatureResult`**

Signs a single chunk of the chunked-encoding body. The transport layer calls this per chunk as it streams to the wire.

- `previousSignatureHex` — The seed signature (chunk 0) or the previous chunk's signature hex (chunk N>0).
- `chunkBytes` — The chunk body bytes. May be empty for the final terminator chunk.

### Companion-object factories

| Function | Returns | Description |
|----------|---------|-------------|
| `bearer(apiKey: String): Bearer` | `Bearer` | Construct Bearer auth from a Bedrock API key. |
| `sigV4(accessKeyId: String, secretAccessKey: String, sessionToken: String? = null, region: String, service: String = "bedrock-mantle", clock: Clock = SystemClock): SigV4` | `SigV4` | Construct SigV4 auth from explicit credentials. `clock` is injectable for deterministic timestamps in tests. |
| `sigV4FromEnv(regionOverride: String? = null): SigV4?` | `SigV4?` | Construct SigV4 auth by resolving credentials from `BedrockMantleEnv`. Returns `null` when the env cannot resolve both an access key id and a secret access key, so callers can fall back to bearer mode without throwing. |
| `streaming(accessKeyId: String, secretAccessKey: String, sessionToken: String? = null, region: String, service: String = "bedrock-mantle", decodedContentLength: Long = 0L, clock: Clock = SystemClock): Streaming` | `Streaming` | Construct chunked-streaming SigV4 auth from explicit credentials. Both the seed signer and the chunked signer share credentials and region. |
| `streamingFromEnv(regionOverride: String? = null, decodedContentLength: Long = 0L): Streaming?` | `Streaming?` | Construct chunked-streaming SigV4 auth by resolving credentials from `BedrockMantleEnv`. Returns `null` when credentials are unresolvable. |

## BedrockMantleEnv

```kotlin
package genericOpenAIPipe.env

object BedrockMantleEnv
```

Central environment configuration for Mantle credentials. Mirrors the shape of `GenericOpenAIEnv`: every accessor follows the same precedence chain — programmatic setter first, then `-D` system property, then env var, then standard AWS env var. The accessors are zero-arg and side-effect-free so `SigV4Signer` can call them at request time without holding references to live state.

### Precedence chain

For the access key id (top wins):

1. Programmatic setter (test-only)
2. System property `tpipe.bedrockMantle.accessKeyId`
3. Env var `BEDROCK_MANTLE_ACCESS_KEY_ID`
4. Standard AWS env var `AWS_ACCESS_KEY_ID`

The same chain applies symmetrically to the secret access key, session token, and region. The region defaults to `us-east-1` when nothing is set.

### Constants

| Constant | Value |
|----------|-------|
| `DEFAULT_REGION` | `us-east-1` |
| `ACCESS_KEY_ID_SYSTEM_PROPERTY` | `tpipe.bedrockMantle.accessKeyId` |
| `SECRET_ACCESS_KEY_SYSTEM_PROPERTY` | `tpipe.bedrockMantle.secretAccessKey` |
| `SESSION_TOKEN_SYSTEM_PROPERTY` | `tpipe.bedrockMantle.sessionToken` |
| `REGION_SYSTEM_PROPERTY` | `tpipe.bedrockMantle.region` |
| `ACCESS_KEY_ID_ENV_VAR` | `BEDROCK_MANTLE_ACCESS_KEY_ID` |
| `SECRET_ACCESS_KEY_ENV_VAR` | `BEDROCK_MANTLE_SECRET_ACCESS_KEY` |
| `SESSION_TOKEN_ENV_VAR` | `BEDROCK_MANTLE_SESSION_TOKEN` |
| `REGION_ENV_VAR` | `BEDROCK_MANTLE_REGION` |
| `AWS_ACCESS_KEY_ID_ENV_VAR` | `AWS_ACCESS_KEY_ID` |
| `AWS_SECRET_ACCESS_KEY_ENV_VAR` | `AWS_SECRET_ACCESS_KEY` |
| `AWS_SESSION_TOKEN_ENV_VAR` | `AWS_SESSION_TOKEN` |
| `AWS_REGION_ENV_VAR` | `AWS_REGION` |

### Programmatic setters and clearers

| Function | Description |
|----------|-------------|
| `setAccessKeyId(value: String)` | Override the AWS access key id. Intended for tests and explicit credential injection. |
| `clearAccessKeyId()` | Clear the override; falls back to system property / env var / AWS env var chain. |
| `setSecretAccessKey(value: String)` | Override the AWS secret access key. |
| `clearSecretAccessKey()` | Clear the override. |
| `setSessionToken(value: String)` | Override the AWS session token (for temporary credentials). |
| `clearSessionToken()` | Clear the override. |
| `setRegion(value: String)` | Override the AWS region. |
| `clearRegion()` | Clear the override. |

### Resolvers

| Function | Returns | Description |
|----------|---------|-------------|
| `resolveAccessKeyId(): String` | access key id (empty when nothing is set) | Walk the precedence chain and return the first non-blank value. |
| `resolveSecretAccessKey(): String` | secret access key (empty when nothing is set) | Walk the precedence chain. |
| `resolveSessionToken(): String` | session token (empty when nothing is set) | Walk the precedence chain. |
| `resolveRegion(): String` | AWS region (defaults to `us-east-1`) | Walk the precedence chain; fall through to `us-east-1` when nothing is set. |

## Next Steps

- [`docs/bedrock/mantle.md`](../bedrock/mantle.md) — getting-started, auth setup, streaming, reasoning on Mantle
- [`docs/api/generic-openai-pipe.md`](generic-openai-pipe.md) — `GenericOpenAIPipe` builders that consume these types (`setBedrockMantle`, `setBedrockMantleWithResponses`, `setBedrockMantleAuth`)
- [`docs/core-concepts/reasoning-pipes.md`](../core-concepts/reasoning-pipes.md) — how `modelReasoning` is populated across non-streaming and streaming paths