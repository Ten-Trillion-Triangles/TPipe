#!/bin/bash
awk '
/metadata = mapOf\(/ {
    if ($0 ~ /action/ && $0 ~ /outputCount/) {
        print $0
        next
    }
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > tmp.kt
# Actually let's just use sed to fix the specific lines

# Fix 1: applyGuardrailStandalone metadata map type mismatch
# Line 570 and 580: mapOf(...) mixing types makes it Map<String, Comparable<*>? & Serializable?>
sed -i 's/"outputCount" to response.outputs?.size,/"outputCount" to (response.outputs?.size ?: 0) as Any,/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
sed -i 's/"hasAssessments" to (response.assessments?.isNotEmpty() ?: false)/"hasAssessments" to (response.assessments?.isNotEmpty() ?: false) as Any/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

# Fix 2: this@BedrockPipe.guardrailIdentifier.isNotEmpty() - guardrailIdentifier is not nullable, so no ? is needed, but the error says "nullable receiver of type String?". Wait, where?
# Line 1098: if (this@BedrockPipe.guardrailIdentifier.isNotEmpty()) -> Wait, the property is just guardrailIdentifier which is String.
# Ah, maybe I injected "if (guardrailIdentifier.isNotEmpty())" but the property is private var guardrailIdentifier?
