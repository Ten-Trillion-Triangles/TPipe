#!/bin/bash
# Revert Trace.fromValue back to GuardrailTrace.fromValue for the ones that need it
# Then only change the InvokeModelRequest ones!
# Ah, the compiler errors say: "Assignment type mismatch: actual type is 'Trace', but 'GuardrailTrace' was expected."
# This means GuardrailConfiguration.trace expects GuardrailTrace enum, but it got Trace enum.
# So `GuardrailConfiguration.trace` needs `GuardrailTrace.fromValue`.
# But `InvokeModelRequest.trace` needs `Trace.fromValue`.
# How many GuardrailConfiguration blocks are there? The Converse API blocks.
# Let's see lines 1871, etc.
sed -i 's/aws.sdk.kotlin.services.bedrockruntime.model.Trace.fromValue/GuardrailTrace.fromValue/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

# Let's fix only InvokeModelRequest and InvokeModelWithResponseStreamRequest
# I'll use awk to replace GuardrailTrace.fromValue with Trace.fromValue ONLY when inside invokeRequest / request for InvokeModel
awk '
/val invokeRequest = InvokeModelRequest \{/ {
    in_invoke = 1
}
/val request = InvokeModelWithResponseStreamRequest \{/ {
    in_invoke = 1
}
in_invoke && /GuardrailTrace\.fromValue/ {
    sub(/GuardrailTrace\.fromValue/, "aws.sdk.kotlin.services.bedrockruntime.model.Trace.fromValue")
}
in_invoke && /^\s*\}/ {
    in_invoke = 0
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
