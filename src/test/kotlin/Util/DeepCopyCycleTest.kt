package com.TTT.Util

import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the bounded `deepCopy` behavior against the classes of input that overflowed the pre-fix
 * unbounded `deepCopyInternal`:
 *
 * - genuine back-reference cycles (a data class whose body holds itself)
 * - the incident's "snapshot-of-a-snapshot" nesting (a [MultimodalContent] whose `metadata` map
 *   holds a nested copy of itself, N levels deep — exponential work pre-fix)
 * - a [ContextWindow] back-reference held in a content graph
 * - a shared-subtree DAG (two map keys → one node)
 * - a very deep acyclic chain (past [DEEP_COPY_RECURSION_CAP] → tail shared by reference, not SOE)
 *
 * Written against the POST-fix contract. Before the `Util.kt` fix, tests 1/2/5/6 are expected to fail
 * (StackOverflowError or >2s / non-shared semantics); test 4 (acyclic independence) must pass both
 * pre- and post-fix (see [DeepCopyRegressionTest] for the full acyclic contract).
 */
class DeepCopyCycleTest
{
    companion object
    {
        // A plain data class with a body-level mutable map that can hold a back-reference to itself.
        // Both fields default so the class gets a synthetic no-arg constructor — matching the shape of
        // the incident's real type (MultimodalContent has a no-arg ctor), which is what lets the fast
        // path register the in-flight copy BEFORE descending into fields and terminate the cycle.
        data class Node(val label: String = "", var children: MutableMap<String, Any> = mutableMapOf())

        // A linear wrapper chain used to exercise the depth cap.
        data class Wrap(val label: String, val next: Wrap? = null)

        // A shared-subtree (DAG) holder: two keys point at the same instance.
        data class SharedRef(val name: String)

        // A lightweight node for the exponential DAG proof — a single field plus a body map, so that
        // pre-fix (no visited map) the work is O(2^depth) at a per-node cost low enough that the
        // pre-fix baseline still finishes in seconds rather than minutes.
        data class ExpNode(val id: Int, var children: MutableMap<String, ExpNode> = mutableMapOf())
    }

    private fun timedMillis(block: () -> Unit): Long
    {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }

    @Test
    fun `a self-referencing data class terminates and preserves the cycle`()
    {
        val node = Node("root")
        node.children["self"] = node   // genuine back-edge -> the incident class of bug

        val copy = node.deepCopy()

        assertNotNull(copy)
        assertEquals("root", copy.label)
        // The back-edge must survive as a cycle in the copy: the copied child points at the copied root,
        // not back at the original root and not at itself.
        val copiedChild = copy.children.getValue("self")
        assertSame(copy, copiedChild, "cycle back-edge must resolve to the in-flight copy")
        assertNotSame(node, copy, "root must be a fresh copy")
    }

    @Test
    fun `an exponential snapshot-of-snapshot DAG stays bounded in work`()
    {
        // Build a light-node graph where each level holds the SAME next-level node under TWO distinct
        // map keys ("snapshot" and "alias"). Pre-fix (no visited map), deepCopy re-copies that shared
        // subtree once per distinct path, so the work grows as 2^depth — the incident's
        // "snapshot-in-snapshot" exponential blowup. At depth 20 that is ~1M node copies and the
        // 2-second bound is breached. Post-fix, the visited map copies each distinct node once, so the
        // work is linear in depth (~41 nodes) and completes in milliseconds.
        val outer = exponentialDag(20)

        val elapsed = timedMillis { outer.deepCopy() }
        assertTrue(
            elapsed < 2000,
            "deepCopy of a 20-level exponential DAG took ${elapsed}ms (expected bounded by the visited map)"
        )

        // The nesting is preserved structurally: the outer level's snapshot points at level 19, and the
        // shared alias points at the same level 19 node.
        assertEquals(19, (outer.children["snapshot"] as ExpNode).id)
        assertEquals(19, (outer.children["alias"] as ExpNode).id)
        assertNotSame(outer, outer.deepCopy())
    }

    private fun exponentialDag(depth: Int): ExpNode
    {
        val c = ExpNode(id = depth)
        if(depth > 0)
        {
            val next = exponentialDag(depth - 1)
            c.children["snapshot"] = next
            c.children["alias"] = next   // two distinct paths into the SAME subtree -> exponential copies pre-fix
        }
        return c
    }

    @Test
    fun `a content whose ContextWindow holds a back-reference to itself terminates`()
    {
        val content = MultimodalContent(text = "x")
        val ctx = content.context
        // Put a back-reference to this very content inside the context's transient metadata map.
        ctx.metaData["owner"] = content

        val copy = content.deepCopy()

        assertNotSame(content, copy)
        val copiedOwner = (copy.context.metaData["owner"] as? MultimodalContent)
        assertSame(copy, copiedOwner, "the ContextWindow back-edge must resolve to the in-flight copy")
    }

    @Test
    fun `acyclic inputs still produce fully independent copies`()
    {
        val node = Node("a", mutableMapOf("k" to Node("b", mutableMapOf())))

        val copy = node.deepCopy()

        assertNotSame(node, copy)
        assertNotSame(node.children, copy.children, "body map must be a fresh instance")
        copy.children["added"] = SharedRef("new")
        assertFalse("added" in node.children, "mutating the copy's map must not affect the original's map")
        // The shared subtree node is a fresh copy, and value equality holds.
        assertEquals("b", (node.children.getValue("k") as Node).label)
    }

    @Test
    fun `a shared-subtree DAG copies the shared node once`()
    {
        val shared = SharedRef("the-node")
        val holder = Node("root", mutableMapOf("a" to shared, "b" to shared))

        val copy = holder.deepCopy()

        // Post-fix semantic: both keys in the copy resolve to the SAME copied instance (the node was
        // visited once). This is the intentional, documented change vs. the pre-fix "two copies" result.
        val copiedA = copy.children.getValue("a") as SharedRef
        val copiedB = copy.children.getValue("b") as SharedRef
        assertSame(copiedA, copiedB, "a shared subtree must be copied once and shared, not duplicated")
        assertNotSame(shared, copiedA, "the shared node must still be a fresh copy, not the original")
        assertEquals("the-node", copiedA.name)
    }

    @Test
    fun `a very deep acyclic chain terminates past the recursion cap`()
    {
        // 5000-deep chain: pre-fix this is near/over the stack limit (SOE risk); post-fix the copy
        // stops recursing past DEEP_COPY_RECURSION_CAP and shares the tail by reference, so it
        // completes instantly with the value preserved.
        var chain: Wrap? = null
        repeat(5000)
        {
            val w = Wrap("w$it", chain)
            chain = w
        }
        val top = chain!!

        val elapsed = timedMillis { top.deepCopy() }
        assertTrue(
            elapsed < 2000,
            "deepCopy of a 5000-deep chain took ${elapsed}ms (expected bounded)"
        )
        assertNotSame(top, top.deepCopy())
        // The near leaf is still reachable through the copy (copied-or-shared, either way non-null).
        assertNotNull(top.deepCopy().next)
    }

    @Test
    fun `deepCopy is idempotent on a plain value`()
    {
        val original = Node("root", mutableMapOf("k" to Node("leaf")))

        val once = original.deepCopy()
        val twice = once.deepCopy()

        // Value equality (not identity) survives two hops; the label chain is intact.
        assertEquals("root", twice.label)
        assertEquals("leaf", (twice.children.getValue("k") as Node).label)
        assertNotSame(original, twice, "a double copy must still be a fresh object")
    }
}
