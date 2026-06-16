package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.LoreBook
import kotlinx.serialization.Serializable

/**
 * Typed envelope contract for the lorebook agent in PumpStation v3.
 *
 * Replaces the previous free-form JSON contract with explicit input/output data classes so
 * the harness can:
 * - pass a deterministic slice of `turnHistory` (the [LorebookAgentInput.turnsSinceLastUpdate]
 *   list, computed from `lorebookCursor.lastUpdatedTurnIndex`) to the agent;
 * - verify the agent's output is fresh (the [LorebookAgentOutput.compactedThroughTurn] must
 *   be greater than `lorebookCursor.lastUpdatedTurnIndex`) before applying;
 * - distinguish merge vs replace on the [LorebookUpdate.operation] field;
 * - apply deletions explicitly via the [LorebookAgentOutput.deletions] list.
 *
 * The legacy free-form JSON contract is preserved as a fallback: if the agent's response does
 * not parse as a [LorebookAgentOutput], the existing `applyLorebookUpdates(MultimodalContent)`
 * JSON path runs unchanged. Both paths are exercised by tests.
 *
 * The v3 contract is opt-in: agents that want to use it return a JSON object that matches the
 * [LorebookAgentOutput] shape; agents that don't (or haven't been migrated) keep working
 * through the legacy path.
 */

//=========================================LorebookAgentInput=========================================================

/**
 * Input to the lorebook agent. Built by `updateLorebook()` from the current harness state
 * and serialized to JSON before being wrapped in a [com.TTT.Pipe.MultimodalContent] for the
 * agent's `executeLocal` call.
 *
 * @property turnsSinceLastUpdate The slice of `turnHistory.history` whose `turnIndex` is
 *   greater than `lorebookCursor.lastUpdatedTurnIndex`. The pre-prune step is applied before
 *   this list is built, so blank turns, stash placeholders, etc. are already removed.
 * @property lastLorebookUpdateTurnIndex The cursor value at the time the input was built.
 *   Mirrored into the agent's output as the floor for `compactedThroughTurn`.
 * @property currentLorebook All current lorebook entries as a list, so the agent can see
 *   what already exists and decide whether to merge or replace.
 * @property taskContext The harness's static task framing — personality, system task, user
 *   guidelines, entry user prompt. Lets the agent prioritize what to capture.
 * @property harnessGeneration The [CompactionCursor.generation] at the time the input was
 *   built. The agent can include this in its output (or use it as a freshness hint).
 */
@Serializable
data class LorebookAgentInput(
    val turnsSinceLastUpdate: List<ConverseData>,
    val lastLorebookUpdateTurnIndex: Int,
    val currentLorebook: List<LoreBook>,
    val taskContext: LorebookTaskContext,
    val harnessGeneration: Long
)

/**
 * Static task framing passed to the lorebook agent. Mirrors the existing
 * `PumpStation.personality` / `systemTask` / `userGuidelines` / `entryUserPrompt` fields so
 * the agent can prioritize what to capture.
 */
@Serializable
data class LorebookTaskContext(
    val task: String,
    val persona: String,
    val systemTask: String,
    val userGuidelines: String
)

//=========================================LorebookAgentOutput========================================================

/**
 * Output from the lorebook agent. Parsed from the agent's response before being applied.
 *
 * @property updates List of lorebook updates. Each [LorebookUpdate] specifies the key, value,
 *   weight, and any linked/alias/required keys, plus the merge-vs-replace [LorebookOperation].
 *   Empty list means "no changes to existing entries".
 * @property deletions List of lorebook keys to remove from the [ContextWindow.loreBookKeys]
 *   map. Useful for entries that have become obsolete (e.g. a character that left the
 *   conversation). Default empty for backward compat.
 * @property compactedThroughTurn The highest turn index the agent processed. The harness
 *   discards the output if this is `<= lorebookCursor.lastUpdatedTurnIndex` — meaning the
 *   agent's work has been subsumed by a later lorebook update.
 */
@Serializable
data class LorebookAgentOutput(
    val updates: List<LorebookUpdate>,
    val deletions: List<String> = emptyList(),
    val compactedThroughTurn: Int
)

/**
 * One update entry in a [LorebookAgentOutput]. Fields mirror the existing [LoreBook] class so
 * the harness can apply the update via the same [LoreBook.combineValue] path the legacy
 * free-form JSON uses.
 *
 * @property key The lorebook key. Must be non-empty; the harness drops empty keys.
 * @property value The lorebook value. Merged with the existing entry on [LorebookOperation.Merge]
 *   or used as-is on [LorebookOperation.Replace].
 * @property weight The entry weight. Higher weight gives higher priority during lorebook
 *   selection when token budget is constrained.
 * @property linkedKeys Optional cross-reference keys. Preserved on the entry.
 * @property aliasKeys Optional alternative keys that also match the entry. Preserved on the entry.
 * @property requiredKeys Optional keys that must be present in context for this entry to be
 *   included. Preserved on the entry.
 * @property operation [LorebookOperation.Merge] combines the new value with the existing
 *   entry's value via [LoreBook.combineValue] (default for backward compat).
 *   [LorebookOperation.Replace] overwrites the existing entry wholesale.
 */
@Serializable
data class LorebookUpdate(
    val key: String,
    val value: String,
    val weight: Int = 0,
    val linkedKeys: List<String> = emptyList(),
    val aliasKeys: List<String> = emptyList(),
    val requiredKeys: List<String> = emptyList(),
    val operation: LorebookOperation = LorebookOperation.Merge
)

/**
 * Whether a [LorebookUpdate] merges into the existing lorebook entry or replaces it.
 */
@Serializable
enum class LorebookOperation
{
    /** Combine the new value with the existing entry's value via [LoreBook.combineValue]. */
    Merge,

    /** Overwrite the existing entry wholesale. Existing linked/alias/required keys are lost. */
    Replace
}
