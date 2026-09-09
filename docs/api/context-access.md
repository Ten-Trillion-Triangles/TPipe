# Context Access API

## Table of Contents

- [Overview](#overview)
- [Resource Enrollment](#resource-enrollment)
- [Authorization Semantics](#authorization-semantics)
  - [Enforcement modes](#enforcement-modes)
  - [Selectors and boundaries](#selectors-and-boundaries)
  - [Scope attenuation](#scope-attenuation)
  - [Denial contract](#denial-contract)
- [Data Types](#data-types)
  - [ExecutionPrincipal](#executionprincipal)
  - [Resource kinds and operations](#resource-kinds-and-operations)
  - [Selectors and containment](#selectors-and-containment)
  - [ContextAccessRights](#contextaccessrights)
  - [ContextAccessScope](#contextaccessscope)
- [ContextAccess Functions](#contextaccess-functions)
  - [Root scope creation](#root-scope-creation)
  - [Scope installation](#scope-installation)
  - [Delegated child scopes](#delegated-child-scopes)
  - [Scope inspection](#scope-inspection)
- [ContextBank Integration](#contextbank-integration)
  - [Metadata administration](#metadata-administration)
  - [Enrolled resource creation](#enrolled-resource-creation)
  - [Protected direct operations](#protected-direct-operations)
  - [Enumeration and resource presence](#enumeration-and-resource-presence)
  - [Mutable reference protection](#mutable-reference-protection)
- [Secure Introspection Tools](#secure-introspection-tools)
  - [Secure tool functions](#secure-tool-functions)
  - [PCP registration](#pcp-registration)
- [Remote Metadata Backend](#remote-metadata-backend)
  - [Backend capability](#backend-capability)
  - [HTTP endpoints](#http-endpoints)
  - [MemoryClient methods](#memoryclient-methods)
- [Examples](#examples)
  - [Create and read an enrolled context window](#create-and-read-an-enrolled-context-window)
  - [Delegate a contained resource](#delegate-a-contained-resource)
  - [Register secure PCP tools](#register-secure-pcp-tools)
- [Error Reference](#error-reference)
- [See Also](#see-also)

## Overview

`ContextAccess` is the provider-neutral authority layer for `ContextBank` resources. It protects context windows, todo lists, and the active banked context through an execution-local scope. The stored `ContextWindow` and `TodoList` payloads remain unchanged. Authorization identity is stored separately in versioned metadata sidecars. See [Resource Enrollment](#resource-enrollment).

The implementation is in `src/main/kotlin/Context/ContextAccess.kt:26-718`, and `ContextBank` enforcement is in `src/main/kotlin/Context/ContextBank.kt:155-833`.

With no active scope, direct `ContextBank` calls retain their historical behavior. A scope changes the behavior of calls made inside that scope only. Nested scopes are narrowed by intersection. Coroutine scopes follow dispatcher changes through the coroutine context.

## Resource Enrollment

Enroll a resource by associating its storage key with `ContextResourceMetadata`. The storage key remains an address. The opaque `resourceId` and optional `boundaryId` determine authorization matches.

```kotlin
ContextBank.registerResourceMetadata(
    "private-page",
    ContextResourceMetadata(
        resourceKind = ContextResourceKind.CONTEXT_WINDOW,
        resourceId = "resource-17",
        boundaryId = "workspace-3"
    )
)
```

`registerResourceMetadata` and `registerResourceMetadataSuspend` are trusted-host operations. They must run outside an active `ContextAccess` scope. Removing metadata with `deleteResourceMetadata` or `deleteResourceMetadataSuspend` returns the resource to legacy-shared behavior. See `src/main/kotlin/Context/ContextBank.kt:2490-2647`.

Local metadata is stored beside the payload:

| Resource kind | Payload path | Metadata path |
|---|---|---|
| `CONTEXT_WINDOW` | `<lorebook-directory>/<key>.bank` | `<lorebook-directory>/<key>.bank.access` |
| `TODO_LIST` | `<todo-list-directory>/<key>.todo` | `<todo-list-directory>/<key>.todo.access` |
| `BANKED_CONTEXT` | `<lorebook-directory>/__banked_context.bank` | `<lorebook-directory>/__banked_context.bank.access` |

The sidecar schema currently uses `schemaVersion = 1`. Strict decoding requires `schemaVersion`, `resourceKind`, and `resourceId`; unknown schema versions, malformed records, and kind mismatches fail closed. See `src/main/kotlin/Context/ContextResourceMetadata.kt:18-180`.

## Authorization Semantics

### Enforcement modes

`ContextAccess.issueRootScope` has two overloads:

| Root-scope call | Mode | Missing metadata |
|---|---|---|
| `issueRootScope(principal, rights)` | `LEGACY_COMPATIBLE` | The resource keeps historical shared access behavior. |
| `issueRootScope(principal, rights, REQUIRE_ENROLLMENT)` | `REQUIRE_ENROLLMENT` | The secured operation is denied with `METADATA_UNAVAILABLE`. |

A nested scope uses `REQUIRE_ENROLLMENT` when either the ambient or requested scope uses that mode. This prevents a nested scope from weakening enrollment requirements. See `src/main/kotlin/Context/ContextAccess.kt:264-301` and `src/main/kotlin/Context/ContextAccess.kt:678-699`.

### Selectors and boundaries

A scope grants selectors for each operation independently:

| Operation | Rights property | Meaning |
|---|---|---|
| `READ` | `readableResources` | Retrieve a protected resource. |
| `WRITE` | `writableResources` | Update an existing resource. |
| `CREATE` | `creatableResources` | Create and enroll a new resource. |
| `DELETE` | `deletableResources` | Delete a resource or perform a delete-equivalent operation. |
| `ENUMERATE` | `enumerableResources` | List keys visible to the execution. |
| `DELEGATE` | `delegable` | Issue an attenuated child scope. |

A `Resource("resource-17")` selector matches only metadata with that exact `resourceId`. A `Boundary("workspace-3")` selector matches metadata with that exact `boundaryId`. Page keys do not match selectors. See `src/main/kotlin/Context/ContextAccess.kt:56-109` and `src/main/kotlin/Context/ContextAccess.kt:181-217`.

### Scope attenuation

`ContextAccess.attenuate` and `ContextAccessScope.attenuate` intersect every selector set and the `delegable` flag. They preserve the original principal and enforcement mode. A caller cannot broaden a scope through attenuation.

`attenuateForResources` also permits a requested exact resource when a trusted resolver confirms that the resource belongs to an ambient boundary. Exact selector equality remains valid. Unknown resources and resolver failures are omitted. Cancellation is propagated. See `src/main/kotlin/Context/ContextAccess.kt:303-342` and `src/main/kotlin/Context/ContextAccess.kt:598-675`.

### Denial contract

A protected direct `ContextBank` operation throws `ContextAccessDeniedException` before the protected value, retrieval callback, write-back callback, or persistence backend is invoked. The exception exposes:

| Property | Type | Meaning |
|---|---|---|
| `operation` | `ContextAccessOperation` | Operation that failed. |
| `resourceKind` | `ContextResourceKind` | Resource type involved. |
| `resourceKey` | `String` | ContextBank storage key used for diagnostics. |
| `reason` | `ContextAccessDenialReason` | `MISSING_PERMISSION`, `METADATA_UNAVAILABLE`, `INVALID_METADATA`, or `UNSAFE_REFERENCE`. |

The exception is a `SecurityException`. See `src/main/kotlin/Context/ContextAccess.kt:220-249`.

## Data Types

### ExecutionPrincipal

```kotlin
data class ExecutionPrincipal(val id: String)
```

`id` is an opaque host-defined identity. A blank identifier throws `IllegalArgumentException`. TPipe does not assign provider, workspace, session, or user semantics to this value. See `src/main/kotlin/Context/ContextAccess.kt:9-24`.

### Resource kinds and operations

```kotlin
enum class ContextResourceKind {
    CONTEXT_WINDOW,
    TODO_LIST,
    BANKED_CONTEXT
}

enum class ContextAccessOperation {
    READ,
    WRITE,
    CREATE,
    DELETE,
    ENUMERATE,
    DELEGATE
}

enum class ContextAccessEnforcementMode {
    LEGACY_COMPATIBLE,
    REQUIRE_ENROLLMENT
}
```

`ContextResourceKind` identifies the protected value. `ContextAccessOperation` identifies the requested authority. `ContextAccessEnforcementMode` controls how an active scope treats a missing metadata sidecar.

### Selectors and containment

```kotlin
sealed interface ContextResourceSelector {
    data class Resource(val id: String) : ContextResourceSelector
    data class Boundary(val id: String) : ContextResourceSelector
}

fun interface ContextResourceContainmentResolver {
    suspend fun isResourceInBoundary(
        resource: ContextResourceSelector.Resource,
        boundary: ContextResourceSelector.Boundary
    ): Boolean
}
```

Selector identifiers must be non-blank. A containment resolver returns membership without returning the protected resource value. Use it only with trusted metadata access. See `src/main/kotlin/Context/ContextAccess.kt:56-109`.

### ContextAccessRights

```kotlin
data class ContextAccessRights(
    val readableResources: Set<ContextResourceSelector> = emptySet(),
    val writableResources: Set<ContextResourceSelector> = emptySet(),
    val creatableResources: Set<ContextResourceSelector> = emptySet(),
    val deletableResources: Set<ContextResourceSelector> = emptySet(),
    val enumerableResources: Set<ContextResourceSelector> = emptySet(),
    val delegable: Boolean = false
)
```

An empty selector set grants no authority for that operation. `delegable` controls whether the scope may create a child scope. See `src/main/kotlin/Context/ContextAccess.kt:111-129`.

### ContextAccessScope

`ContextAccessScope` is created by `ContextAccess.issueRootScope` and is narrowed through `attenuate` or `attenuateForResources`. Its public properties are `principal` and `enforcementMode`. The rights snapshot is internal.

```kotlin
fun attenuate(requested: ContextAccessRights): ContextAccessScope

suspend fun attenuateForResources(
    requested: ContextAccessRights,
    resolver: ContextResourceContainmentResolver
): ContextAccessScope
```

See `src/main/kotlin/Context/ContextAccess.kt:131-217`.

## ContextAccess Functions

### Root scope creation

```kotlin
fun issueRootScope(
    principal: ExecutionPrincipal,
    rights: ContextAccessRights
): ContextAccessScope

fun issueRootScope(
    principal: ExecutionPrincipal,
    rights: ContextAccessRights,
    enforcementMode: ContextAccessEnforcementMode
): ContextAccessScope
```

Create root scopes in trusted host code. The two-argument overload uses `LEGACY_COMPATIBLE`. Select `REQUIRE_ENROLLMENT` when every secured operation must have valid metadata. See `src/main/kotlin/Context/ContextAccess.kt:264-301`.

### Scope installation

```kotlin
fun <T> withScope(
    scope: ContextAccessScope,
    block: () -> T
): T

suspend fun <T> withCoroutineScope(
    scope: ContextAccessScope,
    block: suspend () -> T
): T

suspend fun <T> withCurrentScope(
    block: suspend () -> T
): T
```

`withScope` uses a thread-local for synchronous code. `withCoroutineScope` installs a coroutine context element and restores the previous scope after the block. When a scope is already active, the effective principal remains the ambient principal, rights are intersected, and the stricter enforcement mode wins. `withCurrentScope` preserves the current scope across an explicit coroutine boundary and executes directly when no scope is active. See `src/main/kotlin/Context/ContextAccess.kt:387-449`.

### Delegated child scopes

```kotlin
fun <T> withChildScope(
    principal: ExecutionPrincipal,
    requested: ContextAccessRights,
    block: () -> T
): T

suspend fun <T> withChildCoroutineScope(
    principal: ExecutionPrincipal,
    requested: ContextAccessRights,
    block: suspend () -> T
): T

suspend fun <T> withChildCoroutineScopeForResources(
    principal: ExecutionPrincipal,
    requested: ContextAccessRights,
    resolver: ContextResourceContainmentResolver,
    block: suspend () -> T
): T
```

A child scope requires an active ambient scope with `delegable = true`. The child receives the requested principal and an intersection of ambient and requested rights. The ambient enforcement mode remains active. Without an ambient delegable scope, these functions throw `ContextAccessDeniedException` with `operation = DELEGATE`, `resourceKind = BANKED_CONTEXT`, `resourceKey = "<execution>"`, and `reason = MISSING_PERMISSION`. See `src/main/kotlin/Context/ContextAccess.kt:344-538`.

### Scope inspection

```kotlin
fun currentScope(): ContextAccessScope?
```

`currentScope` returns the active scope or `null` in legacy mode. `runBlockingWithCurrentScope` is an internal compatibility helper used by synchronous `ContextBank` methods. See `src/main/kotlin/Context/ContextAccess.kt:540-559`.

## ContextBank Integration

### Metadata administration

| Function | Result | Contract |
|---|---|---|
| `registerResourceMetadata(key, metadata)` | `Unit` | Trusted synchronous registration. |
| `registerResourceMetadataSuspend(key, metadata)` | `Unit` | Trusted suspending registration. |
| `deleteResourceMetadata(key, kind)` | `Boolean` | `true` when a sidecar was deleted. |
| `deleteResourceMetadataSuspend(key, kind)` | `Boolean` | Suspend-safe equivalent. |
| `getResourceMetadataSuspend(key, kind)` | `ContextResourceMetadata?` | Returns metadata without exposing the protected value. |

Metadata administration requires no active scope. A remote storage mode requires a `ContextResourceMetadataBackend`; otherwise registration and deletion fail with `IllegalStateException`. See `src/main/kotlin/Context/ContextBank.kt:2490-2589`.

### Enrolled resource creation

Use the creation functions when creating a resource that must be enrolled atomically with its metadata:

```kotlin
fun createContextWindow(
    key: String,
    metadata: ContextResourceMetadata,
    window: ContextWindow,
    mode: StorageMode = StorageMode.MEMORY_ONLY,
    skipRemote: Boolean = false
)

suspend fun createContextWindowSuspend(
    key: String,
    metadata: ContextResourceMetadata,
    window: ContextWindow,
    mode: StorageMode = StorageMode.MEMORY_ONLY,
    skipRemote: Boolean = false
)

fun createTodoList(
    key: String,
    metadata: ContextResourceMetadata,
    todoList: TodoList,
    mode: StorageMode = StorageMode.MEMORY_ONLY,
    skipRemote: Boolean = false
)

suspend fun createTodoListSuspend(
    key: String,
    metadata: ContextResourceMetadata,
    todoList: TodoList,
    mode: StorageMode = StorageMode.MEMORY_ONLY,
    skipRemote: Boolean = false
)
```

Creation validates the resource kind, non-blank key, and current metadata schema. It rejects an existing payload or sidecar. Inside a scope, the caller must have `CREATE` authority for the proposed metadata. A scoped caller receives a defensive copy of the supplied mutable payload before storage. Remote creation requires the metadata backend capability and removes the metadata sidecar if payload persistence fails. See `src/main/kotlin/Context/ContextBank.kt:1384-1480` and `src/main/kotlin/Context/ContextBank.kt:2894-2986`.

### Protected direct operations

The following `ContextBank` paths consult the active scope before accessing the underlying value or backend:

| Operation | Representative APIs | Enforcement |
|---|---|---|
| Read | `getContextFromBank*`, `getPagedTodoList*`, banked-context reads | Requires `READ` for enrolled metadata. |
| Write | `emplace*`, mutations, `updateBankedContext*`, `swapBank*` | Existing resources require `WRITE`; creation requires `CREATE`. |
| Delete | delete and eviction paths | Requires `DELETE`. |
| Enumeration | `getPageKeys*`, todo-key listing, banked-key listing | Requires `ENUMERATE`; returned keys are filtered to visible resources. |
| Retrieval/write-back | retrieval functions, write-back functions, remote persistence | Authorization occurs before the callback or backend call. |

Legacy `MemoryIntrospectionTools` methods retain safe-result contracts. Secure callers can use `SecureMemoryIntrospectionTools` to receive typed denials. See [Secure Introspection Tools](#secure-introspection-tools) and `src/main/kotlin/Context/MemoryIntrospectionTools.kt:450-476`.

### Enumeration and resource presence

Enumeration filters keys after checking resource metadata. In `LEGACY_COMPATIBLE` mode, resources without a sidecar remain visible. In `REQUIRE_ENROLLMENT` mode, a missing sidecar is denied or excluded. Remote enumeration checks the metadata capability before requesting remote keys. Resource-presence checks use metadata-plane operations and do not retrieve protected values. See `src/main/kotlin/Context/ContextBank.kt:713-834`.

### Mutable reference protection

When a scope is active, APIs that would retain a mutable `ContextWindow` reference are denied with `reason = UNSAFE_REFERENCE`:

- `getContextFromBank(key, copy = false)` and suspend or mutex variants.
- `getPagedTodoList(key, copy = false)` and suspend or mutex variants.
- `getBankedContextWindowSuspend(copy = false)`.
- `getBankedContextWindowReference()`.
- `withContextWindowReferenceSuspend(...)`.

Copy-returning reads remain available. See `src/main/kotlin/Context/ContextBank.kt:1191-1220`, `src/main/kotlin/Context/ContextBank.kt:2186-2262`, and `src/main/kotlin/Context/ContextBank.kt:2678-2693`.

## Secure Introspection Tools

`SecureMemoryIntrospectionTools` provides PCP-facing functions with explicit `secure_` registrations and typed `ContextAccessDeniedException` failures. The original `MemoryIntrospectionTools` names remain source- and contract-compatible. See `src/main/kotlin/Context/SecureMemoryIntrospectionTools.kt:9-333`.

### Secure tool functions

| Function | Parameters | Return |
|---|---|---|
| `listPageKeys()` | none | `List<String>` |
| `getLorebookEntry(pageKey, key)` | page key, lorebook key | `LoreBook?` |
| `getLorebook(pageKey)` | page key | `Map<String, LoreBook>` |
| `queryLorebook(pageKey, query, minWeight, requiredKeys, aliasKeys, extractRegex)` | page key and query filters | `List<LoreBookQueryResult>` |
| `simulateLorebookTrigger(pageKey, text)` | page key, input text | `List<String>` |
| `searchMemory(pageKey, query, extractRegex)` | page key, search text, optional regex | `MemorySearchResult` |
| `updateLorebookEntry(pageKey, entry)` | page key, replacement entry | `Boolean` |
| `deleteLorebookEntry(pageKey, key)` | page key, lorebook key | `Boolean` |
| `getTodoList(pageKey)` | todo-list key | `TodoList?` |
| `updateTodoList(pageKey, todoList)` | todo-list key, replacement list | `Boolean` |

Read functions perform `READ` checks. Lorebook and todo-list updates perform `WRITE` checks. `listPageKeys` performs `ENUMERATE` and filters out pages hidden by `MemoryIntrospection` or `ContextLock`. See `src/main/kotlin/Context/SecureMemoryIntrospectionTools.kt:19-180`.

### PCP registration

```kotlin
SecureMemoryIntrospectionTools.registerAndEnable(pcpContext)
```

The function registers these PCP names with `FunctionRegistry`:

```text
secure_listPageKeys
secure_getLorebookEntry
secure_getLorebook
secure_queryLorebook
secure_simulateLorebookTrigger
secure_searchMemory
secure_updateLorebookEntry
secure_deleteLorebookEntry
secure_getTodoList
secure_updateTodoList
```

It adds a PCP option only when the context does not already define that function name. See `src/main/kotlin/Context/SecureMemoryIntrospectionTools.kt:182-333`.

## Remote Metadata Backend

### Backend capability

`ContextResourceMetadataBackend` is an optional capability separate from the provider-neutral persistence contract. A backend can implement this capability without changing `ContextPersistenceBackend`.

```kotlin
interface ContextResourceMetadataBackend {
    suspend fun getResourceMetadata(
        kind: ContextResourceKind,
        key: String
    ): ContextResourceMetadata?

    suspend fun putResourceMetadata(
        kind: ContextResourceKind,
        key: String,
        metadata: ContextResourceMetadata
    )

    suspend fun deleteResourceMetadata(
        kind: ContextResourceKind,
        key: String
    ): Boolean

    suspend fun resourceExists(
        kind: ContextResourceKind,
        key: String
    ): Boolean?
}
```

The interface is defined in `src/main/kotlin/Context/Persistence/ContextResourceMetadataBackend.kt:6-54`. A backend returns `null` for missing metadata and may return `null` from `resourceExists` when payload presence cannot be determined. Remote secured creation and enumeration require this capability.

### HTTP endpoints

`MemoryServer.configureMemoryRouting` exposes these endpoints under `/context`. When `P2PRegistry.globalAuthMechanism` is configured, the `/context` interceptor checks the `Authorization` header before dispatching any endpoint. Unauthorized requests return HTTP `401` with `MemoryErrorType.auth`. See `src/main/kotlin/Context/MemoryServer.kt:15-45`.

The `{kind}` path value is parsed case-insensitively as `ContextResourceKind`.

#### `GET /context/metadata/{kind}/{key}`

Returns the enrolled metadata document. A missing sidecar returns HTTP `404` with `MemoryErrorType.notFound`. The local metadata reader rejects malformed, unsupported, or kind-mismatched sidecars with `ContextAccessDeniedException` and `reason = INVALID_METADATA`.

Example response:

```json
{
  "resourceKind": "CONTEXT_WINDOW",
  "resourceId": "resource-17",
  "boundaryId": "workspace-3",
  "schemaVersion": 1
}
```

#### `POST /context/metadata/{kind}/{key}`

Stores or replaces metadata. The request body is a serialized `ContextResourceMetadata`. The body `resourceKind` must match `{kind}`. Success returns HTTP `200` and the stored metadata. A malformed body or kind mismatch returns HTTP `400` with `MemoryErrorType.serialization` or `MemoryErrorType.badRequest`.

Example request body:

```json
{
  "resourceKind": "CONTEXT_WINDOW",
  "resourceId": "resource-17",
  "boundaryId": "workspace-3",
  "schemaVersion": 1
}
```

#### `DELETE /context/metadata/{kind}/{key}`

Deletes the metadata sidecar. Success returns HTTP `204`. A missing sidecar returns HTTP `404` with `MemoryErrorType.notFound`.

#### `GET /context/metadata/{kind}/{key}/exists`

Checks payload presence without retrieving the payload. Success returns HTTP `200` with a JSON boolean:

```json
true
```

Metadata-route bad requests return HTTP `400` with `MemoryErrorType.badRequest`. See `src/main/kotlin/Context/MemoryServer.kt:294-398`.

### MemoryClient methods

| Method | Return | Endpoint |
|---|---|---|
| `getResourceMetadata(kind, key)` | `MemoryOperationResult<ContextResourceMetadata>` | `GET /context/metadata/{kind}/{key}` |
| `putResourceMetadata(kind, key, metadata)` | `MemoryOperationResult<Unit>` | `POST /context/metadata/{kind}/{key}` |
| `deleteResourceMetadata(kind, key)` | `MemoryOperationResult<Unit>` | `DELETE /context/metadata/{kind}/{key}` |
| `resourceExists(kind, key)` | `MemoryOperationResult<Boolean>` | `GET /context/metadata/{kind}/{key}/exists` |

`MemoryOperationResult.Success` contains the parsed value. `MemoryOperationResult.Failure` contains the HTTP status and `MemoryErrorResponse`. `requireValue` and `requireSuccess` convert failures to `MemoryRemoteException`. See `src/main/kotlin/Context/MemoryClient.kt:293-380` and `src/main/kotlin/Context/MemoryTypes.kt:34-103`.

## Examples

### Create and read an enrolled context window

Partial example. Requires an unused key and a configured TPipe storage directory.

```kotlin
suspend fun readPrivatePage(): List<String>
{
    val metadata = ContextResourceMetadata(
        resourceKind = ContextResourceKind.CONTEXT_WINDOW,
        resourceId = "resource-17",
        boundaryId = "workspace-3"
    )
    val rights = ContextAccessRights(
        readableResources = setOf(ContextResourceSelector.Resource("resource-17")),
        creatableResources = setOf(ContextResourceSelector.Resource("resource-17"))
    )
    val scope = ContextAccess.issueRootScope(
        ExecutionPrincipal("agent-1"),
        rights,
        ContextAccessEnforcementMode.REQUIRE_ENROLLMENT
    )

    return ContextAccess.withCoroutineScope(scope) {
        val window = ContextWindow().apply { addText("private value") }
        ContextBank.createContextWindowSuspend("private-page", metadata, window)
        ContextBank.getContextFromBankSuspend("private-page").contextElements
    }
}
```

Expected output:

```text
[private value]
```

The creation call requires `CREATE`. The subsequent read requires `READ`. The stored payload is copied while a scope is active. Source: `src/main/kotlin/Context/ContextBank.kt:1426-1430`.

### Delegate a contained resource

Partial example. Requires an ambient root scope with the boundary selector and `delegable = true`, plus a trusted containment resolver.

```kotlin
suspend fun readDelegatedPage(
    resolver: ContextResourceContainmentResolver
): List<String>
{
    val root = ContextAccess.issueRootScope(
        ExecutionPrincipal("manager-1"),
        ContextAccessRights(
            readableResources = setOf(ContextResourceSelector.Boundary("workspace-3")),
            delegable = true
        ),
        ContextAccessEnforcementMode.REQUIRE_ENROLLMENT
    )

    return ContextAccess.withCoroutineScope(root) {
        ContextAccess.withChildCoroutineScopeForResources(
            ExecutionPrincipal("worker-1"),
            ContextAccessRights(
                readableResources = setOf(ContextResourceSelector.Resource("resource-17"))
            ),
            resolver
        ) {
            ContextBank.getContextFromBankSuspend("private-page").contextElements
        }
    }
}
```

Expected output when the resolver confirms containment:

```text
[private value]
```

When the resolver returns `false` or throws a non-cancellation exception, the requested resource selector is omitted and the read is denied with `MISSING_PERMISSION`. Source: `src/main/kotlin/Context/ContextAccess.kt:503-538` and `src/main/kotlin/Context/ContextAccess.kt:650-675`.

### Register secure PCP tools

Partial example. Requires a `PcpContext` instance.

```kotlin
SecureMemoryIntrospectionTools.registerAndEnable(pcpContext)
```

Expected result: the ten `secure_` function names listed in [PCP registration](#pcp-registration) are available through the function registry and PCP options. A denied protected-resource call raises `ContextAccessDeniedException`; the legacy tool names retain their historical safe-result behavior.

## Error Reference

| Error | Condition | Resolution |
|---|---|---|
| `ContextAccessDeniedException` with `MISSING_PERMISSION` | The active scope lacks the operation or selector. | Grant the required operation to the intended resource, or attenuate the request to an authorized selector. |
| `ContextAccessDeniedException` with `METADATA_UNAVAILABLE` | Strict enrollment requires metadata, or a remote backend lacks metadata capability. | Enroll the resource or install a `ContextResourceMetadataBackend`. |
| `ContextAccessDeniedException` with `INVALID_METADATA` | A sidecar is malformed, unsupported, or mismatched with the requested kind. | Replace the sidecar with schema version `1` metadata. |
| `ContextAccessDeniedException` with `UNSAFE_REFERENCE` | A secured caller requested a retained mutable reference. | Use a copy-returning API or perform a scoped mutation through the supported API. |
| `IllegalStateException` | Creation targets an existing payload or sidecar, or trusted metadata administration is attempted inside a scope. | Use a new key, or perform metadata administration outside `ContextAccess`. |
| `MemoryRemoteException` | A `MemoryClient` operation returns a typed remote-memory failure and the caller invokes `requireValue` or `requireSuccess`. | Inspect `failure.statusCode` and `failure.error`, then correct authentication, metadata, payload, or transport state. |

## See Also

- [ContextBank API](context-bank.md) - ContextBank storage and mutation methods.
- [Memory Introspection](../advanced-concepts/memory-introspection.md) - Agent-facing memory inspection model.
- [Remote Memory](../advanced-concepts/remote-memory.md) - Remote memory hosting and client configuration.
- [ContextLock API](context-lock.md) - Independent page and key locking.
- [Pipe Context Protocol](../advanced-concepts/pipe-context-protocol.md) - PCP tool execution.
