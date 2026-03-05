#!/bin/bash
# For line 580: trace(TraceEventType.API_CALL_FAILURE, ..., metadata = mapOf("error" to e.message))
# The map is Map<String, String?>, but mapOf expects Map<String, Any> because tracing accepts Map<String, Any>. So we do e.message ?: "Unknown error"

sed -i 's/"error" to e.message/"error" to (e.message ?: "Unknown error") as Any/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

# For line 1098 & 4128:
# `GuardrailTrace.fromValue(guardrailTrace)` expects a Trace enum, not GuardrailTrace, wait...
# Wait, for `InvokeModelRequest.Builder`, the property `trace` is actually of type `aws.sdk.kotlin.services.bedrockruntime.model.Trace?` and NOT `GuardrailTrace` for some reason? Let's check the AWS SDK import.
# Ah, `aws.sdk.kotlin.services.bedrockruntime.model.Trace` is the AWS SDK model enum for InvokeModelRequest.
# Let's use `aws.sdk.kotlin.services.bedrockruntime.model.Trace.fromValue(guardrailTrace)` instead of `GuardrailTrace`.
sed -i 's/GuardrailTrace.fromValue/aws.sdk.kotlin.services.bedrockruntime.model.Trace.fromValue/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

# Let's fix lines 1098/4128 nullable string error. "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type String?". Wait, guardrailIdentifier on InvokeModelRequest.Builder is nullable? Yes, `this.guardrailIdentifier = ...` but in `if (guardrailIdentifier.isNotEmpty())`, what is `guardrailIdentifier` resolving to? It's resolving to `InvokeModelRequest.Builder.guardrailIdentifier` which is `String?`.
# So it should be `if (this@BedrockPipe.guardrailIdentifier.isNotEmpty())` inside the builder.
sed -i 's/if (guardrailIdentifier.isNotEmpty())/if (this@BedrockPipe.guardrailIdentifier.isNotEmpty())/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
