package com.TTT.Context

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Provider-neutral identity for an execution that is using TPipe context.
 *
 * TPipe deliberately does not assign meaning to the identifier. Hosts such as
 * Apex may use an agent, run, or session identifier, while other hosts may use
 * a different identity scheme.
 *
 * @param id Opaque, non-blank host-defined identity.
 */
data class ExecutionPrincipal(val id: String)
{
    init
    {
        require(id.isNotBlank()) { "Execution principal id must not be blank." }
    }
}

/** The kinds of resources that can be protected by ContextBank authority. */
@Serializable
enum class ContextResourceKind
{
    CONTEXT_WINDOW,
    TODO_LIST,
    BANKED_CONTEXT
}

/** The operation being authorized against a context resource. */
enum class ContextAccessOperation
{
    READ,
    WRITE,
    CREATE,
    DELETE,
    ENUMERATE,
    DELEGATE
}

/** Controls how an active scope treats resources without authorization metadata. */
enum class ContextAccessEnforcementMode
{
    /** Preserve TPipe's historical missing-metadata behavior. */
    LEGACY_COMPATIBLE,

    /** Require an enrolled sidecar for every secured resource operation. */
    REQUIRE_ENROLLMENT
}

/**
 * Opaque resource selectors used by an access scope.
 *
 * A resource selector is intentionally independent from a ContextBank page
 * key. Page keys are storage addresses; resource and boundary identifiers are
 * host-owned authorization identifiers persisted in the sidecar metadata.
 */
sealed interface ContextResourceSelector
{
    /** Select one exact opaque resource identifier.
     *
     * @param id Opaque host-defined resource identifier.
     */
    data class Resource(val id: String) : ContextResourceSelector
    {
        init
        {
            require(id.isNotBlank()) { "Context resource selector id must not be blank." }
        }
    }

    /** Select resources enrolled in one opaque boundary/domain identifier.
     *
     * @param id Opaque host-defined boundary identifier.
     */
    data class Boundary(val id: String) : ContextResourceSelector
    {
        init
        {
            require(id.isNotBlank()) { "Context boundary selector id must not be blank." }
        }
    }
}

/**
 * Resolves whether an opaque resource belongs to an opaque authorization
 * boundary during trusted scope attenuation.
 */
fun interface ContextResourceContainmentResolver
{
    /**
     * Return whether [resource] is enrolled in [boundary]. The resolver may
     * consult local or remote metadata, but it must never return the protected
     * resource value.
     *
     * @param resource Exact [ContextResourceSelector.Resource] to resolve.
     * @param boundary Parent [ContextResourceSelector.Boundary] to check.
     * @return `true` when [resource] is contained by [boundary].
     */
    suspend fun isResourceInBoundary(
        resource: ContextResourceSelector.Resource,
        boundary: ContextResourceSelector.Boundary
    ): Boolean
}

/**
 * Immutable rights supplied when a host creates or attenuates an access scope.
 * Empty sets mean that the corresponding operation is not granted.
 *
 * @param readableResources Selectors allowed for [ContextAccessOperation.READ].
 * @param writableResources Selectors allowed for [ContextAccessOperation.WRITE].
 * @param creatableResources Selectors allowed for [ContextAccessOperation.CREATE].
 * @param deletableResources Selectors allowed for [ContextAccessOperation.DELETE].
 * @param enumerableResources Selectors allowed for [ContextAccessOperation.ENUMERATE].
 * @param delegable Whether this authority may be delegated to a child execution.
 */
data class ContextAccessRights(
    val readableResources: Set<ContextResourceSelector> = emptySet(),
    val writableResources: Set<ContextResourceSelector> = emptySet(),
    val creatableResources: Set<ContextResourceSelector> = emptySet(),
    val deletableResources: Set<ContextResourceSelector> = emptySet(),
    val enumerableResources: Set<ContextResourceSelector> = emptySet(),
    val delegable: Boolean = false
)

/**
 * Immutable authority carried by TPipe execution code.
 *
 * Instances can be created only by [ContextAccess] and can be narrowed by
 * exact intersection or trusted resource containment. They contain no
 * provider or Apex-specific policy.
 *
 * @param principal Opaque [ExecutionPrincipal] associated with the execution.
 * @param rights [ContextAccessRights] carried by this scope.
 * @param enforcementMode [ContextAccessEnforcementMode] for resources without authorization metadata.
 */
class ContextAccessScope internal constructor(
    val principal: ExecutionPrincipal,
    internal val rights: ContextAccessRights,
    val enforcementMode: ContextAccessEnforcementMode
)
{
    /**
     * Narrow this scope without consulting ambient execution state.
     *
     * @param requested [ContextAccessRights] the derived scope may retain.
     * @return A scope with the intersection of both scopes' rights.
     */
    fun attenuate(requested: ContextAccessRights): ContextAccessScope
    {
        return ContextAccess.attenuate(this, requested)
    }

    /**
     * Narrow this scope using trusted resource-boundary containment metadata.
     *
     * @param requested [ContextAccessRights] the derived scope may retain.
     * @param resolver Trusted [ContextResourceContainmentResolver] for boundary-to-resource containment.
     * @return A scope narrowed by rights and resolved containment.
     */
    suspend fun attenuateForResources(
        requested: ContextAccessRights,
        resolver: ContextResourceContainmentResolver
    ): ContextAccessScope
    {
        return ContextAccess.attenuateForResources(this, requested, resolver)
    }

    /**
     * Check whether this scope grants an operation for enrolled metadata.
     *
     * @param operation Operation to check.
     * @param metadata Enrolled resource metadata being evaluated.
     * @return `true` when one of the operation's selectors covers the resource.
     */
    internal fun allows(
        operation: ContextAccessOperation,
        metadata: ContextResourceMetadata
    ): Boolean
    {
        val selectors = when(operation)
        {
            ContextAccessOperation.READ -> rights.readableResources
            ContextAccessOperation.WRITE -> rights.writableResources
            ContextAccessOperation.CREATE -> rights.creatableResources
            ContextAccessOperation.DELETE -> rights.deletableResources
            ContextAccessOperation.ENUMERATE -> rights.enumerableResources
            ContextAccessOperation.DELEGATE -> emptySet()
        }

        return selectors.any { selector ->
            when(selector)
            {
                is ContextResourceSelector.Resource -> selector.id == metadata.resourceId
                is ContextResourceSelector.Boundary -> selector.id == metadata.boundaryId
            }
        }
    }

    /**
     * Check whether this scope may issue an attenuated child scope.
     *
     * @return `true` when delegation is granted.
     */
    internal fun canDelegate(): Boolean = rights.delegable

    /**
     * Check whether this scope has any selectors with which to enumerate.
     *
     * @return `true` when enumeration selectors are present.
     */
    internal fun hasEnumerationAuthority(): Boolean = rights.enumerableResources.isNotEmpty()
}

/**
 * Reason a secured operation was rejected.
 */
enum class ContextAccessDenialReason
{
    MISSING_PERMISSION,
    METADATA_UNAVAILABLE,
    INVALID_METADATA,
    UNSAFE_REFERENCE
}

/**
 * Typed denial raised by secured direct ContextBank APIs.
 *
 * The page key is available to trusted callers for diagnostics, but callers
 * should use [operation] and [resourceKind] as the stable contract.
 *
 * @param operation [ContextAccessOperation] that was denied.
 * @param resourceKind [ContextResourceKind] involved in the denied operation.
 * @param resourceKey ContextBank key involved in the denied operation.
 * @param reason [ContextAccessDenialReason] for the denial.
 */
class ContextAccessDeniedException(
    val operation: ContextAccessOperation,
    val resourceKind: ContextResourceKind,
    val resourceKey: String,
    val reason: ContextAccessDenialReason = ContextAccessDenialReason.MISSING_PERMISSION
): SecurityException(
    "Context access denied for ${operation.name.lowercase()} ${resourceKind.name.lowercase()} '$resourceKey'."
)

/**
 * Installs and propagates opt-in ContextBank authority.
 *
 * With no installed scope, TPipe preserves its historical direct ContextBank
 * behavior. A scope is a host-issued capability; nested installation always
 * intersects with the ambient scope, preventing accidental privilege
 * escalation. Coroutine installation uses a coroutine context element so the
 * scope follows dispatcher changes and is restored after the block.
 */
object ContextAccess
{
    private val scopeThreadLocal = ThreadLocal<ContextAccessScope>()

    /**
     * Create a trusted root scope.
     *
     * Hosts should keep this operation outside agent- or PCP-callable code and
     * expose only attenuated scopes to child executions.
     *
     * @param principal Opaque [ExecutionPrincipal] associated with the execution.
     * @param rights [ContextAccessRights] granted to the root scope.
     * @return A root scope using [ContextAccessEnforcementMode.LEGACY_COMPATIBLE].
     */
    fun issueRootScope(
        principal: ExecutionPrincipal,
        rights: ContextAccessRights
    ): ContextAccessScope
    {
        return issueRootScope(
            principal,
            rights,
            ContextAccessEnforcementMode.LEGACY_COMPATIBLE
        )
    }

    /**
     * Create a trusted root scope with an explicit enrollment policy.
     *
     * @param principal Opaque [ExecutionPrincipal] associated with the execution.
     * @param rights [ContextAccessRights] granted to the root scope.
     * @param enforcementMode [ContextAccessEnforcementMode] for resources without authorization metadata.
     * @return A root scope carrying the requested rights and enforcement mode.
     */
    fun issueRootScope(
        principal: ExecutionPrincipal,
        rights: ContextAccessRights,
        enforcementMode: ContextAccessEnforcementMode
    ): ContextAccessScope
    {
        return ContextAccessScope(principal, immutableRights(rights), enforcementMode)
    }

    /**
     * Intersect a scope with a requested set of rights.
     *
     * @param scope [ContextAccessScope] whose authority is being narrowed.
     * @param requested [ContextAccessRights] the derived scope may retain.
     * @return A scope containing only rights present in both inputs.
     */
    fun attenuate(
        scope: ContextAccessScope,
        requested: ContextAccessRights
    ): ContextAccessScope
    {
        return ContextAccessScope(
            scope.principal,
            intersectRights(scope.rights, requested),
            scope.enforcementMode
        )
    }

    /**
     * Attenuate selectors using explicit trusted containment resolution.
     * Unknown resources are omitted rather than granted.
     *
     * @param scope [ContextAccessScope] whose authority is being narrowed.
     * @param requested [ContextAccessRights] the derived scope may retain.
     * @param resolver Trusted [ContextResourceContainmentResolver] for boundary-to-resource containment.
     * @return A scope containing only exactly or safely contained selectors.
     */
    suspend fun attenuateForResources(
        scope: ContextAccessScope,
        requested: ContextAccessRights,
        resolver: ContextResourceContainmentResolver
    ): ContextAccessScope
    {
        return ContextAccessScope(
            scope.principal,
            intersectRightsForResources(scope.rights, requested, resolver),
            scope.enforcementMode
        )
    }

    /**
     * Install a child scope for a different execution principal.
     *
     * The ambient principal must explicitly possess delegation rights.
     *
     * @param principal Opaque [ExecutionPrincipal] assigned to the child execution.
     * @param requested [ContextAccessRights] delegated to the child.
     * @param block Code executed with the attenuated child scope.
     * @return The value returned by [block].
     */
    fun <T> withChildScope(
        principal: ExecutionPrincipal,
        requested: ContextAccessRights,
        block: () -> T
    ): T
    {
        val ambient = currentScope()
            ?: throw ContextAccessDeniedException(
                ContextAccessOperation.DELEGATE,
                ContextResourceKind.BANKED_CONTEXT,
                "<execution>",
                ContextAccessDenialReason.MISSING_PERMISSION
            )
        if(!ambient.canDelegate())
        {
            throw ContextAccessDeniedException(
                ContextAccessOperation.DELEGATE,
                ContextResourceKind.BANKED_CONTEXT,
                "<execution>",
                ContextAccessDenialReason.MISSING_PERMISSION
            )
        }

        return installScope(
            ContextAccessScope(
                principal,
                intersectRights(ambient.rights, requested),
                ambient.enforcementMode
            ),
            block
        )
    }

    /**
     * Install a scope for synchronous code. Nested scopes are intersected.
     *
     * @param scope [ContextAccessScope] to install or intersect with the ambient scope.
     * @param block Code executed with the effective scope.
     * @return The value returned by [block].
     */
    fun <T> withScope(scope: ContextAccessScope, block: () -> T): T
    {
        val effectiveScope = currentScope()?.let { ambient ->
            ContextAccessScope(
                ambient.principal,
                intersectRights(ambient.rights, scope.rights),
                stricterMode(ambient.enforcementMode, scope.enforcementMode)
            )
        } ?: scope
        return installScope(effectiveScope, block)
    }

    /**
     * Install a scope for coroutine code and dispatcher changes.
     *
     * @param scope [ContextAccessScope] to install or intersect with the ambient scope.
     * @param block Suspending code executed with the effective scope.
     * @return The value returned by [block].
     */
    suspend fun <T> withCoroutineScope(scope: ContextAccessScope, block: suspend () -> T): T
    {
        val effectiveScope = currentScope()?.let { ambient ->
            ContextAccessScope(
                ambient.principal,
                intersectRights(ambient.rights, scope.rights),
                stricterMode(ambient.enforcementMode, scope.enforcementMode)
            )
        } ?: scope
        return withContext(scopeThreadLocal.asContextElement(effectiveScope))
        {
            block()
        }
    }

    /**
     * Re-install the current scope at a local execution boundary.
     *
     * The operation does not create authority or serialize it. It gives
     * pipeline, manifold, and local P2P adapters an explicit propagation hook
     * while preserving legacy behavior when no scope is active.
     *
     * @param block Suspending code executed with the current scope.
     * @return The value returned by [block].
     */
    suspend fun <T> withCurrentScope(block: suspend () -> T): T
    {
        val scope = currentScope()
        return if(scope == null)
        {
            block()
        }
        else
        {
            withCoroutineScope(scope, block)
        }
    }

    /**
     * Install an attenuated child scope for coroutine code.
     *
     * @param principal Opaque [ExecutionPrincipal] assigned to the child execution.
     * @param requested [ContextAccessRights] delegated to the child.
     * @param block Suspending code executed with the attenuated child scope.
     * @return The value returned by [block].
     */
    suspend fun <T> withChildCoroutineScope(
        principal: ExecutionPrincipal,
        requested: ContextAccessRights,
        block: suspend () -> T
    ): T
    {
        val ambient = currentScope()
            ?: throw ContextAccessDeniedException(
                ContextAccessOperation.DELEGATE,
                ContextResourceKind.BANKED_CONTEXT,
                "<execution>",
                ContextAccessDenialReason.MISSING_PERMISSION
            )
        if(!ambient.canDelegate())
        {
            throw ContextAccessDeniedException(
                ContextAccessOperation.DELEGATE,
                ContextResourceKind.BANKED_CONTEXT,
                "<execution>",
                ContextAccessDenialReason.MISSING_PERMISSION
            )
        }

        return withContext(scopeThreadLocal.asContextElement(
            ContextAccessScope(
                principal,
                intersectRights(ambient.rights, requested),
                ambient.enforcementMode
            ),
        ))
        {
            block()
        }
    }

    /**
     * Install a delegated child scope using explicit containment resolution.
     *
     * @param principal Opaque [ExecutionPrincipal] assigned to the child execution.
     * @param requested [ContextAccessRights] delegated to the child.
     * @param resolver Trusted [ContextResourceContainmentResolver] for boundary-to-resource containment.
     * @param block Suspending code executed with the attenuated child scope.
     * @return The value returned by [block].
     */
    suspend fun <T> withChildCoroutineScopeForResources(
        principal: ExecutionPrincipal,
        requested: ContextAccessRights,
        resolver: ContextResourceContainmentResolver,
        block: suspend () -> T
    ): T
    {
        val ambient = currentScope()
            ?: throw ContextAccessDeniedException(
                ContextAccessOperation.DELEGATE,
                ContextResourceKind.BANKED_CONTEXT,
                "<execution>",
                ContextAccessDenialReason.MISSING_PERMISSION
            )
        if(!ambient.canDelegate())
        {
            throw ContextAccessDeniedException(
                ContextAccessOperation.DELEGATE,
                ContextResourceKind.BANKED_CONTEXT,
                "<execution>",
                ContextAccessDenialReason.MISSING_PERMISSION
            )
        }

        val childScope = attenuateForResources(
            ContextAccessScope(ambient.principal, ambient.rights, ambient.enforcementMode),
            requested,
            resolver
        )
        return withContext(scopeThreadLocal.asContextElement(
            ContextAccessScope(principal, childScope.rights, childScope.enforcementMode)
        ))
        {
            block()
        }
    }

    /**
     * Return the active scope, or null when TPipe is in legacy mode.
     *
     * @return The current coroutine-local scope, or `null` when none is active.
     */
    fun currentScope(): ContextAccessScope? = scopeThreadLocal.get()

    /**
     * Run a blocking compatibility wrapper while preserving the caller's
     * authority across the new coroutine created by [runBlocking].
     *
     * @param block Suspending code executed inside the compatibility wrapper.
     * @return The value returned by [block].
     */
    internal fun <T> runBlockingWithCurrentScope(block: suspend () -> T): T
    {
        val scope = currentScope()
        return runBlocking {
            if(scope == null) block() else withCoroutineScope(scope, block)
        }
    }

    private fun <T> installScope(scope: ContextAccessScope, block: () -> T): T
    {
        val previous = scopeThreadLocal.get()
        scopeThreadLocal.set(scope)
        try
        {
            return block()
        }
        finally
        {
            if(previous == null)
            {
                scopeThreadLocal.remove()
            }
            else
            {
                scopeThreadLocal.set(previous)
            }
        }
    }

    private fun intersectRights(
        ambient: ContextAccessRights,
        requested: ContextAccessRights
    ): ContextAccessRights
    {
        return ContextAccessRights(
            readableResources = ambient.readableResources intersect requested.readableResources,
            writableResources = ambient.writableResources intersect requested.writableResources,
            creatableResources = ambient.creatableResources intersect requested.creatableResources,
            deletableResources = ambient.deletableResources intersect requested.deletableResources,
            enumerableResources = ambient.enumerableResources intersect requested.enumerableResources,
            delegable = ambient.delegable && requested.delegable
        )
    }

    /**
     * Intersect rights while resolving boundary-to-resource containment.
     *
     * @param ambient Ambient rights that cannot be exceeded.
     * @param requested Requested rights to retain where safely contained.
     * @param resolver Trusted resolver for boundary membership.
     * @return Rights narrowed to exact matches and confirmed containment.
     */
    private suspend fun intersectRightsForResources(
        ambient: ContextAccessRights,
        requested: ContextAccessRights,
        resolver: ContextResourceContainmentResolver
    ): ContextAccessRights
    {
        return ContextAccessRights(
            readableResources = narrowSelectors(ambient.readableResources, requested.readableResources, resolver),
            writableResources = narrowSelectors(ambient.writableResources, requested.writableResources, resolver),
            creatableResources = narrowSelectors(ambient.creatableResources, requested.creatableResources, resolver),
            deletableResources = narrowSelectors(ambient.deletableResources, requested.deletableResources, resolver),
            enumerableResources = narrowSelectors(ambient.enumerableResources, requested.enumerableResources, resolver),
            delegable = ambient.delegable && requested.delegable
        )
    }

    /**
     * Retain requested selectors that are exact matches or safely contained.
     *
     * @param ambient Ambient selectors available to the parent scope.
     * @param requested Selectors requested by the child scope.
     * @param resolver Trusted resolver for boundary membership.
     * @return Requested selectors that remain within ambient authority.
     */
    private suspend fun narrowSelectors(
        ambient: Set<ContextResourceSelector>,
        requested: Set<ContextResourceSelector>,
        resolver: ContextResourceContainmentResolver
    ): Set<ContextResourceSelector>
    {
        return requested.filter { requestedSelector ->
            ambient.any { ambientSelector ->
                when
                {
                    ambientSelector == requestedSelector -> true
                    ambientSelector is ContextResourceSelector.Boundary &&
                        requestedSelector is ContextResourceSelector.Resource ->
                        resolveContainment(resolver, requestedSelector, ambientSelector)
                    else -> false
                }
            }
        }.toSet()
    }

    /**
     * Treat resolver outages as denied containment while preserving cancellation.
     *
     * @param resolver Trusted [ContextResourceContainmentResolver] for boundary-to-resource containment.
     * @param resource Exact [ContextResourceSelector.Resource] to resolve.
     * @param boundary Parent [ContextResourceSelector.Boundary] to check.
     * @return `true` only when the resolver confirms containment.
     */
    private suspend fun resolveContainment(
        resolver: ContextResourceContainmentResolver,
        resource: ContextResourceSelector.Resource,
        boundary: ContextResourceSelector.Boundary
    ): Boolean
    {
        return try
        {
            resolver.isResourceInBoundary(resource, boundary)
        }
        catch(exception: CancellationException)
        {
            throw exception
        }
        catch(_: Exception)
        {
            false
        }
    }

    /**
     * Select the stricter enrollment behavior for nested scopes.
     *
     * @param first First enforcement mode.
     * @param second Second enforcement mode.
     * @return [ContextAccessEnforcementMode.REQUIRE_ENROLLMENT] when either mode requires it.
     */
    private fun stricterMode(
        first: ContextAccessEnforcementMode,
        second: ContextAccessEnforcementMode
    ): ContextAccessEnforcementMode
    {
        return if(first == ContextAccessEnforcementMode.REQUIRE_ENROLLMENT ||
            second == ContextAccessEnforcementMode.REQUIRE_ENROLLMENT)
        {
            ContextAccessEnforcementMode.REQUIRE_ENROLLMENT
        }
        else
        {
            ContextAccessEnforcementMode.LEGACY_COMPATIBLE
        }
    }

    /**
     * Copy selector collections so a scope cannot be broadened through a caller's mutable set.
     *
     * @param rights Rights to copy.
     * @return An immutable-set snapshot of [rights].
     */
    private fun immutableRights(rights: ContextAccessRights): ContextAccessRights
    {
        return ContextAccessRights(
            readableResources = rights.readableResources.toSet(),
            writableResources = rights.writableResources.toSet(),
            creatableResources = rights.creatableResources.toSet(),
            deletableResources = rights.deletableResources.toSet(),
            enumerableResources = rights.enumerableResources.toSet(),
            delegable = rights.delegable
        )
    }
}
