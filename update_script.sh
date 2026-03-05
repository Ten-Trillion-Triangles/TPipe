#!/bin/bash
sed -i 's/import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailStreamConfiguration/import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailStreamConfiguration\nimport aws.sdk.kotlin.services.bedrockruntime.model.GuardrailConfiguration\nimport aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailRequest\nimport aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailResponse\nimport aws.sdk.kotlin.services.bedrockruntime.model.GuardrailContentBlock\nimport aws.sdk.kotlin.services.bedrockruntime.model.GuardrailTextBlock\nimport aws.sdk.kotlin.services.bedrockruntime.model.GuardrailContentSource\nimport aws.sdk.kotlin.services.bedrockruntime.model.GuardrailTrace/g' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

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

awk '
/fun setServiceTier/ {
    print "    /**"
    print "     * Sets the guardrail to use for content filtering and safety policies."
    print "     * Guardrails evaluate both user inputs and model responses against configured policies"
    print "     * including content filters, denied topics, sensitive information filters, and word filters."
    print "     * "
    print "     * @param identifier The guardrail identifier (ID or ARN)"
    print "     * @param version The guardrail version to use (version number or \"DRAFT\")"
    print "     * @param enableTrace Enable guardrail tracing for debugging (default: false)"
    print "     * @return This BedrockPipe instance for method chaining"
    print "     * "
    print "     * @see clearGuardrail to remove guardrail configuration"
    print "     * @see applyGuardrailStandalone for standalone content evaluation"
    print "     * "
    print "     * @since Requires bedrock:ApplyGuardrail IAM permission"
    print "     */"
    print "    fun setGuardrail(identifier: String, version: String = \"DRAFT\", enableTrace: Boolean = false): BedrockPipe {"
    print "        this.guardrailIdentifier = identifier"
    print "        this.guardrailVersion = version"
    print "        this.guardrailTrace = if (enableTrace) \"enabled\" else \"disabled\""
    print "        return this"
    print "    }"
    print ""
    print "    /**"
    print "     * Enables full guardrail tracing which includes both detected and non-detected content"
    print "     * for enhanced debugging. Only applies to content filters, denied topics, sensitive"
    print "     * information PII detection, and contextual grounding policies."
    print "     * "
    print "     * @return This BedrockPipe instance for method chaining"
    print "     */"
    print "    fun enableFullGuardrailTrace(): BedrockPipe {"
    print "        this.guardrailTrace = \"enabled_full\""
    print "        return this"
    print "    }"
    print ""
    print "    /**"
    print "     * Clears the guardrail configuration, disabling content filtering."
    print "     * "
    print "     * @return This BedrockPipe instance for method chaining"
    print "     */"
    print "    fun clearGuardrail(): BedrockPipe {"
    print "        this.guardrailIdentifier = \"\""
    print "        this.guardrailVersion = \"\""
    print "        this.guardrailTrace = \"disabled\""
    print "        return this"
    print "    }"
    print ""
    print $0
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

awk '
/fun clearGuardrail/ {
    print $0
    in_func = 1
    next
}
in_func && /^    \}/ {
    print $0
    print ""
    print "    /**"
    print "     * Evaluates content against the configured guardrail without invoking foundation models."
    print "     * This allows independent content validation at any stage of your application flow."
    print "     * "
    print "     * @param content The text content to evaluate"
    print "     * @param source Whether content is from user input (\"INPUT\") or model output (\"OUTPUT\")"
    print "     * @param fullOutput Return full assessment including non-detected content for debugging"
    print "     * @return ApplyGuardrailResponse containing action taken and detailed assessments"
    print "     * "
    print "     * @throws IllegalStateException if guardrail is not configured"
    print "     * @throws IllegalArgumentException if client is not initialized"
    print "     * "
    print "     * @see setGuardrail to configure guardrail before calling this method"
    print "     * "
    print "     * @since Requires bedrock:ApplyGuardrail IAM permission"
    print "     */"
    print "    suspend fun applyGuardrailStandalone("
    print "        content: String,"
    print "        source: String = \"INPUT\","
    print "        fullOutput: Boolean = false"
    print "    ): ApplyGuardrailResponse? {"
    print "        // Validate guardrail configuration"
    print "        if (guardrailIdentifier.isEmpty()) {"
    print "            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,"
    print "                  metadata = mapOf(\"error\" to \"Guardrail not configured\"))"
    print "            throw IllegalStateException(\"Guardrail must be configured before calling applyGuardrailStandalone. Use setGuardrail() first.\")"
    print "        }"
    print "        "
    print "        // Validate client initialization"
    print "        val client = bedrockClient ?: run {"
    print "            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,"
    print "                  metadata = mapOf(\"error\" to \"Client not initialized\"))"
    print "            throw IllegalArgumentException(\"BedrockPipe must be initialized before applying guardrails. Call init() first.\")"
    print "        }"
    print "        "
    print "        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,"
    print "              metadata = mapOf("
    print "                  \"method\" to \"applyGuardrailStandalone\","
    print "                  \"guardrailId\" to guardrailIdentifier,"
    print "                  \"guardrailVersion\" to guardrailVersion,"
    print "                  \"source\" to source,"
    print "                  \"contentLength\" to content.length,"
    print "                  \"fullOutput\" to fullOutput"
    print "              ))"
    print "        "
    print "        return try {"
    print "            val request = ApplyGuardrailRequest {"
    print "                this.guardrailIdentifier = this@BedrockPipe.guardrailIdentifier"
    print "                this.guardrailVersion = this@BedrockPipe.guardrailVersion"
    print "                this.source = GuardrailContentSource.fromValue(source)"
    print "                this.content = listOf("
    print "                    GuardrailContentBlock.Text("
    print "                        GuardrailTextBlock {"
    print "                            this.text = content"
    print "                        }"
    print "                    )"
    print "                )"
    print "            }"
    print "            "
    print "            val response = client.applyGuardrail(request)"
    print "            "
    print "            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,"
    print "                  metadata = mapOf("
    print "                      \"action\" to response.action.toString(),"
    print "                      \"outputCount\" to response.outputs?.size,"
    print "                      \"hasAssessments\" to (response.assessments?.isNotEmpty() ?: false)"
    print "                  ))"
    print "            "
    print "            response"
    print "            "
    print "        } catch (e: Exception) {"
    print "            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,"
    print "                  metadata = mapOf(\"error\" to e.message), error = e)"
    print "            null"
    print "        }"
    print "    }"
    in_func = 0
    next
}
{ print }
' TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt > TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new
mv TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt.new TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt

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

# Apply ConverseRequest guardrail injection exactly once.

awk '
/serviceTier = ServiceTier \{ type = mapServiceTier\(\) \}/ {
    print $0
    print "            "
    print "            // Add guardrail configuration if set"
    print "            if (this@BedrockPipe.guardrailIdentifier.isNotEmpty()) {"
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
