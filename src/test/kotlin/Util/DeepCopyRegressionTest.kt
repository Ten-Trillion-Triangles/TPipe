package com.TTT.Util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the EXISTING acyclic `deepCopy` contract — the safety net for the bounded rewrite. Every graph
 * here is a shallow tree (well under the new recursion cap) so that, post-fix, the visited-map and
 * depth-cap add no observable difference and the suite is green BOTH before and after the `Util.kt`
 * change. If any assertion flips green→red after the rewrite, the rewrite drifted acyclic semantics.
 *
 * These tests intentionally do NOT exercise cycles or shared-subtrees (DAGs) — that is
 * [DeepCopyCycleTest]'s job.
 */
class DeepCopyRegressionTest
{
    companion object
    {
        enum class Suit { Hearts, Diamonds }

        data class NullHolder(var v: Any? = null)

        data class Prim(val i: Int = 0, val d: Double = 0.0, val s: String = "", val b: Boolean = false, val c: Char = ' ', val l: Long = 0L)

        data class StrList(val items: MutableList<String> = mutableListOf())

        data class NodeList(val items: MutableList<Node> = mutableListOf())
        data class Node(val name: String, var payload: String = "")

        data class SetHolder(val items: MutableSet<String> = mutableSetOf())

        data class MapHolder(val data: MutableMap<String, Node> = mutableMapOf())

        data class Inner(var field: String)
        data class Middle(val inner: MutableList<Inner> = mutableListOf())
        data class Outer(val middle: Middle = Middle())

        data class DefMap(var m: MutableMap<String, String> = mutableMapOf())
    }

    @Test
    fun `null inside a data class stays null`()
    {
        val original = NullHolder("x")
        val nulled = NullHolder(null)

        val c1 = original.deepCopy()
        val c2 = nulled.deepCopy()

        assertEquals("x", c1.v)
        assertNull(c2.v, "a null field must remain null in the copy")
        assertNotSame(original, c1)
    }

    @Test
    fun `primitive and boxed fields are copied by value`()
    {
        val original = Prim(5, 3.14, "s", true, 'c', 7L)
        val copy = original.deepCopy()

        assertEquals(original, copy, "value equality must hold for a flat primitive record")
        assertNotSame(original, copy)
    }

    @Test
    fun `enum values are shared by reference`()
    {
        // Current deepCopyInternal has no explicit Enum branch — enums fall through to the reference
        // share (else branch), and enums are JVM singletons so sharing is correct. Post-fix an
        // explicit Enum branch keeps the same observable result. Pin the identity so a future change
        // that tries to "copy" the enum is caught.
        val original = Suit.Hearts
        val copy = original.deepCopy()

        assertSame(original, copy, "enum singletons must be shared by reference, not re-created")
    }

    @Test
    fun `a list of strings is a fresh instance with equal contents`()
    {
        val original = StrList(mutableListOf("a", "b", "c"))
        val copy = original.deepCopy()

        assertNotSame(original.items, copy.items, "the list itself must be a fresh instance")
        assertEquals(listOf("a", "b", "c"), copy.items)

        copy.items.add("MUTATED")
        assertEquals(3, original.items.size, "mutating the copy's list must not affect the original's")
    }

    @Test
    fun `a list of data-class elements deep-copies each element`()
    {
        val original = NodeList(mutableListOf(Node("n1", "p1"), Node("n2", "p2")))
        val copy = original.deepCopy()

        assertNotSame(original.items, copy.items)
        assertNotSame(original.items[0], copy.items[0], "each element must be a fresh copy")
        assertEquals("n1", copy.items[0].name)

        copy.items[0].payload = "MUTATED"
        assertEquals("p1", original.items[0].payload, "mutating a copied element must not affect the original")
    }

    @Test
    fun `a set is a fresh instance with equal contents`()
    {
        val original = SetHolder(mutableSetOf("x", "y", "z"))
        val copy = original.deepCopy()

        assertNotSame(original.items, copy.items, "the set must be a fresh instance")
        assertEquals(setOf("x", "y", "z"), copy.items)

        copy.items.add("MUTATED")
        assertEquals(3, original.items.size, "mutating the copy's set must not affect the original's")
    }

    @Test
    fun `a map keeps keys as-is and deep-copies values`()
    {
        val original = MapHolder(mutableMapOf("k1" to Node("n1"), "k2" to Node("n2")))
        val copy = original.deepCopy()

        assertNotSame(original.data, copy.data, "the map must be a fresh instance")
        // Keys (Strings) are immutable; contents match.
        assertEquals(setOf("k1", "k2"), copy.data.keys)
        // Values (data classes) are deep-copied.
        assertNotSame(original.data["k1"], copy.data["k1"], "data-class values must be fresh copies")
        assertEquals("n1", copy.data["k1"]!!.name)
    }

    @Test
    fun `nested composite copies independently down the tree`()
    {
        val original = Outer(Middle(mutableListOf(Inner("deep"))))
        val copy = original.deepCopy()

        assertNotSame(original, copy)
        assertNotSame(original.middle, copy.middle)
        assertNotSame(original.middle.inner, copy.middle.inner)
        assertNotSame(original.middle.inner[0], copy.middle.inner[0])
        assertEquals("deep", copy.middle.inner[0].field)

        copy.middle.inner[0].field = "MUTATED"
        assertEquals("deep", original.middle.inner[0].field, "mutation must not propagate up to the original")
    }

    @Test
    fun `mutable map defaults are not shared between original and copy`()
    {
        val original = DefMap(mutableMapOf())
        original.m["a"] = "1"
        val copy = original.deepCopy()

        // The copy's map is a fresh instance carrying the same entry, so writing a new key into the
        // copy must not leak into the original, and vice versa.
        assertNotSame(original.m, copy.m, "the copy got its own map instance")
        assertEquals("1", copy.m["a"], "the copied entry must survive in the copy's own map")

        copy.m["b"] = "2"
        assertFalse("b" in original.m, "writing the copy's map must not touch the original's map")

        copy.m["a"] = "9"
        assertEquals("1", original.m["a"], "overwriting a copied entry must not affect the original")
    }

    @Test
    fun `empty containers copy to empty instances`()
    {
        val original = StrList(mutableListOf())
        val copy = original.deepCopy()

        assertTrue(copy.items.isEmpty())
        assertNotSame(original.items, copy.items)
    }

    @Test
    fun `two separate deepCopy calls are independent of each other`()
    {
        val original = NodeList(mutableListOf(Node("n")))

        val a = original.deepCopy()
        val b = original.deepCopy()

        assertNotSame(a.items, b.items, "each call must produce its own fresh copy — no cross-call sharing")
        assertEquals("n", a.items[0].name)
        assertEquals("n", b.items[0].name)
    }

    @Test
    fun `a flat data-class list round-trips through deepCopy`()
    {
        // A second, independent-value regression pin for the plain (acyclic) copy path: contents and
        // values survive the copy. This must be green both pre- and post-fix.
        val outer = NodeList(mutableListOf(Node("leaf", "payload")))

        val copy = outer.deepCopy()
        assertNotNull(copy)
        assertNotSame(outer, copy)
        assertEquals("leaf", copy.items[0].name)
        assertEquals("payload", copy.items[0].payload)
    }
}
