#!/bin/bash
# First, add the helper extension function at the bottom of the Converse Request section or at the top of the builders.
# Let's insert it before `fun buildGptOssConverseRequest`

awk '
/fun buildGptOssConverseRequest/ && !inserted {
    print "    /**"
    print "     * Applies guardrail configuration to ConverseRequest builder."
    print "     */"
    print "    private fun aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest.Builder.applyGuardrailConfig() {"
    print "        if (this@BedrockPipe.guardrailIdentifier.isNotEmpty()) {"
    print "            this.guardrailConfig = GuardrailConfiguration {"
    print "                this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier"
    print "                this.guardrailVersion = this@BedrockPipe.guardrailVersion"
    print "                this.trace = GuardrailTrace.fromValue(this@BedrockPipe.guardrailTrace)"
    print "            }"
    print "        }"
    print "    }"
    print ""
    inserted = 1
    print $0
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
