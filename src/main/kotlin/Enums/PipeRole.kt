package com.TTT.Enums

/**
 * Declared role of a pipe within an agent pipeline. The pump station uses this
 * as one of the signals when figuring out which pipe is the "decision pipe"
 * (the one whose output is the actual decision and should be returned to the
 * caller).
 *
 * Priority order in the pump station's decision-pipe resolution:
 *   1. Manual override: [com.TTT.Pipeline.Pipeline.decisionPipeName]
 *   2. Pipe-level flag: [com.TTT.Pipe.Pipe.isDecisionPipe]
 *   3. Role tag: [PipeRole.Decision] (this enum)
 *   4. Heuristic scoring based on `pipeSettings.provider`, `jsonOutput`,
 *      `systemPrompt`, and pipe-name matching.
 *
 * `null` (no value set) is distinct from [Other]: `null` means "not configured
 * yet" at build time; [Other] means "I considered it, it's explicitly not a
 * decision role."
 */
enum class PipeRole
{
    /** This pipe produces the agent's actual decision. The pump station returns this pipe's output. */
    Decision,

    /** Pre-processes the input (chunking, reformatting, retrieval, etc.) before the decision pipe runs. */
    Preprocessor,

    /** Post-processes the decision pipe's output (validation, formatting, summarization, etc.). */
    Postprocessor,

    /** Loads context (lorebooks, memory, RAG) into the pipeline before the decision pipe runs. */
    ContextLoader,

    /** Validates the decision pipe's output (schema, safety, content checks). */
    Validator,

    /** Catch-all for pipes that don't fit one of the other roles. */
    Other
}
