# Semantic Compression

## Overview

Semantic compression is TPipe's legend-backed prompt reduction strategy for natural-language text. It is
designed to lower token usage before truncation while preserving the meaning of the prompt as much as possible.

The compressor is deterministic and safe to use for repeated prompts because it does not rely on an external
LLM or probabilistic summarizer. It:

- preserves quoted spans exactly as written
- normalizes compressible text to ASCII
- removes common function words and prompt filler phrases
- replaces repeated proper nouns with 2-character codes
- strips punctuation and syntactic noise except colons

The built-in stop-word, phrase, connector, and sentence-filler tables are loaded from resource files under
`src/main/resources/semantic-compression/`, so the lexicon can grow without turning the compressor into a
hardcoded constant block. Caller-supplied additions still merge on top of those base tables.

TPipe also keeps a small audit helper, `auditSemanticCompressionCorpus(...)`, so teams can surface recurring
residual phrases from their own prompt corpora and decide which choke points should be added to the checked-in
lexicon next.

## How It Works

### 1. Quote Preservation

Any text inside quotation marks is copied through unchanged. This allows examples, literal instructions, and
important quoted strings to survive compression without accidental rewriting.

### 2. ASCII Normalization

All compressible text is converted to ASCII so the compressed prompt stays portable and easy to tokenize.
This pass is applied only to text outside quotes.

### 3. Function Word Removal

Common English function words and filler phrases are removed from the compressible spans.
This is where most of the token savings come from for long natural-language prompts.

### 4. Proper Noun Legend

Repeated proper nouns are assigned deterministic 2-character codes. The compressor returns both the compressed
text and a legend so the LLM can reconstruct the original names if needed.

### 5. Punctuation Reduction

After the semantic cleanup, punctuation is reduced to the minimum useful surface form. Quotes and colons are
preserved because they are useful for legends and instructions.

## TPipe Integration

TPipe exposes semantic compression in two places:

- [`Pipe.compressPrompt(...)`](../api/pipe.md) for explicit opt-in compression of prompt text
- `TokenBudgetSettings.compressUserPrompt` for the existing user-prompt budget path
- `Pipe.enableSemanticCompression()` for fluent opt-in from the pipe API
- `Pipe.enableSemanticDecompression()` for reserving the future system-prompt decompression hook

When `compressUserPrompt` is enabled, TPipe tries semantic compression before truncation. If the prompt is
structured content such as JSON, XML, or code, TPipe leaves the existing budget and truncation logic in place.
The phrase and stop-word tables come from checked-in resource files and may be extended with the settings
object when a caller needs additional domain-specific coverage.

When `enableSemanticDecompression()` is also enabled, `applySystemPrompt()` prepends a short decompression
prelude at the very top of the rebuilt system prompt. That prelude tells the model that the user prompt was
compressed using TPipe Semantic Compression, explains that the compressed text is meant to be reconstructed
as closely as possible to the original intent and data, explains that the legend starts with `Legend:` and
contains `code: phrase` lines until the first blank line, and instructs the model to read the legend first,
expand the 2-character codes, restore omitted glue words and syntax as faithfully as possible, preserve quoted
spans verbatim, and then continue with the rest of the system instructions.

## Usage

```kotlin
val compression = pipe.compressPrompt("""
    Alice Johnson and Alice Johnson are going to review the launch proposal in order to help the team.
    "Quoted text stays untouched."
""".trimIndent())

val compressedPrompt = if(compression.legend.isNotEmpty())
{
    "${compression.legend}\n\n${compression.compressedText}"
}
else
{
    compression.compressedText
}
```

## Best Practices

- Use semantic compression for plain-language prompts that repeat names or verbose filler.
- Do not apply it to JSON, XML, code fences, or other machine-readable content.
- Keep quoted examples in quotes if they must survive compression exactly.
- Treat the legend as part of the final prompt so the LLM can expand short codes back into the original names.
