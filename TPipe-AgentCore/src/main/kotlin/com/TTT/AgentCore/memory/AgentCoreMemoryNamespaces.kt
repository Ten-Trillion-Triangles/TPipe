package com.TTT.AgentCore.memory

/**
 * A validated namespace template for AgentCore semantic-memory strategies.
 *
 * Exact ContextBank persistence keeps its existing `/tpipe/<instance>/...`
 * namespace and is not represented by this class.
 *
 * @param template Template containing `{variable}` placeholders.
 * @param variables Values used to expand placeholders.
 */
data class AgentCoreMemoryNamespaceTemplate(
    val template: String,
    val variables: Map<String, String> = emptyMap()
)
{
    init
    {
        require(template.isNotBlank()) { "A semantic-memory namespace template is required." }
        require(variables.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) {
            "Namespace variable names must start with a letter."
        }
    }

    /**
     * Expand this template using configured values and optional per-call values.
     *
     * Per-call values take precedence, which supports one strategy template
     * being reused for multiple actors, tenants, or sessions.
     *
     * @param overrides Values that replace configured values for this expansion.
     * @return Expanded semantic-memory namespace.
     */
    fun expand(overrides: Map<String, String> = emptyMap()): String
    {
        val values = variables + overrides
        val names = VARIABLE_PATTERN.findAll(template).map { it.groupValues[1] }.toSet()
        require(names.all { values[it]?.isNullOrBlank() == false }) {
            "Every namespace template variable must have a non-blank value."
        }
        require(overrides.keys.all { it.matches(VARIABLE_NAME_PATTERN) }) {
            "Namespace variable names must start with a letter."
        }
        return VARIABLE_PATTERN.replace(template) { values.getValue(it.groupValues[1]) }
    }

    private companion object
    {
        val VARIABLE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]*")
        val VARIABLE_PATTERN = Regex("\\{([A-Za-z][A-Za-z0-9_-]*)}")
    }
}

/** Canonical semantic namespace strategy configuration. */
data class AgentCoreMemoryNamespaceConfiguration(
    val strategies: Map<String, AgentCoreMemoryNamespaceTemplate>
)
{
    init
    {
        require(strategies.isNotEmpty()) { "At least one semantic-memory strategy is required." }
        require(strategies.keys.all { it.isNotBlank() }) { "Strategy names must not be blank." }
    }

    /**
     * Expand the named strategy namespace with optional per-call values.
     *
     * @param strategyName Semantic-memory strategy name.
     * @param variables Values that replace the template defaults for this expansion.
     * @return Expanded semantic-memory namespace.
     */
    fun expand(strategyName: String, variables: Map<String, String> = emptyMap()): String =
        strategies.getValue(strategyName).expand(variables)
}
