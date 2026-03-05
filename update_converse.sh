#!/bin/bash
awk '
/serviceTier = ServiceTier \{ type = mapServiceTier\(\) \}/ {
    print $0
    print "            "
    print "            // Add guardrail configuration if set"
    print "            if (guardrailIdentifier.isNotEmpty()) {"
    print "                this.guardrailConfig = GuardrailConfiguration {"
    print "                    this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier"
    print "                    this.guardrailVersion = this@BedrockPipe.guardrailVersion"
    print "                    this.trace = GuardrailTrace.fromValue(this@BedrockPipe.guardrailTrace)"
    print "                }"
    print "            }"
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

# Need to fix the one in buildGptOssConverseRequest too which might not match exactly
awk '
/serviceTier = ServiceTier/ {
    print $0
    print "            "
    print "            // Add guardrail configuration if set"
    print "            if (guardrailIdentifier.isNotEmpty()) {"
    print "                this.guardrailConfig = GuardrailConfiguration {"
    print "                    this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier"
    print "                    this.guardrailVersion = this@BedrockPipe.guardrailVersion"
    print "                    this.trace = GuardrailTrace.fromValue(this@BedrockPipe.guardrailTrace)"
    print "                }"
    print "            }"
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

# Let's fix duplicate insertions by replacing multiple identical blocks if any were made. Wait, awk replace will replace all occurrences.
# Let's check how many times it was inserted.
