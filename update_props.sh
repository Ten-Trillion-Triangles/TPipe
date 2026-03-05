#!/bin/bash
awk '
/private var serviceTier/ {
    print $0
    print "    "
    print "    /**"
    print "     * Guardrail identifier for content filtering and safety policies."
    print "     * Can be guardrail ID or ARN."
    print "     */"
    print "    @kotlinx.serialization.Serializable"
    print "    private var guardrailIdentifier: String = \"\""
    print ""
    print "    /**"
    print "     * Version of the guardrail to use. Can be version number or \"DRAFT\"."
    print "     */"
    print "    @kotlinx.serialization.Serializable"
    print "    private var guardrailVersion: String = \"\""
    print ""
    print "    /**"
    print "     * Guardrail trace setting for debugging guardrail decisions."
    print "     * Values: \"enabled\", \"disabled\", \"enabled_full\""
    print "     */"
    print "    @kotlinx.serialization.Serializable"
    print "    private var guardrailTrace: String = \"disabled\""
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
