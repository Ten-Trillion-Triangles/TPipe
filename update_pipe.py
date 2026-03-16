import re

with open('src/main/kotlin/Pipe/Pipe.kt', 'r') as f:
    content = f.read()

old_block = """        val reasoningBudget = budget.reasoningBudget ?: 0
        if(reasoningBudget > maxTokens) throw IllegalArgumentException("Reasoning tokens cannot be greater " +
                "than the overall max token budget for llm output.")

        /**
         * Subtract max token output to ensure we are keeping both model reasoning, and token output constrained
         * to the defined token budget.
         */
        maxTokensFromSettings -= reasoningBudget

        /**
         * Now after saving this back to the pipe we have our true max tokens which also ensure reasoning is accounted
         * for either being 0 for not being set, or being subtracted correctly from the max token value.
         */
        maxTokens = maxTokensFromSettings"""

new_block = """        val reasoningBudget = budget.reasoningBudget ?: 0
        if(reasoningBudget > maxTokens) throw IllegalArgumentException("Reasoning tokens cannot be greater " +
                "than the overall max token budget for llm output.")

        if(budget.subtractReasoningBudgetFromMaxTokens)
        {
            /**
             * Subtract max token output to ensure we are keeping both model reasoning, and token output constrained
             * to the defined token budget.
             */
            maxTokensFromSettings -= reasoningBudget

            /**
             * Now after saving this back to the pipe we have our true max tokens which also ensure reasoning is accounted
             * for either being 0 for not being set, or being subtracted correctly from the max token value.
             */
            maxTokens = maxTokensFromSettings
        }
        else
        {
            /**
             * Reasoning budget does not come out of max tokens, it comes out of the context window because it is
             * injected into the input of the main pipe as reasoning text.
             */
            workingTokenWindowSize -= reasoningBudget
            if(workingTokenWindowSize <= 0) throw Exception("Reasoning budget has overflowed the token budget.")
            maxTokens = maxTokensFromSettings
        }"""

if old_block in content:
    content = content.replace(old_block, new_block)
    with open('src/main/kotlin/Pipe/Pipe.kt', 'w') as f:
        f.write(content)
    print("Successfully replaced block.")
else:
    print("Could not find the exact block to replace.")
