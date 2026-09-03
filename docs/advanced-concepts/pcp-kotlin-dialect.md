# PCP Kotlin dialect

TPipe executes PCP Kotlin requests with the versioned `pcp-kotlin-v1` dialect.
The dialect is an internal compatibility boundary around Kotlin's experimental
custom-scripting APIs; applications continue to use the existing PCP models
and `KotlinExecutor` API.

## Fixed contract

- Language version: Kotlin 2.0
- API version: Kotlin 2.0
- JVM target: 24
- Execution model: one-shot
- Dependencies: host application classpath only
- Compiler warnings: version warnings suppressed for this fixed dialect

Each request gets a fresh scripting host. Declarations and script state do not
survive between requests, and TPipe does not resolve Maven dependencies or
process `@DependsOn` annotations.

## Bindings

`registerBinding()` keeps the original live host object. A script can use the
object only when host application access is enabled and the binding name is
explicitly present in `KotlinContext.exposedBindings`. Registered but unexposed
objects are absent. Named classes are supported through Kotlin provided
properties; local and anonymous binding types retain their compatibility
behavior rather than being widened to `Any`.

When introspection is enabled, `PcpRegistry` and the live `PcpContext` are
provided. Explicit custom bindings are applied afterward, preserving the
existing collision behavior.

## Results and safety

The final non-null, non-`Unit` expression is rendered as `Result: <value>`.
`null` and `Unit` do not add a result line. Standard output and error retain
the existing channel and trimming behavior. Security validation runs before
the backend compiles or evaluates a script.

The timeout is an outward wait bound. It returns a timeout result but does not
terminate arbitrary JVM bytecode still running in the daemon thread. The K2
host is not a JVM security sandbox; PCP policy validation remains the security
boundary.

## Versioning

Upgrading the repository compiler must not silently change `pcp-kotlin-v1`.
Any incompatible scripting behavior requires a new dialect identifier and a
compatibility review.
