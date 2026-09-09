package com.TTT.Context

import kotlinx.coroutines.asContextElement
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

/**
 * Opaque resource selectors used by an access scope.
 *
 * A resource selector is intentionally independent from a ContextBank page
 * key. Page keys are storage addresses; resource and boundary identifiers are
 * host-owned authorization identifiers persisted in the sidecar metadata.
 */
sealed interface ContextResourceSelector
{
    /** Select one exact opaque resource identifier. */
    data class Resource(val id: String) : ContextResourceSelector
    {
        init
        {
            require(id.isNotBlank()) { "Context resource selector id must not be blank." }
        }
    }

    /** Select resources enrolled in one opaque boundary/domain identifier. */
    data class Boundary(val id: String) : ContextResourceSelector
    {
        init
        {
            require(id.isNotBlank()) { "Context boundary selector id must not be blank." }
        }
    }
}

/**
 * Immutable rights supplied when a host creates or attenuates an access scope.
 * Empty sets mean that the corresponding operation is not granted.
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
 * intersection. They contain no provider or Apex-specific policy.
 */
class ContextAccessScope internal constructor(
    val principal: ExecutionPrincipal,
    internal val rights: ContextAccessRights
)
{
    /**
     * Narrow this scope without consulting ambient execution state.
     *
     * @param requested The maximum rights the derived scope may retain.
     * @return A scope with the intersection of both scopes' rights.
     */
    fun attenuate(requested: ContextAccessRights): ContextAccessScope
    {
        return ContextAccess.attenuate(this, requested)
    }

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

    internal fun canDelegate(): Boolean = rights.delegable

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
 */
class ContextAccessDeniedException(
    val operation: ContextAccessOperation,
    val resourceKind: ContextResourceKind,
    val resourceKey: String,
    val reason: ContextAccessDenialReason = ContextAccessDenialReason.MISSING_PERMISSION
) : SecurityException(
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
     */
    fun issueRootScope(
        principal: ExecutionPrincipal,
        rights: ContextAccessRights
    ): ContextAccessScope
    {
        return ContextAccessScope(principal, immutableRights(rights))
    }

    /**
     * Intersect a scope with a requested set of rights.
     */
    fun attenuate(
        scope: ContextAccessScope,
        requested: ContextAccessRights
    ): ContextAccessScope
    {
        return ContextAccessScope(
            scope.principal,
            intersectRights(scope.rights, requested)
        )
    }

    /**
     * Install a child scope for a different execution principal.
     *
     * The ambient principal must explicitly possess delegation rights.
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
            ContextAccessScope(principal, intersectRights(ambient.rights, requested)),
            block
        )
    }

    /**
     * Install a scope for synchronous code. Nested scopes are intersected.
     */
    fun <T> withScope(scope: ContextAccessScope, block: () -> T): T
    {
        val effectiveScope = currentScope()?.let { ambient ->
            ContextAccessScope(ambient.principal, intersectRights(ambient.rights, scope.rights))
        } ?: scope
        return installScope(effectiveScope, block)
    }

    /**
     * Install a scope for coroutine code and dispatcher changes.
     */
    suspend fun <T> withCoroutineScope(scope: ContextAccessScope, block: suspend () -> T): T
    {
        val effectiveScope = currentScope()?.let { ambient ->
            ContextAccessScope(ambient.principal, intersectRights(ambient.rights, scope.rights))
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
            ContextAccessScope(principal, intersectRights(ambient.rights, requested)),
        ))
        {
            block()
        }
    }

    /** Return the active scope, or null when TPipe is in legacy mode. */
    fun currentScope(): ContextAccessScope? = scopeThreadLocal.get()

    /**
     * Run a blocking compatibility wrapper while preserving the caller's
     * authority across the new coroutine created by [runBlocking].
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
