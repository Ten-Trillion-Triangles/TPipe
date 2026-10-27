package com.TTT.Util

import com.TTT.Pipe.Pipe
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

//====
// Canonical-encoding tags. All integers are big-endian fixed-width, length-prefixed where noted.
//====

private const val TAG_CYCLE: Byte = 0x00
private const val TAG_NULL: Byte = 0x01
private const val TAG_BOOL: Byte = 0x02
private const val TAG_BYTE: Byte = 0x03
private const val TAG_CHAR: Byte = 0x04
private const val TAG_SHORT: Byte = 0x05
private const val TAG_INT: Byte = 0x06
private const val TAG_LONG: Byte = 0x07
private const val TAG_FLOAT: Byte = 0x08
private const val TAG_DOUBLE: Byte = 0x09
private const val TAG_STRING: Byte = 0x0A
private const val TAG_ENUM: Byte = 0x0B
private const val TAG_ARRAY: Byte = 0x0C
private const val TAG_LIST: Byte = 0x0D
private const val TAG_SET: Byte = 0x0E
private const val TAG_MAP: Byte = 0x0F
private const val TAG_OBJECT: Byte = 0x10
private const val TAG_FUNC: Byte = 0x11
private const val TAG_EX: Byte = 0x12
private const val TAG_TRUNCATED: Byte = 0x13

/**
 * Configures [stateHash] and [isDirty]. All setters are fluent and return [this] for chaining.
 *
 * Defaults mirror the exclusion semantics of [cloneInstance] (Util.kt:804-812): properties annotated
 * with [RuntimeState] or any annotation whose qualified name contains "Transient" are excluded from
 * the canonical form.
 */
class StateHashOptions
{
    private var maxDepthValue: Int = 64
    private var maxObjectsValue: Int = 10000
    private var encodeFunctionIdentityValue: Boolean = false
    private var exclusionPredicateValue: (KProperty1<*, *>) -> Boolean = { property ->
        property.annotations.any { it.annotationClass == RuntimeState::class } ||
        property.annotations.any { it.annotationClass.qualifiedName?.contains("Transient") == true }
    }

    /** The maximum object depth before the walk emits [TAG_TRUNCATED]. Default 64. */
    fun getMaxDepth(): Int = maxDepthValue

    /** Sets the maximum object depth (see [getMaxDepth]) and returns this options instance. */
    fun setMaxDepth(value: Int): StateHashOptions = apply { maxDepthValue = value }

    /** The maximum number of object descents before the walk emits [TAG_TRUNCATED]. Default 10000. */
    fun getMaxObjects(): Int = maxObjectsValue

    /** Sets the maximum number of object descents (see [getMaxObjects]) and returns this options instance. */
    fun setMaxObjects(value: Int): StateHashOptions = apply { maxObjectsValue = value }

    /** Whether [TAG_FUNC] values append their identity hash code. Default false. */
    fun getEncodeFunctionIdentity(): Boolean = encodeFunctionIdentityValue

    /** Sets whether function values append their identity hash code and returns this options instance. */
    fun setEncodeFunctionIdentity(value: Boolean): StateHashOptions = apply { encodeFunctionIdentityValue = value }

    /**
     * Replaces the default exclusion predicate. The predicate receives each candidate property and
     * returns true to exclude it from the canonical form.
     *
     * @param predicate Replacement for the clone-mirroring default.
     * @return This options instance for chaining.
     */
    fun exclude(predicate: (KProperty1<*, *>) -> Boolean): StateHashOptions = apply { exclusionPredicateValue = predicate }

    /**
     * Applies the exclusion predicate to a candidate property. Internal seam so the engine can honor
     * both the clone-mirroring default and operator-supplied overrides without leaking the predicate
     * to public API.
     */
    internal fun excludes(property: KProperty1<*, *>): Boolean = exclusionPredicateValue(property)
}

/**
 * Computes a deterministic SHA-256 state fingerprint of an object's semantic state graph.
 *
 * The walk covers the declared and inherited property surface, including private properties (the
 * same [cloneInstance] `memberProperties` seam, Util.kt:804-812 — in this build the seam exposes
 * private members, and they are hashed by default), excludes properties per [StateHashOptions],
 * guards cycles with an identity map, and caps depth and object count so unbounded graphs
 * terminate. Two structurally identical instances hash equal regardless of object identity,
 * Map/Set insertion order, or run.
 *
 * @param root The object to fingerprint. Null is a valid, stable root.
 * @param options Encoding options. Defaults mirror [cloneInstance] exclusions.
 * @return Lowercase hex SHA-256 digest (64 characters).
 */
fun stateHash(root: Any?, options: StateHashOptions = StateHashOptions()): String
{
    val writer = CanonicalWriter()
    encodeCanonicalValue(writer, root, options, IdentityHashMap(), 0, IntHolder())
    val digest = MessageDigest.getInstance("SHA-256").digest(writer.toBytes())

    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Reports whether the current state fingerprint of [root] differs from [lastHash].
 *
 * @param root The object to fingerprint.
 * @param lastHash A digest produced earlier by [stateHash] with matching options.
 * @param options Encoding options. Must match the options that produced [lastHash].
 * @return True when the fingerprints differ, meaning semantic state changed since the snapshot.
 */
fun isDirty(root: Any?, lastHash: String, options: StateHashOptions = StateHashOptions()): Boolean
{
    return stateHash(root, options) != lastHash
}

/**
 * Snapshots this pipe's semantic state as a deterministic state fingerprint.
 *
 * Two pipes built the same way produce the same snapshot; a mutation of any included field flips
 * it. This is the isDirty-style check: snapshot at build time, compare after any code path that may
 * reconfigure the pipe (DITL hooks, operator code, serialization round-trips).
 *
 * @return The lowercase hex SHA-256 digest of this pipe's canonical form.
 */
fun Pipe.stateSnapshot(): String
{
    return stateHash(this)
}

/**
 * Reports whether this pipe's semantic state has drifted since [lastSnapshot].
 *
 * @param lastSnapshot A digest produced earlier by [Pipe.stateSnapshot].
 * @return True when the current fingerprint differs from [lastSnapshot].
 */
fun Pipe.isDirtySince(lastSnapshot: String): Boolean
{
    return isDirty(this, lastSnapshot)
}

//====
// Internal canonical-encoding machinery
//====

/**
 * Mutable single-int holder threaded through the recursion to count encoded objects against
 * [StateHashOptions.getMaxObjects].
 */
private class IntHolder
{
    private var encodedCount: Int = 0

    /**
     * Increments the encoded-object counter when the [StateHashOptions.getMaxObjects] limit has not been reached.
     *
     * @param limit The maximum number of object encodings allowed.
     * @return False when the limit is already reached (truncation applies); true otherwise.
     */
    fun checkAndIncrement(limit: Int): Boolean
    {
        if(encodedCount >= limit)
        {
            return false
        }
        encodedCount++
        return true
    }
}

/**
 * Canonical byte stream writer. Accumulates tagged big-endian records in chunked form and
 * materializes a single array on demand.
 */
private class CanonicalWriter
{
    private val chunks: MutableList<ByteArray> = ArrayList()
    private var totalBytes: Int = 0

    /** Emits a single tag byte. */
    fun tag(value: Byte)
    {
        chunks.add(byteArrayOf(value))
        totalBytes++
    }

    /** Emits a 4-byte big-endian integer. */
    fun writeInt(value: Int)
    {
        chunks.add(intToBigEndian(value))
        totalBytes += 4
    }

    /** Emits an 8-byte big-endian long. */
    fun writeLong(value: Long)
    {
        chunks.add(longToBigEndian(value))
        totalBytes += 8
    }

    /** Emits a float as its 4-byte raw-bit encoding. */
    fun writeFloatBits(value: Float)
    {
        writeInt(value.toRawBits())
    }

    /** Emits a double as its 8-byte raw-bit encoding. */
    fun writeDoubleBits(value: Double)
    {
        writeLong(value.toRawBits())
    }

    /** Emits a 4-byte big-endian length followed by the raw payload bytes. */
    fun writeLenPrefixed(payload: ByteArray)
    {
        chunks.add(intToBigEndian(payload.size))
        chunks.add(payload)
        totalBytes += 4 + payload.size
    }

    /** Appends the given payload bytes verbatim. */
    fun writeRaw(bytes: ByteArray)
    {
        chunks.add(bytes)
        totalBytes += bytes.size
    }

    /** Materializes the accumulated stream as a single byte array. */
    fun toBytes(): ByteArray
    {
        val materialized = ByteArray(totalBytes)
        var offset = 0
        for(chunk in chunks)
        {
            System.arraycopy(chunk, 0, materialized, offset, chunk.size)
            offset += chunk.size
        }
        return materialized
    }
}

/** Converts an int into its 4-byte big-endian representation. */
private fun intToBigEndian(value: Int): ByteArray
{
    return byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

/** Converts a long into its 8-byte big-endian representation. */
private fun longToBigEndian(value: Long): ByteArray
{
    return byteArrayOf(
        ((value ushr 56) and 0xFF).toByte(),
        ((value ushr 48) and 0xFF).toByte(),
        ((value ushr 40) and 0xFF).toByte(),
        ((value ushr 32) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

/**
 * Compares two canonical byte sequences in unsigned lexicographic order, matching the ordering
 * of a native byte-array sort. Any total order would preserve determinism; unsigned lexicographic
 * is the one with a stable native reference.
 */
private fun unsignedLexicographicCompareTo(left: ByteArray, right: ByteArray): Int
{
    val commonLength = minOf(left.size, right.size)
    for(index in 0 until commonLength)
    {
        val difference = (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
        if(difference != 0)
        {
            return difference
        }
    }
    return left.size - right.size
}

/** Deterministic ordering for canonical byte payloads, used to make Map/Set encodings insertion-order independent. */
private val unsignedByteArrayComparator = Comparator<ByteArray> { left, right -> unsignedLexicographicCompareTo(left, right) }

/**
 * Recursively encodes [value] into [out] in canonical form. Cycles emit [TAG_CYCLE], depth and
 * object caps emit [TAG_TRUNCATED], and getter failures emit [TAG_EX] so a throwing property
 * degrades to a stable marker instead of aborting the whole fingerprint.
 *
 * @param out Target byte stream.
 * @param value Value to encode. Null encodes as [TAG_NULL].
 * @param options Encoding options in force.
 * @param visited In-flight object guard for cycle termination.
 * @param depth Current descent depth from the root.
 * @param counter Shared object-count cap tracker.
 */
private fun encodeCanonicalValue(
    out: CanonicalWriter,
    value: Any?,
    options: StateHashOptions,
    visited: IdentityHashMap<Any, Unit>,
    depth: Int,
    counter: IntHolder
)
{
    when(value)
    {
        null -> out.tag(TAG_NULL)

        is Boolean -> {
            out.tag(TAG_BOOL)
            out.writeInt(if(value) 1 else 0)
        }

        is Byte -> {
            out.tag(TAG_BYTE)
            out.writeInt(value.toInt())
        }

        is Char -> {
            out.tag(TAG_CHAR)
            out.writeInt(value.code)
        }

        is Short -> {
            out.tag(TAG_SHORT)
            out.writeInt(value.toInt())
        }

        is Int -> {
            out.tag(TAG_INT)
            out.writeInt(value)
        }

        is Long -> {
            out.tag(TAG_LONG)
            out.writeLong(value)
        }

        is Float -> {
            out.tag(TAG_FLOAT)
            out.writeFloatBits(value)
        }

        is Double -> {
            out.tag(TAG_DOUBLE)
            out.writeDoubleBits(value)
        }

        is String -> {
            out.tag(TAG_STRING)
            out.writeLenPrefixed(value.toByteArray(Charsets.UTF_8))
        }

        is Enum<*> -> {
            out.tag(TAG_ENUM)
            out.writeLenPrefixed(value.name.toByteArray(Charsets.UTF_8))
        }

        is Function<*> -> {
            out.tag(TAG_FUNC)
            if(options.getEncodeFunctionIdentity())
            {
                out.writeInt(System.identityHashCode(value))
            }
        }

        else -> {
            if(value.javaClass.isArray)
            {
                out.tag(TAG_ARRAY)
                val arrayLength = java.lang.reflect.Array.getLength(value)
                out.writeInt(arrayLength)
                for(index in 0 until arrayLength)
                {
                    encodeCanonicalValue(out, java.lang.reflect.Array.get(value, index), options, visited, depth, counter)
                }
            }
            else if(value is Map<*, *>)
            {
                out.tag(TAG_MAP)
                val encodedEntries = value.entries.map { entry ->
                    val entryWriter = CanonicalWriter()
                    encodeCanonicalValue(entryWriter, entry.key, options, visited, depth, counter)
                    encodeCanonicalValue(entryWriter, entry.value, options, visited, depth, counter)
                    entryWriter.toBytes()
                }
                val sortedEntries = encodedEntries.sortedWith(unsignedByteArrayComparator)
                out.writeInt(sortedEntries.size)
                for(bytes in sortedEntries)
                {
                    out.writeRaw(bytes)
                }
            }
            else if(value is Iterable<*>)
            {
                out.tag(if(value is List<*>) TAG_LIST else TAG_SET)
                val encodedElements = value.map { element ->
                    val elementWriter = CanonicalWriter()
                    encodeCanonicalValue(elementWriter, element, options, visited, depth, counter)
                    elementWriter.toBytes()
                }
                val orderedElements = if(value is List<*>) encodedElements else encodedElements.sortedWith(unsignedByteArrayComparator)
                out.writeInt(orderedElements.size)
                for(bytes in orderedElements)
                {
                    out.writeRaw(bytes)
                }
            }
            else
            {
                if(visited.containsKey(value))
                {
                    out.tag(TAG_CYCLE)
                    return
                }
                if(depth >= options.getMaxDepth())
                {
                    out.tag(TAG_TRUNCATED)
                    return
                }
                if(!counter.checkAndIncrement(options.getMaxObjects()))
                {
                    out.tag(TAG_TRUNCATED)
                    return
                }
                visited[value] = Unit
                try
                {
                    out.tag(TAG_OBJECT)
                    val kClass = value::class
                    out.writeLenPrefixed(kClass.java.name.toByteArray(Charsets.UTF_8))
                    val properties = kClass.memberProperties
                        .filter { !options.excludes(it) }
                        .sortedBy { it.name }
                    out.writeInt(properties.size)
                    for(property in properties)
                    {
                        out.writeLenPrefixed(property.name.toByteArray(Charsets.UTF_8))
                        try
                        {
                            property.isAccessible = true
                            @Suppress("UNCHECKED_CAST")
                            val fieldValue = (property as KProperty1<Any, Any?>).get(value)
                            encodeCanonicalValue(out, fieldValue, options, visited, depth + 1, counter)
                        }
                        catch(accessError: Throwable)
                        {
                            out.tag(TAG_EX)
                            out.writeLenPrefixed(accessError.javaClass.simpleName.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                finally
                {
                    visited.remove(value)
                }
            }
        }
    }
}
