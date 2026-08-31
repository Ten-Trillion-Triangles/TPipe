# GenericOpenAI localhost transport compatibility

Status: implemented in the isolated `codex/generic-openai-local-transport` worktree.

## Scope

- Permit plaintext HTTP automatically only for exact loopback targets: `localhost`, `127.0.0.0/8`, and `::1`.
- Preserve HTTPS hosted behavior and the explicit `TPIPE_ALLOW_INSECURE_BASEURL` / `tpipe.allowInsecureBaseUrl` compatibility overrides for non-loopback HTTP.
- Allow loopback initialization without an API key and omit blank Bearer / `x-api-key` headers while retaining Anthropic's `anthropic-version` header.
- Add `GenericOpenAIEndpointProfile`, including hosted defaults and `localV1()` routes, and lock profile changes after the first request.
- Propagate the profile through `Defaults.GenericOpenAIConfiguration` and `GenericOpenAIDefaults`.
- Keep serializers, parsers, retry policy, inference behavior, and downstream provider modules unchanged.

## Design

`BaseUrlPolicy` parses `java.net.URI` values and compares host text without DNS resolution. This makes the loopback boundary deterministic and rejects malformed, credential-bearing, query-bearing, fragment-bearing, and non-HTTP(S) base URLs before a request can be constructed.

`GenericOpenAIEndpointProfile` owns only validated relative endpoint paths. The default profile returns the existing hosted suffixes; `localV1()` returns `/v1/chat/completions`, `/v1/responses`, and `/v1/messages`. Both Ktor and direct streaming request construction already call the shared `getEndpoint()` path, so profile selection stays transport-only.

## TDD coverage

Fake-driven tests cover:

- loopback acceptance, non-loopback HTTP rejection, malformed and hostname-spoofing rejection, and explicit override compatibility;
- loopback no-key initialization, credential-header omission, keyed Bearer auth, Anthropic version-header retention, and hosted no-key failure;
- default profile preservation, `localV1()` values, path validation, first-request locking, non-streaming Ktor URL capture, and direct-streaming URL/header capture;
- Defaults-factory profile propagation.

Ktor `MockEngine` and the existing `MockStreamingConnectionFactory` return canned serialized responses/SSE bodies. No real model call or running provider is required.

## Verification

The focused GenericOpenAI, Responses dispatch, and profile command passes all 23 tests. The Defaults module passes all 42 tests, including the factory regression. The full repository test command was also run; it remains red only in five unrelated existing GenericOpenAI live/token-tracing tests (missing live credentials and pre-existing Anthropic streaming token expectations). `git diff --check` is clean, and the new transport/profile tests make no real model calls.
