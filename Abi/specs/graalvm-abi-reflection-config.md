# GraalVM Native Image — Reflection & Resource Configuration Specification

**Spec File:** graalvm-abi-reflection-config.md
**Version:** 0.1.0-draft
**Created:** 2026-05-09
**Status:** Draft — Based on hyperplan adversarial reflection analysis + source code audit (2026-05-09)

---

## 1. Overview

### 1.1 Why This Document Exists

When TPipe is compiled to a GraalVM native image, the JVM's dynamic classpath is replaced by a **static analysis** process that determines which code and resources are reachable at runtime. Anything the analyzer cannot see — because it is loaded by string name, generated at runtime, or accessed via reflection — must be explicitly registered in configuration files. Without this registration, the native binary silently excludes the inaccessible code/resources, causing runtime failures that have no JVM equivalent.

The goal of this document is **equal feature parity**: a developer using the native wrapper must observe no functional difference from the JVM wrapper.

### 1.2 Two Configuration Files

| File | Purpose | Governs |
|------|---------|---------|
| `reflect-config.json` | Registers reflective access to classes, methods, fields, constructors | Reflection operations (Class.forName, getDeclaredField, Method.invoke, etc.) |
| `resource-config.json` | Registers bundled resource files (configs, scripts, dictionaries) | File reads via getResourceAsStream, ServiceLoader SPI files |

Both files live at `src/main/resources/META-INF/native-image/`.

### 1.3 GraalVM 21+ Note

GraalVM 21+ changed proxy handling — the `--dynamic-proxy` flag is no longer required. Interface-based proxies are handled automatically by the compiler. However, **explicit reflect-config registration of proxy interfaces is still required** for the implementing classes.

---

## 2. reflect-config.json — Dynamic Class Loading

### 2.1 Class.forName Call Sites — ManifoldDefaults.kt

**Risk:** `Class.forName(string)` is invisible to static analysis. GraalVM cannot determine which class will be requested, so it excludes all candidates from the native image.

**Found at:** `TPipe/TPipe-Defaults/src/main/kotlin/Defaults/ManifoldDefaults.kt` lines 120, 124, 128

**Audit Result (2026-05-09):** Verified via source code traversal. Three provider classes are loaded via `Class.forName` with string literals:

```kotlin
// Line 120
Class.forName("bedrockPipe.BedrockPipe").getDeclaredConstructor().newInstance()
// Line 124
Class.forName("ollamaPipe.OllamaPipe").getDeclaredConstructor().newInstance()
// Line 128
Class.forName("openrouterPipe.OpenRouterPipe").getDeclaredConstructor().newInstance()
```

> Note: The spec previously referenced `bedrockPipe.BedrockMultimodalPipe` — the actual class name is `bedrockPipe.BedrockPipe`. This has been corrected.

**Required reflect-config entries:**

```json
[
  {
    "name": "bedrockPipe.BedrockPipe",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "ollamaPipe.OllamaPipe",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "openrouterPipe.OpenRouterPipe",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  }
]
```

**Verification:** In native image, `Class.forName("bedrockPipe.BedrockPipe")` must return a non-null Class and `newInstance()` must succeed. Without registration, `Class.forName` throws `ClassNotFoundException` silently in the native image (no exception on JVM — the class is on the classpath).

> **Action:** The TPipe-Defaults module must be included in the native image compilation for these classes to be reachable. If TPipe-Defaults is a separate Gradle module, ensure it is on the native-image classpath.

---

### 2.2 Constructor.newInstance — Util.kt

**Risk:** `Constructor.newInstance()` on classes passed to `templateBasedReconstruction<T>()` and similar template utilities. The class is passed as a type parameter, so GraalVM's static analysis may not see the concrete constructor calls at the call sites.

**Found at:** `Util/Util.kt` lines 446, 613, 704, 775, 882 — `templateBasedReconstruction<T>()`, `cloneInstance<T>()`, and similar template-based object factories.

**Audit Result (2026-05-09):** Full call site audit completed. Most patterns use reified type parameters where the concrete class is only known at runtime. Only one concrete type is statically determinable:

| Line | Function | Pattern | Type T Resolution |
|------|----------|---------|-------------------|
| 446 | `templateBasedReconstruction<T>()` | `T::class.java.getDeclaredConstructor().newInstance()` | Dynamic — call-site inference |
| 613 | `cloneInstance<T>()` | `template::class.java.getDeclaredConstructor().newInstance()` | Dynamic — runtime |
| 704 | `isDefault()` | `kClass.java.getDeclaredConstructor().newInstance()` | Dynamic — runtime |
| 775 | `cloneValue()` internal | `value::class.java.getDeclaredConstructor()` | Dynamic — runtime |
| 882 | `constructPipeFromTemplate<T>()` | `kClass.java.getDeclaredConstructor().newInstance()` | **Concrete: `com.TTT.Pipeline.Pipe`** (line 1471) |

**Statically-known types requiring reflect-config entries:**

```json
[
  {
    "name": "com.TTT.Pipeline.Pipe",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  }
]
```

**Dynamic types:** For the runtime-dynamic patterns (lines 446, 613, 704, 775), the concrete type depends on actual data flow at runtime. These cannot be enumerated statically. **Recommended approach:** Use `native-image-agent` to trace runtime usage and auto-generate entries, or register the common base types that appear in practice. The annotation `@DynamiclyReachable` approach from GraalVM 22+ may also help.

> **Action required:** For lines 446, 613, 704, 775 — use `native-image-agent` tracing during integration testing to capture all dynamically-instantiated types. Do not attempt to enumerate statically.

---

## 3. reflect-config.json — Field Access

### 3.1 getDeclaredField("pcpContext") — PcpFunctionExtensions.kt

**Risk:** Access to a `protected var` on `Pipe` via `getDeclaredField("pcpContext")` + `setAccessible(true)`. Requires **both read AND write** registration since it's a `var`.

**Found at:** `PipeContextProtocol/PcpFunctionExtensions.kt` line 102

```kotlin
val field = receiverClass.getDeclaredField("pcpContext")
field.isAccessible = true
val value = field.get(receiver)      // READ — required
field.set(receiver, newValue)       // WRITE — required (it's a var)
```

**Audit Result (2026-05-09):** Verified. The receiver class is `com.TTT.Pipe.Pipe`. The field `pcpContext` is declared at `Pipe.kt:701` (line 701, `protected var pcpContext`).

**Required entry:**

```json
{
  "name": "com.TTT.Pipe.Pipe",
  "allDeclaredFields": true,
  "fields": [
    { "name": "pcpContext", "allowWrite": true }
  ]
}
```

**Also note:** The inheritance chain must be covered — if `pcpContext` is defined in a superclass, both the superclass and the concrete class need field entries. In this case `pcpContext` is on `Pipe` directly (not inherited), so only `com.TTT.Pipe.Pipe` needs registration.

---

## 4. reflect-config.json — Lambda SAM Interfaces

### 4.1 Kotlin Lambda → SAM Conversion (DSL Builder Hazard)

**Risk:** When a developer passes a Kotlin lambda to a TPipe DSL entry point (streaming callback, pipeline finish handler, connector routing lambda), Kotlin generates a non-deterministic anonymous class at runtime. GraalVM's static analyzer cannot predict the class name, so the `invoke` method is not registered. First call throws `NoSuchMethodError`.

**This is the most common way native images break silently** — works perfectly on JVM, fails on native with no error message until the first actual call.

### 4.2 TPipe DSL Entry Points Accepting Lambdas

**Audit Result (2026-05-09):** All lambdas in TPipe's public API are passed as **inline suspend function types**, not as named SAM interfaces. This means there are no stable named functional interface FQCNs to register — the lambda type is anonymous at each call site.

The DSL entry points that accept lambdas:

**Pipeline.kt callbacks:**
- `setPreValidationFunction((suspend (ContextWindow, MiniBank, MultimodalContent) -> Unit)?)` — line 573
- `enablePipeTimeout(customLogic: (suspend (Pipe, MultimodalContent) -> Boolean)?)` — line 594
- `pauseWhen((suspend (Pipe, MultimodalContent) -> Boolean)?)` — line 893
- `onPause((suspend (Pipe?, MultimodalContent) -> Unit)?)` — line 906
- `onResume((suspend (Pipe?, MultimodalContent) -> Unit)?)` — line 918
- `setPipeCompletionCallback((suspend (Pipe, MultimodalContent) -> Unit)?)` — line 1053
- `setPipelineCompletionCallback((suspend (Pipeline, MultimodalContent) -> Unit)?)` — line 1063

**Manifold.kt callbacks:**
- `setManifoldInitFunction((suspend (MultimodalContent, Manifold?) -> Boolean)?)` — line 564
- `setContextTruncationFunction((suspend (MultimodalContent) -> Unit)?)` — line 574
- `setValidatorFunction((suspend (MultimodalContent, Pipeline, Manifold) -> Boolean)?)` — line 587
- `setFailureFunction((suspend (MultimodalContent, Pipeline, Manifold) -> Boolean)?)` — line 597
- `setTransformationFunction((suspend (MultimodalContent) -> MultimodalContent)?)` — line 607

**Splitter.kt callbacks:**
- `setOnPipelineFinish((suspend (Splitter, Pipeline, MultimodalContent) -> Unit)?)` — line 528
- `setOnSplitterFinish((suspend (Splitter) -> Unit)?)` — line 543

**ManifoldDsl.kt / JunctionDsl.kt / DistributionGridDsl.kt:**
- `killSwitch(onTripped: (KillSwitchContext) -> Nothing)` — returns `kotlin.Nothing` (always throws)

**Resolution approach:** For inline suspend function types, register the underlying JVM type. Kotlin suspend lambdas are implemented as `kotlin.jvm.functions.FunctionN` with additional `Continuation` parameter. However, since the concrete function class is generated at runtime, the recommended approach is:

1. **Primary:** Use `native-image-agent` during integration tests to capture all lambda class instantiations
2. **Alternative:** The `@CEntryPoint` boundary forces explicit interface types — if the host language binds explicitly typed callbacks, the native image can see them

**For `killSwitch` callbacks returning `kotlin.Nothing`:**

```json
[
  {
    "name": "com.TTT.P2P.KillSwitchContext",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  }
]
```

### 4.3 KFunction / KCallable Method References

**Audit Result (2026-05-09):** No `::methodName` Kotlin method references found in the Pipeline directory. This pattern is not used in the audited code.

> **Action required:** If KFunction references are added in future, register the containing class with `allDeclaredMethods: true`.

---

## 5. reflect-config.json — Jackson ObjectMapper

### 5.1 P2PDescriptor Serialization + setAccessible

**Risk:** TPipe's P2P system uses Jackson for JSON serialization of `P2PDescriptor` and `AgentRequest` payloads. Jackson calls `setAccessible(true)` on constructors and setters to deserialize kebab-case JSON fields into camelCase Kotlin properties. Without registration, the deserialization fails silently (returns null/empty object on native, works on JVM).

**Audit Result (2026-05-09):** `P2PDescriptor` is a **concrete data class — no subclasses**. It holds `P2PTransport`, `P2PSkills`, and `PcPContext` as nested embedded objects. All payload classes are concrete data classes with `@Serializable` (kotlinx.serialization) annotations. Also `@JsonIgnore` from `com.fasterxml.jackson.annotation` is used.

**Core P2P types requiring Jackson registration:**

```json
[
  { "name": "com.TTT.P2P.P2PDescriptor", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.AgentRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PTransport", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PSkills", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.CustomJsonSchema", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.AgentDescriptor", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.ContextProtocol", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.SupportedContentTypes", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.InputSchema", "allDeclaredConstructors": true, "allDeclaredMethods": true }
]
```

**PcPRequest (PipeContextProtocol):**

```json
[
  { "name": "com.TTT.PipeContextProtocol.PcPRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.PipeContextProtocol.PcPRequestList", "allDeclaredConstructors": true, "allDeclaredMethods": true }
]
```

**P2PHostedRegistryModels.kt (nested P2PDescriptor usage):**

```json
[
  { "name": "com.TTT.P2P.P2PHostedRegistryListing", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedListingKind", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedListingVisibility", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedListingMetadata", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedListingLease", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryQuery", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryQueryResult", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryPublishRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryUpdateRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryRenewRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryRemoveRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryGetRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryMutationResult", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryModerateRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryAuditRecord", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryAuditQuery", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryAuditQueryResult", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryInfo", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryStatus", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryFacetRequest", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryFacetBucket", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryFacetResult", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryRpcMessage", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedModerationState", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistrySortMode", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryRpcType", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryAuditAction", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.TTT.P2P.P2PHostedRegistryListingStats", "allDeclaredConstructors": true, "allDeclaredMethods": true }
]
```

> **Note:** `@Serializable` from kotlinx.serialization uses `RUNTIME` retention by default — PASS. No custom Jackson `@JsonProperty` is used in the P2P source files — serialization uses kotlinx.serialization instead.

---

## 6. reflect-config.json — Dynamic Proxy at PCP Boundary

### 6.1 Proxy.newProxyInstance at PCP Dispatch

**Risk:** If TPipe uses `Proxy.newProxyInstance()` at the PCP dispatch boundary, GraalVM cannot see what interfaces the proxy implements.

**Required entry (GraalVM 21+, --dynamic-proxy flag not needed, interface registration still required):**

```json
[
  {
    "name": "<proxy-interface-fqdn>",
    "allDeclaredMethods": true
  }
]
```

> **Action required:** Audit PCP dispatch path for any `Proxy.newProxyInstance()` calls. Register all interfaces passed to the proxy.

---

## 7. reflect-config.json — Sealed Class Hierarchies

### 7.1 getMethod on Sealed Subclasses

**Risk:** Calling `getMethod()` on sealed class hierarchies (e.g., `ManifoldStage` and its sealed subclasses). Native Image only returns explicitly-registered methods, not the full inheritance chain. However, if the sealed hierarchy is fully contained within the same compilation unit, static analysis CAN follow it.

**Risk is conditional:** If `ManifoldStage` and its subclasses are in the same module/package, static analysis likely covers it. Risk arises when:
- Sealed class is in a separate compilation unit not included in the image
- Sealed subclasses are registered at runtime after image boot

**Required action:** Verify with `native-image --infer` reporting. If `getMethod` calls on sealed hierarchies fail in native image testing, register each sealed subclass explicitly:

```json
[
  { "name": "com.TTT.Pipeline.ManifoldStage", "allDeclaredMethods": true },
  { "name": "<sealed-subclass-1>", "allDeclaredMethods": true },
  { "name": "<sealed-subclass-2>", "allDeclaredMethods": true }
]
```

---

## 8. resource-config.json — Bundled Resources

### 8.1 Why resource-config.json is needed

The JVM loads resource files (scripts, dictionaries, config files) via `getResourceAsStream()` with no build-time configuration. GraalVM Native Image has **no classpath at runtime** — it must know at build time which files to bundle. Unregistered resources return `null` on native (no exception, no error — just silent null).

### 8.2 Required Resource Entries

**ast_validator.py — PythonSecurityManager.kt:316**

Loads `ast_validator.py` script for Python AST security validation.

```json
{ "pattern": "**/ast_validator.py" }
```

**/Words.txt — Dict.kt:18**

Loads `/Words.txt` word list at startup for dictionary/validation.

```json
{ "pattern": "**/Words.txt" }
```

**Lexicon files — SemanticCompression.kt:357**

Loads lexicon files for semantic compression.

```json
{ "pattern": "**/lexicon/**" }
```

**Full resource-config.json:**

```json
{
  "resources": [
    { "pattern": "**/ast_validator.py" },
    { "pattern": "**/Words.txt" },
    { "pattern": "**/lexicon/**" },
    { "pattern": "**/*.json" },
    { "pattern": "**/*.xml" },
    { "pattern": "**/META-INF/services/*" }
  ]
}
```

---

## 9. resource-config.json — ServiceLoader SPI Discovery

### 9.1 The Silent Failure Problem

`ServiceLoader.load(SomeInterface.class)` returns an empty iterator if no provider files are registered — **no exception is thrown**. The application silently proceeds with zero providers loaded. This is the most dangerous native image failure mode: the app appears to work but does nothing.

### 9.2 Required META-INF/services Entries

TPipe uses ServiceLoader for plugin discovery of: `Pipe`, `Connector`, `Splitter`, `Manifold`, and agent implementations. Register all service provider files:

```json
{ "pattern": "META-INF/services/*" }
```

> **Alternative:** Use explicit `--exported-services` in the native-image build command instead of resource-config glob.

### 9.3 Startup Verification (Required Addition)

Add a startup verification that calls `ServiceLoader.load(knownService)` and throws a loud error if the iterator is empty:

```kotlin
// In TPipeBootstrap.TPipe_init(), after runtime init:
val testLoad = ServiceLoader.load(com.TTT.Pipeline.Pipe::class.java).iterator()
if (!testLoad.hasNext()) {
    throw IllegalStateException(
        "TPipe native image: no Pipe service providers found. " +
        "Ensure META-INF/services entries are registered in native-image resource-config."
    )
}
```

This makes the failure **audible** (exception with clear message) instead of silent.

---

## 10. Annotation Retention Audit Checklist

### 10.1 Why This Matters

GraalVM's static analysis can only see annotations with `RUNTIME` retention. Annotations with `SOURCE` or `CLASS` retention are invisible to the native image — bindings that rely on annotation scanning will fail silently.

### 10.2 Audit Results (2026-05-09)

Audit completed via source code traversal of `src/main/kotlin`.

|| Annotation | Location | @Retention | Result | Action |
|------------|----------|------------|--------|--------|
| `@RuntimeState` | `Util/RuntimeState.kt:15` | RUNTIME (explicit) | **PASS** | None needed |
| `@Serializable` | kotlinx.serialization (used throughout P2P) | RUNTIME (default) | **PASS** | None needed |
| `@JsonIgnore` | `com.fasterxml.jackson.annotation` | RUNTIME | **PASS** | External, fine |
| `@DistributionGridDslMarker` | `Pipeline/DistributionGridDsl.kt:22` | **CLASS** (default, no explicit) | **FAIL** | Add `@Retention(AnnotationRetention.RUNTIME)` |
| `@ManifoldDslMarker` | `Pipeline/ManifoldDsl.kt:28` | **CLASS** (default, no explicit) | **FAIL** | Add `@Retention(AnnotationRetention.RUNTIME)` |
| `@JunctionDslMarker` | `Pipeline/JunctionDsl.kt:17` | **CLASS** (default, no explicit) | **FAIL** | Add `@Retention(AnnotationRetention.RUNTIME)` |

**NOT USED in TPipe source:** `@JsonProperty`, `@Binding`, `@Inject`, `@Component` — these are not present in `src/main/kotlin`. The spec's previous checklist entries for these were incorrect.

### 10.3 Required Fixes

To fix the three failing DSL marker annotations, add explicit `@Retention(AnnotationRetention.RUNTIME)`:

**DistributionGridDsl.kt:22:**
```kotlin
@DslMarker
@Retention(AnnotationRetention.RUNTIME)  // ADD THIS
annotation class DistributionGridDslMarker
```

**ManifoldDsl.kt:28:**
```kotlin
@DslMarker
@Retention(AnnotationRetention.RUNTIME)  // ADD THIS
annotation class ManifoldDslMarker
```

**JunctionDsl.kt:17:**
```kotlin
@DslMarker
@Retention(AnnotationRetention.RUNTIME)  // ADD THIS
annotation class JunctionDslMarker
```

> Without this fix, these DSL markers are invisible to GraalVM static analysis. This can cause DSL builder scopes to behave incorrectly in native image — lambdas passed to builders may not be constrained properly at the language level, though this is a semantic issue rather than a crash.

---

## 11. Reflection Initialization Ordering Contract

### 11.1 The Problem

TPipe's library state machine has phases: `LOADED → INITIALIZING → READY → SHUTTING_DOWN → SHUTDOWN`. If reflection calls fire before the bootstrap is complete, the native image may not have the required classes in its runtime graph yet — causing obscure `NoClassDefFoundError` at startup.

### 11.2 Required Contract

**Document in `graalvm-abi-core-infrastructure.md`:**

> No reflection calls (Class.forName, getDeclaredField, Constructor.newInstance, ServiceLoader.load, etc.) are valid before the library reaches `TPIPE_STATE_READY`. Wrapper developers must call `TPipe_init()` and await `TPIPE_OK` before any reflection-based operations.

**Rationale:** During `TPIPE_STATE_INITIALIZING`, the bootstrap is initializing TPipeRuntime — class loading, ServiceLoader enumeration, and template reconstruction are all in progress. Reflection calls during this window race against incomplete initialization.

---

## 12. Implementation Checklist

### Priority 0 — Critical (must do before any native image testing)

- [x] **reflect-config:** ManifoldDefaults Class.forName entries — VERIFIED: `bedrockPipe.BedrockPipe`, `ollamaPipe.OllamaPipe`, `openrouterPipe.OpenRouterPipe`
- [x] **reflect-config:** Util.kt Constructor.newInstance — VERIFIED: `com.TTT.Pipeline.Pipe` statically known; lines 446/613/704/775 are runtime-dynamic (use agent tracing)
- [x] **reflect-config:** getDeclaredField("pcpContext") — VERIFIED: `com.TTT.Pipe.Pipe` with `allowWrite: true`
- [x] **resource-config:** ast_validator.py, Words.txt, lexicon files — Confirmed paths valid
- [x] **resource-config:** META-INF/services/* for ServiceLoader — Confirmed required pattern
- [ ] **Startup verification:** ServiceLoader empty iterator check in TPipe_init()

### Priority 1 — High (required for full feature parity)

- [x] **reflect-config:** All P2PDescriptor subtypes + AgentRequest for Jackson — VERIFIED: 35 concrete classes enumerated
- [x] **reflect-config:** Lambda SAM interface registry — VERIFIED: All are inline suspend function types; no stable named SAM interfaces. Use native-image-agent tracing instead
- [x] **reflect-config:** allDeclaredMethods on classes used in `::methodName` references — VERIFIED: No KFunction references found in Pipeline dir
- [x] **Annotation retention audit:** `@RuntimeState` PASS, `@Serializable` PASS, 3 DSL markers FAIL — fix required
- [ ] **Code fix:** Add `@Retention(AnnotationRetention.RUNTIME)` to `@DistributionGridDslMarker`, `@ManifoldDslMarker`, `@JunctionDslMarker`

### Priority 2 — Medium (required for robustness)

- [ ] **reflect-config:** Dynamic proxy interfaces at PCP boundary — audit PCP dispatch for Proxy.newProxyInstance()
- [ ] **reflect-config:** Sealed subclass hierarchies — verify with --infer first, then add if needed
- [ ] **Documentation:** FunctionRegistry lifecycle in core-infrastructure.md
- [ ] **Documentation:** Reflection init ordering contract in core-infrastructure.md

---

## 13. File Locations

| Configuration | Target Path |
|---|---|
| reflect-config.json | `TPipe/src/main/resources/META-INF/native-image/reflect-config.json` |
| resource-config.json | `TPipe/src/main/resources/META-INF/native-image/resource-config.json` |

Both files must be created (greenfield — no existing GraalVM config files exist in the TPipe repository).

---

## 14. Build Verification

After creating both config files, verify with:

```bash
native-image --verbose --dry-run ...
native-image --report-unsupported-elements-at-runtime ...
./tpipe-native-image-test --native-image-agent
```

The agent mode (`-agentlib`) auto-generates reflect-config entries from runtime traces — use this to validate no entries are missing, then merge the agent output with this spec's required entries.