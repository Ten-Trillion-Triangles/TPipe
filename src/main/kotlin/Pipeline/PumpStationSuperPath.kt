package com.TTT.Pipeline

import com.TTT.Context.ConverseHistory
import com.TTT.P2P.KillSwitch
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

//============================================== Group S: SuperPath ============================================================

/**
 * A bundle of contained [PathObject]s exposed to the dispatch agent as a single dispatchable
 * path — the "connector of paths." Where a normal [PathObject] is one named action, a
 * [SuperPath] is a container that houses many and lets the dispatch LLM learn what's inside
 * on demand, then route into a specific inner path "like normal."
 *
 * ## Why
 *
 * [com.TTT.Pipeline.PumpStation]'s reserve/reveal system hides paths at the cost of *breaking
 * the dispatch prompt cache* every time the visible menu changes. A [SuperPath] is the
 * counterpart: it keeps exactly **one** stable line in the menu (its own protocol schema), so
 * prompt caching survives, and defers the per-inner-path knowledge to 1–2 LLM query turns
 * whose answers persist in the conversation history. The trade is a small temporary lookup
 * cost in exchange for a stable, cache-friendly menu.
 *
 * ## Query protocol
 *
 * The dispatch agent queries a [SuperPath] with one of three envelope ops (documented in the
 * auto-generated [pathSchema]):
 *
 * | op        | extra fields | effect                                                        |
 *|-----------|--------------|---------------------------------------------------------------|
 *| `list`    | —            | reply with the names + descriptions of every contained path   |
 *| `describe`| `pathName`   | reply with the [PathDescriptionData] the agent would see in its own system prompt |
 *| `invoke`  | `pathName`, `args` | route into that inner path and run its normal execution stack |
 *
 * When the input carries no valid envelope op, the [SuperPath] falls back to **schema
 * sniffing**: it parses the input JSON and matches it against each contained path's required
 * top-level keys. A unique match is invoked with the whole candidate as its input; multiple
 * matches produce a disambiguation reply; zero matches fall back to the listing reply.
 *
 * ## Execution
 *
 * The [SuperPath]'s routing **is** its execution function. [PathObject]'s mechanism-priority
 * stack picks up that execution function, so a [SuperPath] needs no separate
 * [com.TTT.Pipeline.PathObject.executeLocal] override for the station-driven case. Contained
 * paths are **never** individually registered on the station — that is the whole point.
 *
 * An invoked inner path's loop-control flags ([MultimodalContent.passPipeline],
 * [MultimodalContent.terminatePipeline], [MultimodalContent.interuptPipeline]) are carried on
 * the returned content unchanged, so exit mechanics behave exactly as they do for a directly
 * dispatched path.
 *
 * ## Cost and kill switch
 *
 * Because contained paths are not members of the station's [com.TTT.Pipeline.PumpStation.pathList],
 * per-inner-path kill switches are **not** individually enforced by the harness. The
 * [SuperPath]'s own [killSwitch] and the station-level checks cover cost. Inner-path
 * enforcement is left to the contained path's own agent budgeting.
 */
open class SuperPath(override var killSwitch: KillSwitch? = null) : PathObject(killSwitch)
{

    //================================================= Constants ===============================================================

    /**
     * Companion constants for the query protocol and recursion guard. These are UPPER_SNAKE
     * constants per house style; they define the wire keys the dispatch LLM emits and the
     * depth limit for nested [SuperPath] bundles.
     */
    companion object
    {
        // Envelope op values
        const val OP_LIST = "list"
        const val OP_DESCRIBE = "describe"
        const val OP_INVOKE = "invoke"

        // Resolution op labels used in [SuperPathRouted] events (the three above plus internal branches)
        const val RES_SNIFF = "sniff"
        const val RES_CUSTOM_ROUTER = "customRouter"
        const val RES_DISAMBIGUATE = "disambiguate"
        const val RES_UNKNOWN = "unknown"

        // Envelope wire keys
        const val SUPER_OP = "op"
        const val SUPER_PATH_NAME = "pathName"
        const val SUPER_ARGS = "args"

        // Recursion guard — max nesting of [SuperPath] containing [SuperPath]. Depth 1 = one SuperPath
        // of plain paths. A depth-5 chain (five SuperPaths nested) is rejected at init time.
        const val MAX_RECURSION_DEPTH = 4
    }

    //================================================= State ===============================================================

    /**
     * The contained [PathObject]s this bundle routes into. Added at build time via
     * [addPath] / [addPaths] / [setContainedPaths] or the DSL's nested `path { }` blocks.
     */
    private val _containedPaths = mutableListOf<PathObject>()

    /**
     * Immutable view of the contained paths. The harness and observers read this; mutation
     * is only permitted through the builder methods before the station inits the path.
     */
    val containedPaths: List<PathObject> get() = _containedPaths.toList()

    /**
     * Developer-supplied override for the `list` / zero-match reply. When null (the default)
     * the reply is auto-generated from the contained paths' names and descriptions, with a
     * hint for how to follow up with `describe` or `invoke`.
     */
    var defaultListingMessage: String? = null

    /**
     * Optional strict router. When set and it returns non-null, its chosen contained path wins
     * over both the envelope ops and schema sniffing. Return null to defer to the built-in
     * resolution. This is the escape hatch for developers who need deterministic control the
     * keyword/sniff heuristics can't provide.
     */
    var customRouter: ((MultimodalContent, PumpStation, ConverseHistory?, String) -> PathObject?)? = null

    /**
     * The [SuperPath] routes through its own execution function (priority 2 of
     * [com.TTT.Pipeline.PathObject]'s mechanism stack). Setting it here means the subclass
     * has a valid execution mechanism and [com.TTT.Pipeline.PathObject.init]'s "at least one
     * mechanism" check passes with no extra configuration.
     */
    init
    {
        setExecutionFunction { content, station, history, summary ->
            routeInto(content, station, history, summary)
        }
    }

    //================================================= Builder methods ===============================================================

    /**
     * Adds a single contained path to this bundle. Builder-chaining (returns [this]).
     *
     * @param path The contained [PathObject] to route into. Must have at least one execution
     *        mechanism configured, enforced at [init] time.
     * @return This [SuperPath] for chaining.
     */
    fun addPath(path: PathObject): SuperPath
    {
        _containedPaths.add(path)
        return this
    }

    /**
     * Adds a batch of contained paths to this bundle. Builder-chaining (returns [this]).
     *
     * @param paths The contained [PathObject]s to route into.
     * @return This [SuperPath] for chaining.
     */
    fun addPaths(paths: List<PathObject>): SuperPath
    {
        _containedPaths.addAll(paths)
        return this
    }

    /**
     * Replaces the current contained set with the given list. Builder-chaining (returns [this]).
     *
     * @param paths The new complete contained set.
     * @return This [SuperPath] for chaining.
     */
    fun setContainedPaths(paths: List<PathObject>): SuperPath
    {
        _containedPaths.clear()
        _containedPaths.addAll(paths)
        return this
    }

    //================================================= init ===============================================================

    /**
     * Initializes this [SuperPath], transitioning it from build-time configuration to
     * runtime-ready state. Extends [com.TTT.Pipeline.PathObject.init] with [SuperPath]-specific
     * validation:
     *
     * 1. Fills [pathSchema] with the auto-generated [resolveProtocolSchema] when the developer
     *    left it blank — this is the cache-stable menu line the dispatch agent sees.
     * 2. Requires at least one contained path.
     * 3. Requires the containment tree depth to be within [MAX_RECURSION_DEPTH].
     * 4. Eagerly calls [com.TTT.Pipeline.PathObject.init] on every contained path so a
     *    misconfigured inner path (no execution mechanism, blank name) is a build-time error,
     *    matching the [com.TTT.Pipeline.PathObject] house rule.
     *
     * @return [PathDescriptionData] for this [SuperPath] (with the protocol schema in
     *         [PathDescriptionData.inputSchema]).
     * @throws IllegalArgumentException if no contained paths are set, or a contained path
     *         fails its own init (no execution mechanism / blank name).
     * @throws IllegalStateException if the containment depth exceeds [MAX_RECURSION_DEPTH].
     */
    override suspend fun init(): PathDescriptionData
    {
        if (pathSchema.isBlank())
        {
            pathSchema = resolveProtocolSchema()
        }

        // Base validation: name set + at least one execution mechanism (our executionFunction).
        val base = super.init()

        require(_containedPaths.isNotEmpty())
        {
            "SuperPath.init() failed for path '$pathName': no contained paths. " +
            "Add at least one via addPath / addPaths / setContainedPaths."
        }

        val depth = depthOf(this)
        require(depth <= MAX_RECURSION_DEPTH)
        {
            "SuperPath.init() failed for path '$pathName': containment depth $depth exceeds " +
            "the maximum of $MAX_RECURSION_DEPTH. Flatten the bundle."
        }

        for (p in _containedPaths)
        {
            p.init()
        }

        return base
    }

    //================================================= Resolution ===============================================================

    /**
     * Resolves and routes one [SuperPath] query. This is the body of the [SuperPath]'s
     * execution function, invoked by [com.TTT.Pipeline.PathObject]'s mechanism-priority stack
     * (execution function is priority 2, so PCP functions bound on the [SuperPath] itself take
     * precedence — a rare, intentional case).
     *
     * Resolution order:
     * 1. [customRouter] — if set and non-null, its target wins outright.
     * 2. Envelope op (`list` / `describe` / `invoke`).
     * 3. Schema sniffing against the contained paths' required keys.
     * 4. Zero/unknown → listing reply.
     *
     * Emits a [SuperPathRouted] event on every branch so traces can follow sub-agent traffic
     * that never appears in the dispatch menu.
     *
     * @param content The [MultimodalContent] input to this [SuperPath] (the dispatch agent's
     *        envelope or raw JSON).
     * @param station Reference to the parent [PumpStation] that owns this path.
     * @param history The active [ConverseHistory] at invocation time (may be null).
     * @param summary The turn summary at invocation time.
     * @return The [MultimodalContent] produced by the listing, description, or routed inner
     *         path.
     */
    private suspend fun routeInto(
        content: MultimodalContent,
        station: PumpStation,
        history: ConverseHistory?,
        summary: String
    ): MultimodalContent
    {
        // 1. Strict custom router wins over everything.
        val customTarget = customRouter?.invoke(content, station, history, summary)
        if (customTarget != null)
        {
            emitRouted(station, RES_CUSTOM_ROUTER, customTarget, success = true)
            return invokeInner(customTarget, content.text, station, history, summary)
        }

        // 2. Envelope op.
        val envelope = extractJson<JsonObject>(content.text)
        val op = stringOf(envelope?.get(SUPER_OP))?.trim()?.lowercase()

        when (op)
        {
            OP_LIST ->
            {
                emitRouted(station, OP_LIST, null, success = true)
                return listingReply()
            }

            OP_DESCRIBE ->
            {
                val target = findByName(stringOf(envelope?.get(SUPER_PATH_NAME)))
                if (target != null)
                {
                    emitRouted(station, OP_DESCRIBE, target, success = true)
                    return describeReply(target)
                }
                // Unknown target — teach the LLM via history.
                emitRouted(station, RES_UNKNOWN, null, success = false)
                return unknownTargetReply(stringOf(envelope?.get(SUPER_PATH_NAME)))
            }

            OP_INVOKE ->
            {
                val target = findByName(stringOf(envelope?.get(SUPER_PATH_NAME)))
                if (target != null)
                {
                    val args = envelope?.get(SUPER_ARGS)?.toString() ?: "{}"
                    emitRouted(station, OP_INVOKE, target, success = true)
                    return invokeInner(target, args, station, history, summary)
                }
                emitRouted(station, RES_UNKNOWN, null, success = false)
                return unknownTargetReply(stringOf(envelope?.get(SUPER_PATH_NAME)))
            }

            // 3. No valid op → schema sniffing.
            else -> return sniff(content.text, station, history, summary)
        }
    }

    /**
     * Envelope-free fallback. Parses the input as JSON and matches it against each contained
     * path's required top-level keys. A unique match is invoked with the whole candidate as
     * its input; multiple matches yield a disambiguation reply; zero matches yield the
     * listing reply.
     *
     * @param candidateJson The raw input text to sniff.
     * @param station Reference to the parent [PumpStation].
     * @param history The active [ConverseHistory] (may be null).
     * @param summary The turn summary.
     * @return The routed result, disambiguation reply, or listing reply.
     */
    private suspend fun sniff(
        candidateJson: String,
        station: PumpStation,
        history: ConverseHistory?,
        summary: String
    ): MultimodalContent
    {
        val candidate = extractJson<JsonObject>(candidateJson)
        if (candidate == null)
        {
            // No parsable input — fall back to the listing.
            emitRouted(station, RES_UNKNOWN, null, success = false)
            return listingReply()
        }

        val matches = _containedPaths.filter { matchesSchema(candidate, it.pathSchema) }
        return when
        {
            matches.size == 1 ->
            {
                val target = matches[0]
                emitRouted(station, RES_SNIFF, target, success = true)
                invokeInner(target, candidate.toString(), station, history, summary)
            }
            matches.size > 1 ->
            {
                emitRouted(station, RES_DISAMBIGUATE, null, success = false)
                disambiguate(matches)
            }
            else ->
            {
                emitRouted(station, RES_UNKNOWN, null, success = false)
                listingReply()
            }
        }
    }

    //================================================= Replies ===============================================================

    /**
     * Builds the `list` / zero-match reply. Uses [defaultListingMessage] when set, otherwise an
     * auto-generated listing of every contained path's name and description plus a follow-up
     * hint.
     *
     * @return A [MultimodalContent] describing the contained paths.
     */
    private fun listingReply(): MultimodalContent
    {
        val custom = defaultListingMessage
        if (custom != null)
        {
            return MultimodalContent(text = custom)
        }

        val lines = _containedPaths.map {
            " - ${it.pathName}: ${it.pathDescription.ifBlank { "(no description)" }}"
        }
        val body = "Available paths in '$pathName' (query me to use them):\n" +
            lines.joinToString("\n") +
            "\n\nTo see a path's schema, reply with " +
            "{\"$SUPER_OP\":\"$OP_DESCRIBE\",\"$SUPER_PATH_NAME\":\"<name>\"}. " +
            "To run one, reply with " +
            "{\"$SUPER_OP\":\"$OP_INVOKE\",\"$SUPER_PATH_NAME\":\"<name>\",\"$SUPER_ARGS\":{...}}."
        return MultimodalContent(text = body)
    }

    /**
     * Builds the `describe` reply for a specific contained path. Serializes the target's
     * [PathDescriptionData] — the exact record the dispatch agent would see in its own system
     * prompt — plus an invoke hint. This is what the "same information" guarantee means.
     *
     * @param target The contained path to describe.
     * @return A [MultimodalContent] containing the serialized [PathDescriptionData].
     */
    private suspend fun describeReply(target: PathObject): MultimodalContent
    {
        val descriptor = target.init()
        val json = serialize(descriptor)
        val body = "Path '${target.pathName}' inside '$pathName':\n$json\n\n" +
            "Invoke with {\"$SUPER_OP\":\"$OP_INVOKE\",\"$SUPER_PATH_NAME\":\"${target.pathName}\",\"$SUPER_ARGS\":{...}}."
        return MultimodalContent(text = body)
    }

    /**
     * Builds a disambiguation reply when schema sniffing matched more than one contained path.
     * Names the candidates so the LLM re-queries precisely on the next turn.
     *
     * @param matches The candidate contained paths that all matched.
     * @return A [MultimodalContent] listing the candidates and the follow-up protocol.
     */
    private fun disambiguate(matches: List<PathObject>): MultimodalContent
    {
        val names = matches.joinToString(" | ") { "${it.pathName} (${it.pathDescription.ifBlank { "(no description)" }})" }
        val body = "Your input matched multiple paths in '$pathName': $names. " +
            "Pick one precisely and re-send with {\"$SUPER_OP\":\"$OP_INVOKE\",\"$SUPER_PATH_NAME\":\"<name>\",\"$SUPER_ARGS\":{...}}."
        return MultimodalContent(text = body)
    }

    /**
     * Builds a correction reply when an envelope op named a contained path that doesn't exist.
     * Written into history so the LLM learns the valid set (harness-notice style).
     *
     * @param unknownName The (unknown) path name the LLM tried to target, if any.
     * @return A [MultimodalContent] listing the valid contained paths.
     */
    private fun unknownTargetReply(unknownName: String?): MultimodalContent
    {
        val validNames = _containedPaths.joinToString(" | ") { it.pathName }
        val attempted = unknownName?.let { "You asked for '$it', which isn't in '$pathName'. " } ?: ""
        val body = attempted +
            "Valid paths in '$pathName': $validNames. " +
            "Query with {\"$SUPER_OP\":\"$OP_DESCRIBE\",\"$SUPER_PATH_NAME\":\"<name>\"} to see a schema, " +
            "or {\"$SUPER_OP\":\"$OP_INVOKE\",\"$SUPER_PATH_NAME\":\"<name>\",\"$SUPER_ARGS\":{...}} to run one."
        return MultimodalContent(text = body)
    }

    //================================================= Routing internals ===============================================================

    /**
     * Invokes a contained path through its normal execution stack and stamps the result's
     * metadata with the routing provenance. The inner path's loop-control flags are carried on
     * the returned content unchanged, so exit mechanics behave "like normal."
     *
     * @param target The contained [PathObject] to run.
     * @param input The [MultimodalContent] text input for the inner path (the `args` for
     *        envelope-invoke, the whole candidate for sniffing, or the raw input for
     *        custom-router).
     * @param station Reference to the parent [PumpStation].
     * @param history The active [ConverseHistory] (may be null).
     * @param summary The turn summary.
     * @return The routed [MultimodalContent] with `routedVia` and `superPathOp` metadata.
     */
    private suspend fun invokeInner(
        target: PathObject,
        input: String,
        station: PumpStation,
        history: ConverseHistory?,
        summary: String
    ): MultimodalContent
    {
        val inputContent = MultimodalContent(text = input)
        val result = target.execute(inputContent, station, history, summary)
        result.metadata["routedVia"] = "$pathName/${target.pathName}"
        result.metadata["superPathOp"] = OP_INVOKE
        return result
    }

    /**
     * Safe string extraction from a JSON element. LLM-supplied envelope fields can be
     * malformed (an object or array where a string was expected); [jsonPrimitive] would
     * throw on those, which would convert a recoverable "unknown target" into a
     * [SuperPath] failure. This returns the raw string for primitives and null for
     * anything that is not cleanly scalar.
     *
     * @param element The [kotlinx.serialization.json.JsonElement] to read (may be null).
     * @return The element's string content, or null if absent or not a scalar.
     */
    private fun stringOf(element: kotlinx.serialization.json.JsonElement?): String?
    {
        val scalar = element as? JsonPrimitive ?: return null
        // Only quoted JSON strings are valid path names. Numbers/booleans/objects/arrays
        // in the envelope are malformed input and resolve as "no target".
        return if (scalar.isString) scalar.content else null
    }

    /**
     * Case-insensitive lookup of a contained path by name.
     *
     * @param name The (possibly null or blank) name to look up.
     * @return The matching contained [PathObject], or null if none matches.
     */
    private fun findByName(name: String?): PathObject?
    {
        if (name.isNullOrBlank()) return null
        return _containedPaths.firstOrNull { it.pathName.equals(name.trim(), ignoreCase = true) }
    }

    /**
     * Computes the required top-level keys for a contained path's schema (JSON-Schema
     * `required` if present, else `properties` keys). Returns null when the schema is blank or
     * carries no sniffable keys — such paths are not sniff candidates.
     *
     * @param schema The contained path's [PathObject.pathSchema] string.
     * @return The list of required keys, or null if the path is not sniffable.
     */
    private fun requiredKeysOf(schema: String): List<String>?
    {
        if (schema.isBlank()) return null
        val s = extractJson<JsonObject>(schema) ?: return null
        val requiredArr = (s["required"] as? JsonArray)?.map { it.jsonPrimitive.content }
        if (requiredArr != null && requiredArr.isNotEmpty()) return requiredArr
        val props = (s["properties"] as? JsonObject)?.keys?.toList()
        if (!props.isNullOrEmpty()) return props
        return null
    }

    /**
     * Returns true when the candidate JSON object contains every required key of the schema —
     * the schema-sniffing match test.
     *
     * @param candidate The parsed input JSON object.
     * @param schema The contained path's [PathObject.pathSchema] string.
     * @return True if the candidate is a sniff match for this schema.
     */
    private fun matchesSchema(candidate: JsonObject, schema: String): Boolean
    {
        val required = requiredKeysOf(schema) ?: return false
        return required.all { candidate.containsKey(it) }
    }

    /**
     * Measures the nesting depth of this [SuperPath] tree (1 = a [SuperPath] of plain paths).
     * Only [SuperPath] branches contribute to the depth; plain contained paths add nothing.
     *
     * @param sp The [SuperPath] to measure from.
     * @return The maximum containment depth reachable from [sp].
     */
    private fun depthOf(sp: SuperPath, visited: MutableSet<PathObject> = mutableSetOf()): Int
    {
        if (!visited.add(sp)) return 0
        var depth = 1
        for (p in sp.containedPaths)
        {
            if (p is SuperPath)
            {
                depth = maxOf(depth, 1 + depthOf(p, visited))
            }
        }
        return depth
    }

    /**
     * Emits a [SuperPathRouted] event for one resolution branch. This is the only place the
     * [SuperPath] publishes to the harness funnel, so traces and observers can follow
     * sub-agent traffic that is invisible to the dispatch menu.
     *
     * @param station The owning [PumpStation] (provides the event funnel and task state).
     * @param op The resolution op label (one of [OP_LIST], [OP_DESCRIBE], [OP_INVOKE],
     *        [RES_SNIFF], [RES_CUSTOM_ROUTER], [RES_DISAMBIGUATE], [RES_UNKNOWN]).
     * @param target The resolved contained path, or null for list/disambiguate/unknown ops.
     * @param success Whether the op produced a useful reply.
     */
    private fun emitRouted(station: PumpStation, op: String, target: PathObject?, success: Boolean)
    {
        val ts = station.getTaskState()
        station.emitEventInternal(SuperPathRouted(
            runId = ts.runId,
            turnIndex = ts.turnIndex,
            superPathName = pathName,
            op = op,
            targetPathName = target?.pathName,
            success = success
        ))
    }

    //================================================= Protocol schema ===============================================================

    /**
     * Builds the cache-stable JSON-Schema hint shown to the dispatch agent in the path menu.
     * It documents the three envelope ops, the valid contained-path names, and the `args`
     * field — one line that survives prompt caching even as the LLM discovers the bundle
     * contents turn by turn.
     *
     * @return A compact JSON-Schema string describing the [SuperPath] query protocol.
     */
    fun resolveProtocolSchema(): String
    {
        val names = _containedPaths.map { it.pathName }
        val opEnum = names.joinToString(" | ")
        val opValues = listOf(OP_LIST, OP_DESCRIBE, OP_INVOKE)
        val json = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("description", JsonPrimitive(
                "Query the '$pathName' bundle. " +
                    "$OP_LIST → list contained paths; " +
                    "$OP_DESCRIBE + $SUPER_PATH_NAME → show a contained path's schema; " +
                    "$OP_INVOKE + $SUPER_PATH_NAME + $SUPER_ARGS → run a contained path."
            ))
            put("properties", buildJsonObject {
                put("op", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", JsonArray(opValues.map { JsonPrimitive(it) }))
                })
                put(SUPER_PATH_NAME, buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Contained path to target. One of: $opEnum"))
                })
                put(SUPER_ARGS, buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Structured input for the target path when op=$OP_INVOKE"))
                })
            })
        }
        return json.toString()
    }
}
