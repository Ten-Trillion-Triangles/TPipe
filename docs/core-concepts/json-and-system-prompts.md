# JSON Schema and System Prompts

In a professional software ecosystem, you cannot rely on loose, unstructured text. You need high-precision **Specifications**. TPipe uses **JSON Schemas** to transform the non-deterministic output of an AI model into reliable, type-safe data that your application can actually use.

By combining structured output with a professional **System Prompt**, you turn a generic model into a robust software component.

## Table of Contents
- [Why Structure is Mandatory for Production](#why-structure-is-mandatory-for-production)
- [1. Defining the Blueprint (JSON Schema)](#1-defining-the-blueprint-json-schema)
- [2. The System Prompt: The Operational Manual](#2-the-system-prompt-the-operational-manual)
- [3. The Flow: Assembling the Compound Prompt](#3-the-flow-assembling-the-compound-prompt)
- [4. Patching the Leaks: JSON Repair](#4-patching-the-leaks-json-repair)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Why Structure is Mandatory for Production

Raw text prompts are for human chat. JSON Schemas are for industrial automation. They provide:
*   **Determinism**: You define exactly which fields must exist in a response.
*   **Type Safety**: You ensure that a pressure reading is an integer, not a string or a vague description.
*   **Automated Validation**: TPipe can automatically verify if the model's output matches your blueprint before it ever reaches your business logic.

---

## 1. Defining the Blueprint (JSON Schema)

You can attach a JSON schema to any Pipe. TPipe automatically updates the system instructions to force the model into this specific format.

```kotlin
val schema = """
{
  "type": "object",
  "properties": {
    "valve_id": { "type": "string" },
    "status": { "type": "string", "enum": ["open", "closed", "leaking"] },
    "pressure_psi": { "type": "number" }
  },
  "required": ["valve_id", "status"]
}
"""

val auditor = BedrockPipe()
    .setJsonSchema(schema)
    .setSystemPrompt("Inspect the sensor logs.")
```

> [!TIP]
> **Kotlin Integration**: You can use `kotlinx.serialization` to define your schema as a standard Kotlin data class, ensuring your agent's output and your application's data models are perfectly synchronized.

---

## 2. The System Prompt: The Operational Manual

If the JSON schema is the **Blueprint**, the system prompt is the **Manual**. It establishes the persona, context, and logic the model must apply.

### Professional Prompt Construction
A high-quality industrial prompt should include:
1.  **Identity**: "You are a lead hydraulic engineer."
2.  **Scope**: "Your task is to identify pressure drops in the provided stream."
3.  **Constraints**: "Only report anomalies above 50 PSI. Be concise. Do not include introductory text."
4.  **Format Rule**: "Your final output must be a single JSON object."

```kotlin
pipe.setSystemPrompt("""
    # Role
    Professional Data Inspector

    # Context
    You are monitoring an industrial water mainline.

    # Task
    Identify any sensor readings that are outside of the normal range (100-200 PSI).

    # Formatting
    Your output MUST be a single JSON object matching the provided schema.
""".trimIndent())
```

---

## 3. The Flow: Assembling the Compound Prompt

When you call `execute()`, TPipe does not just send your raw prompt. it assembles a sophisticated **Compound Specification** in this order:

1.  **Core Instructions**: Your defined `systemPrompt`.
2.  **PCP Tools**: "You have access to the following tools: [PCP Definitions]" (if enabled).
3.  **Structural Specs**: "You must respond in JSON. Your blueprint is: [Schema]" (via `jsonOutput`).
4.  **Middle Prompt**: Instructions injected between input and output schemas (via `setMiddlePrompt`).
5.  **Background Knowledge**: Any LoreBook entries or history that have been injected.
6.  **Footer Prompt**: Final absolute constraints (via `setFooterPrompt`).

---

## 4. Patching the Leaks: JSON Repair

Because models can occasionally make minor formatting errors (like trailing commas or missing brackets), TPipe includes a **JSON Repair Engine**.

**Repair Strategies:**
- **Boundary Detection**: Uses bracket matching to find the JSON "Cargo" even if the model included extra conversational text.
- **Structural Patching**: Fixes unquoted keys, trailing commas, and escaped characters.
- **Auto-Closure**: Force-closes any brackets that the model left "Open" due to token limit truncation.

If the output is truly unparseable, TPipe raises a `PipelineException`, allowing you to handle the failure gracefully using the **Error Handling** system.

---

## Best Practices

*   **Be Explicit**: Tell the model *why* it is using the schema. (e.g., "Use this schema to report the final status of the audit.")
*   **Use Enums**: Limit the model's choices for status fields to prevent "Creative" but invalid labels.
*   **Zero Temperature**: When requiring structured output, always set `temperature` to **0.0** to reduce the risk of formatting deviations.
*   **Verify with DITL**: For mission-critical data, use a **Developer-in-the-Loop Function** to perform an additional check on the parsed JSON fields.
*   **Native JSON**: For models that support it (like Claude 3.5), TPipe will attempt to use the provider's native JSON mode for maximum reliability.

---

## Next Steps

Now that you can specify the exact format of your data, learn how to manage the total volume of that data to prevent system overflows.

**→ [Context and Tokens - Token Management](context-and-tokens.md)** - Managing token usage and limits.
