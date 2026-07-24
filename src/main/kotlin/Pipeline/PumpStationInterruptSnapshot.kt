package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Pipe.MultimodalContent

/**
 * Snapshot of the harness state at BeforeJudge of a turn. Captured at the
 * top of every [runTurn] and used by [PumpStationInterruptException] to
 * rewind the harness before re-entering the turn from BeforeJudge.
 *
 * Captured fields:
 *   - [turnIndex] — taskState.turnIndex at BeforeJudge (NOT advanced on
 *     interrupt; the rewind restores this value so the interrupted turn
 *     counts as the same turn slot)
 *   - [latestContent] — taskState.latestContent at BeforeJudge (rewind
 *     restores; the in-flight turn's path output is discarded)
 *   - [lastPathResult] — taskState.lastPathResult at BeforeJudge (rewind
 *     restores; previous turn's path result is preserved)
 *   - [selectedPathName] — taskState.selectedPathName at BeforeJudge
 *   - [originalInput] — taskState.originalInput at BeforeJudge
 *   - [turnHistoryCopy] — deep copy of turnHistory.history at BeforeJudge.
 *     Always copied on construction so subsequent in-flight turns mutating
 *     turnHistory do not bleed into the snapshot.
 *
 * Not captured (intentionally — these don't change during a single turn):
 *   - rawTurnHistory — the full event log; preserving in-flight events is
 *     the entire point of the raw channel
 *   - contextWindow, miniBank — unchanged by a turn's in-flight work
 *   - visiblePathNames, reservePathNames — unchanged
 */
class PumpStationInterruptSnapshot(
    val turnIndex: Int,
    val latestContent: MultimodalContent?,
    val lastPathResult: MultimodalContent?,
    val selectedPathName: String?,
    val originalInput: MultimodalContent?,
    turnHistory: List<ConverseData>
)
{
    val turnHistoryCopy: List<ConverseData> = turnHistory.toList()
}
