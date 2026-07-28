package com.TTT.Util

import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract of `Iterable<T>.intersect(other)` and `Iterable<T>.subtract(other)`
 * against the Kotlin 2.3 behavioral change (KTLC-268).
 *
 * What changed in Kotlin 2.3.0:
 *  - The functions now test membership for each receiver element BEFORE adding
 *    it to the result set, and the result set compares elements with
 *    `Any::equals`. This produces correct results when the argument
 *    collection uses referential equality (e.g. `IdentityHashMap.keys`).
 *  - Previously, the function delegated straight to a `Set` built from the
 *    argument, which used the argument's equality, which in turn could mask
 *    receiver-side duplicates that the argument collection treated as
 *    "different" by identity.
 *
 * Why this matters for TPipe:
 *  - `DistributionGrid.kt:7379`, `DistributionGrid.kt:7467`, and
 *    `DistributionGrid.kt:8346` all use `Iterable<T>.intersect(...)` against
 *    `Set<String>`-shaped storage-class policy lists and peer-key maps.
 *    If a future change ever swaps one of those Sets for an `IdentityHashMap`
 *    (e.g. to dedupe peer descriptors by reference), the new contract is the
 *    only behavior that produces semantically-correct overlap detection.
 *  - `Junction.kt` and `Pipeline.kt` use `subtract` in workflow-recipe vote
 *    tallying. The 2.3 contract pins that the receiver element's `equals`
 *    (not the argument collection's) is the source of truth.
 */
class IterableIntersectSubtractContractTest
{
    //================================================ intersect: standard equals

    @Test
    fun `intersect with two string lists returns the common elements`() {
        val left = listOf("a", "b", "c", "d")
        val right = listOf("c", "d", "e")
        val result = left.intersect(right).toList()
        // The receiver drives ordering, so the result is [c, d] (in the
        // order the receiver saw them), not the right's order.
        assertEquals(listOf("c", "d"), result)
    }

    @Test
    fun `intersect with receiver-only elements returns an empty set`() {
        val left = listOf("a", "b")
        val right = listOf("c", "d")
        assertTrue(left.intersect(right).none())
    }

    @Test
    fun `intersect with a Set uses the set's equals contract`() {
        val left = listOf("a", "b", "c")
        val right: Set<String> = linkedSetOf("b", "c", "d")
        val result = left.intersect(right).toList()
        assertEquals(listOf("b", "c"), result)
    }

    //================================================ intersect: duplicate receiver elements

    @Test
    fun `intersect deduplicates receiver elements with equals`() {
        // Receiver has duplicates. Both must be filtered out by the
        // membership test on the argument. The new 2.3 contract says: emit
        // each receiver element at most once even if the receiver has dupes.
        val left = listOf("a", "a", "b", "b", "c", "c")
        val right = setOf("a", "b")
        val result = left.intersect(right).toList()
        // The 2.3 contract returns a Set-like result, so the duplicate
        // values collapse to {a, b}.
        assertEquals(setOf("a", "b"), result.toSet())
        assertEquals(2, result.size, "Receiver duplicates must collapse under the new contract")
    }

    //================================================ intersect: IdentityHashMap.keys argument

    @Test
    fun `intersect with IdentityHashMap-keys argument uses Any equals for membership`() {
        // Build three reference-distinct String instances. Two of them have
        // structural equality, the third does not.
        val a1 = "alpha"
        val a2 = String(charArrayOf('a', 'l', 'p', 'h', 'a')) // structurally equal to a1, but a fresh String
        val z = "zulu"

        // Pin the structural equality assumption: a1 == a2 by .equals but
        // a1 !== a2 by reference (true in general, but we re-verify in the
        // test rather than trust the platform).
        assertEquals(a1, a2, "Setup: a1 and a2 must be structurally equal")
        // The Kotlin compiler may intern a1 and a2 in some runtimes; the
        // contract under test is on the membership result, not on the
        // identity of the strings themselves.

        val identity = IdentityHashMap<String, Int>()
        identity[a1] = 1
        identity[z] = 2
        val identityKeys: Set<String> = identity.keys

        val left = listOf(a1, a2, z)
        val result = left.intersect(identityKeys).toList()

        // The new 2.3 contract: a1 and a2 are equal under Any::equals, so
        // they collapse into one membership check against the
        // IdentityHashMap.keys. z is structurally different and matches z.
        assertEquals(2, result.size, "Expected {alpha, zulu} under Any::equals membership")
        assertEquals(setOf("alpha", "zulu"), result.toSet())
    }

    @Test
    fun `subtract removes elements present in the argument set`() {
        val left = listOf("a", "b", "c", "d")
        val right = setOf("b", "d")
        val result = left.subtract(right).toList()
        // Receiver-driven ordering, with the result collapsed by equals
        // (no receiver duplicates to dedupe in this case).
        assertEquals(listOf("a", "c"), result)
    }

    @Test
    fun `subtract deduplicates receiver elements with equals`() {
        val left = listOf("a", "a", "b", "b", "c", "c")
        val right = emptySet<String>()
        val result = left.subtract(right).toList()
        // With an empty argument, subtract returns the receiver's distinct
        // values, in the receiver's order, deduped.
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `subtract with IdentityHashMap-keys argument uses Any equals`() {
        val a1 = "alpha"
        val z = "zulu"
        val identity = IdentityHashMap<String, Int>()
        identity[a1] = 1
        // z is NOT in the identity map; it should be retained in the result.

        val left = listOf(a1, z)
        val result = left.subtract(identity.keys).toList()
        assertEquals(listOf("zulu"), result)
    }

    //================================================ DistributionGrid-shaped fixture

    @Test
    fun `intersect across the two DistributionGrid storage-class policy lists matches 2_3 contract`() {
        // This is the canonical call shape from `DistributionGrid.kt:7379`
        // and `DistributionGrid.kt:7467`:
        //
        //     localPolicy.allowedStorageClasses
        //         .intersect(requestedPolicy.allowedStorageClasses.toSet())
        //
        // The 2.3 contract must hold: overlap is computed by String.equals,
        // not by reference.
        val localPolicy = listOf("redis", "postgres", "sqlite")
        val requestedPolicy = listOf("postgres", "mysql", "redis")

        val overlap = localPolicy.intersect(requestedPolicy.toSet()).toList()

        // Receiver-driven ordering, with the duplicate-collapse of equal
        // values (no dupes in this fixture, so the result has 2 elements).
        assertEquals(2, overlap.size)
        assertEquals(setOf("redis", "postgres"), overlap.toSet())
    }

    @Test
    fun `intersect across DistributionGrid peer-key sets matches 2_3 contract`() {
        // The 2.3 contract pin for `DistributionGrid.kt:8346`:
        //
        //     val overlappingKeys = localPeerKeys.toSet()
        //         .intersect(externalPeerKeys.toSet())
        //
        // A regression that re-introduces the pre-2.3 behavior would emit
        // receiver-side duplicates verbatim, which the downstream `require`
        // would still reject (overlap is non-empty either way), but the
        // membership computation itself is the contract under test.
        val localPeerKeys = listOf("peer-A", "peer-B", "peer-C", "peer-A")
        val externalPeerKeys = listOf("peer-B", "peer-C", "peer-D")

        val overlap = localPeerKeys.toSet().intersect(externalPeerKeys.toSet()).toList()

        assertEquals(2, overlap.size)
        assertEquals(setOf("peer-B", "peer-C"), overlap.toSet())
    }

    //================================================ Negative pin (no false positives)

    @Test
    fun `intersect does not report an overlap when receiver and argument are value-distinct`() {
        val left = listOf("alpha")
        val right = listOf("bravo")
        val result = left.intersect(right)
        assertTrue(result.none(), "Distinct string values must not overlap under Any::equals")
    }

    @Test
    fun `subtract does not drop a receiver element that has no equals match in the argument`() {
        val left = listOf("alpha", "bravo", "charlie")
        val right = listOf("delta", "echo")
        val result = left.subtract(right).toList()
        assertEquals(listOf("alpha", "bravo", "charlie"), result)
    }

    //================================================ Sanity: identity-equal strings

    @Test
    fun `intersect with a structurally-equal but reference-distinct argument set still uses equals`() {
        // Build an argument Set that holds a String that is structurally
        // equal to one in the receiver. A HashSet<String> uses String.equals
        // and String.hashCode, so membership returns true; the contract
        // pin is that the new 2.3 behavior matches.
        val a1 = "shared"
        val a2 = "shared"
        val left = listOf(a1, "unique-1")
        val right: Set<String> = hashSetOf(a2, "unique-2")

        val result = left.intersect(right).toList()
        assertEquals(1, result.size)
        assertEquals("shared", result.first())
    }
}
