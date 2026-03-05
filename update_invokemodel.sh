#!/bin/bash
awk '
/val invokeRequest = InvokeModelRequest \{/ {
    print $0
    in_invoke = 1
    next
}
in_invoke && /serviceTier = mapServiceTier\(\)/ {
    print $0
    print "            "
    print "            // Add guardrail configuration if set"
    print "            if (guardrailIdentifier.isNotEmpty()) {"
    print "                this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier"
    print "                this.guardrailVersion = this@BedrockPipe.guardrailVersion"
    print "                this.trace = GuardrailTrace.fromValue(guardrailTrace)"
    print "            }"
    in_invoke = 0
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

awk '
/val request = InvokeModelWithResponseStreamRequest \{/ {
    print $0
    in_stream_invoke = 1
    next
}
in_stream_invoke && /accept = "application\/json"/ {
    print $0
    print "            "
    print "            // Add guardrail configuration if set"
    print "            if (guardrailIdentifier.isNotEmpty()) {"
    print "                this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier"
    print "                this.guardrailVersion = this@BedrockPipe.guardrailVersion"
    print "                this.trace = GuardrailTrace.fromValue(guardrailTrace)"
    print "            }"
    in_stream_invoke = 0
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
