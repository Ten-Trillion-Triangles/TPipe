# GraalVM Native Image — Reflection & Resource Configuration Specification

**Spec File:** graalvm-abi-reflection-config.md
**Version:** 0.1.0-draft
**Created:** 2026-05-09
**Status:** Draft — Based on hyperplan adversarial reflection analysis

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

**Found at:** `Defaults/ManifoldDefaults.kt` lines 120, 124, 128

```kotlin
// Line 120 — BedrockPipe
val bedrockPipe = when {
    classExists("bedrockPipe.BedrockMultimodalPipe") ->
        Class.forName("bedrockPipe.BedrockMultimodalPipe").getDeclaredConstructor().newInstance()
    // ...
}

// Line 124 — OllamaPipe
val ollamaPipe = when {
    classExists("ollamaPipe.OllamaPipe") ->
        Class.forName("ollamaPipe.OllamaPipe").getDeclaredConstructor().newInstance()
    // ...
}

// Line 128 — OpenRouterPipe
val openrouterPipe = when {
    classExists("openrouterPipe.OpenRouterPipe") ->
        Class.forName("openrouterPipe.OpenRouterPipe").getDeclaredConstructor().newInstance()
    // ...
}
```

**Required entries:**

```json
[
  {
    "name": "bedrockPipe.BedrockMultimodalPipe",
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

**Verification:** In native image, `Class.forName("bedrockPipe.BedrockMultimodalPipe")` must return a non-null Class and `newInstance()` must succeed. Without registration, `Class.forName` throws `ClassNotFoundException` silently in the native image (no exception on JVM — the class is on the classpath).

---

### 2.2 Constructor.newInstance — Util.kt

**Risk:** `Constructor.newInstance()` on classes passed to `templateBasedReconstruction<T>()` and similar template utilities. The class is passed as a type parameter, so GraalVM's static analysis may not see the concrete constructor calls at the call sites (lines 446, 613, 704, 882).

**Found at:** `Util/Util.kt` lines 446, 613, 704, 882 — `templateBasedReconstruction<T>()`, `reconstructFromTemplate<T>()`, and similar template-based object factories.

**Required approach:** Audit every call site of `templateBasedReconstruction<T>()` and similar template functions to identify the concrete classes passed as type arguments. For each class, register:

```json
{
  "name": "<fully-qualified-class-name>",
  "allDeclaredConstructors": true
}
```

**Concrete classes to audit (full enumeration required):**
- Any class passed as `T` to `templateBasedReconstruction<T>()` at Util.kt:446, 613, 704, 882
- Classes used with `Constructor.newInstance()` anywhere in Util.kt
- Classes reconstructed from JSON/template at these call sites

> **Action required:** Full call site audit of Util.kt lines 446, 613, 704, 882 to enumerate every `T` type argument. Each distinct type requires its own reflect-config entry.

---

## 3. reflect-config.json — Field Access

### 3.1 getDeclaredField("pcpContext") — PcpFunctionExtensions.kt

**Risk:** Access to a `private var` on an internal extension receiver via `getDeclaredField("pcpContext")` + `setAccessible(true)`. Requires **both read AND write** registration.

**Found at:** `PipeContextProtocol/PcpFunctionExtensions.kt` line 102

```kotlin
val field = receiverClass.getDeclaredField("pcpContext")
field.isAccessible = true
val value = field.get(receiver)      // READ — required
field.set(receiver, newValue)        // WRITE — required (it's a var)
```

**Required entry:**

```json
{
  "name": "<receiver-class-name>",
  "allDeclaredFields": true,
  "fields": [
    { "name": "pcpContext", "allowWrite": true }
  ]
}
```

The receiver class is the Kotlin extension receiver type — audit the file to identify the exact class.

**Also note:** The inheritance chain must be covered — if `pcpContext` is defined in a superclass, both the superclass and the concrete class need field entries.

---

## 4. reflect-config.json — Lambda SAM Interfaces

### 4.1 Kotlin Lambda → SAM Conversion (DSL Builder Hazard)

**Risk:** When a developer passes a Kotlin lambda to a TPipe DSL entry point (streaming callback, pipeline finish handler, connector routing lambda), Kotlin generates a non-deterministic anonymous class at runtime. GraalVM's static analyzer cannot predict the class name, so the `invoke` method is not registered. First call throws `NoSuchMethodError`.

**This is the most common way native images break silently** — works perfectly on JVM, fails on native with no error message until the first actual call.

### 4.2 TPipe DSL Entry Points Accepting Lambdas

Audit all public TPipe APIs that accept lambda arguments. For each lambda interface type, register:

```json
{
  "name": "<lambda-interface-fqdn>",
  "methods": [
    { "name": "invoke", "parameterTypes": [...] }
  ]
}
```

**Known lambda interface categories (verify in actual source):**
- Streaming callbacks: `setStreamingCallback { chunk -> ... }`
- Pipeline finish handlers: `setOnPipelineFinish { _, content -> ... }`
- Connector routing lambdas: `addPipeline("key", pipeline) { content -> ... }`
- Agent request handlers
- P2P message handlers

> **Action required:** Full audit of TPipe's public API surface to enumerate every lambda-accepting method. Each lambda interface type requires its own reflect-config entry with `invoke` method registered.

### 4.3 KFunction / KCallable Method References

**Risk:** `::methodName` Kotlin method references create `KFunction`/`KCallable` wrappers that backstop to Java reflection. The underlying `Method.invoke()` is not registered unless the declaring class is registered with `allDeclaredMethods`.

**Pattern:**

```kotlin
val handler = someObject::handleMethod  // Creates KFunction wrapper
handler.invoke(arg)  // Internally calls java.lang.reflect.Method.invoke()
```

**Required entry for each class used in method references:**

```json
{
  "name": "<class-containing-method-references>",
  "allDeclaredMethods": true
}
```

---

## 5. reflect-config.json — Jackson ObjectMapper

### 5.1 P2PDescriptor Serialization + setAccessible

**Risk:** TPipe's P2P system uses Jackson for JSON serialization of `P2PDescriptor` and `AgentRequest` payloads. Jackson calls `setAccessible(true)` on constructors and setters to deserialize kebab-case JSON fields into camelCase Kotlin properties. Without registration, the deserialization fails silently (returns null/empty object on native, works on JVM).

**Found at:** P2P dispatch path — `P2PDescriptor` and all subtypes, `AgentRequest` payload classes.

**Required entries:**

```json
[
  {
    "name": "com.TTT.P2P.P2PDescriptor",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.TTT.P2P.AgentRequest",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  }
]
```

**Also register:** Every concrete subtype of `P2PDescriptor` used in P2P dispatch. Audit the P2P implementation to enumerate all subtypes.

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

### 10.2 Required Audit

Audit all TPipe binding annotations used in the reflection path:

| Annotation | Location | Retention | Status |
|------------|----------|-----------|--------|
| `@JvmClass` | ManifoldDefaults.kt | RUNTIME? | **Verify** |
| `@JsonProperty` | P2PDescriptor | SOURCE/CLASS/RUNTIME? | **Verify** |
| `@Inject` / CDI | — | RUNTIME | Likely fine |
| `@Component` | — | RUNTIME | Likely fine |
| Custom TPipe binding annotations | FunctionRegistry, PCP | RUNTIME? | **Verify** |

**Action:** Audit each binding annotation class to confirm `RetentionPolicy.RUNTIME`. If any are `SOURCE` or `CLASS`, change to `RUNTIME`.

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

- [ ] **reflect-config:** ManifoldDefaults Class.forName entries (BedrockPipe, OllamaPipe, OpenRouterPipe)
- [ ] **resource-config:** ast_validator.py, Words.txt, lexicon files
- [ ] **resource-config:** META-INF/services/* for ServiceLoader
- [ ] **Startup verification:** ServiceLoader empty iterator check in TPipe_init()

### Priority 1 — High (required for full feature parity)

- [ ] **reflect-config:** Full class enumeration for Util.kt Constructor.newInstance call sites (lines 446, 613, 704, 882)
- [ ] **reflect-config:** getDeclaredField("pcpContext") read+write on correct receiver class
- [ ] **reflect-config:** All P2PDescriptor subtypes + AgentRequest for Jackson
- [ ] **reflect-config:** Lambda SAM interface registry (audit all DSL entry points)
- [ ] **reflect-config:** allDeclaredMethods on classes used in `::methodName` references
- [ ] **Annotation retention audit:** All binding annotations with RUNTIME retention verified

### Priority 2 — Medium (required for robustness)

- [ ] **reflect-config:** Dynamic proxy interfaces at PCP boundary
- [ ] **reflect-config:** Sealed subclass hierarchies (verify with --infer first, then add if needed)
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