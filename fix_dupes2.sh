#!/bin/bash
awk '
BEGIN { skip = 0 }
/\/\/ Add guardrail configuration if set/ {
    # Check if we are inside ConverseRequest builder
    skip = 1
    print "            applyGuardrailConfig()"
    next
}
skip && /this\.guardrailConfig = GuardrailConfiguration \{/ { next }
skip && /this\.guardrailIdentifier = this@BedrockPipe\.guardrailIdentifier/ { next }
skip && /this\.guardrailVersion = this@BedrockPipe\.guardrailVersion/ { next }
skip && /this\.trace = GuardrailTrace\.fromValue\(this@BedrockPipe\.guardrailTrace\)/ { next }
skip && /^\s*\}/ {
    # Match the closing brace of GuardrailConfiguration
    # Wait, there are two closing braces: one for GuardrailConfiguration, one for the if statement.
    # Let us just write a robust awk or perl script to replace the 8-line block.
}
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
