# Hosted P2P Registry Progress

Date: 2026-03-28
Last Updated: 2026-03-28

## Summary

The first hosted-registry slice is now implemented in code:

- hosted-registry contract models exist
- a P2P-exposed hosted-registry service exists
- an in-memory hosted-registry store exists
- a human client exists
- PCP tools exist for agent query and mutation flows
- `P2PRegistry` can import sanitized hosted `AGENT` listings into the local static registry
- `DistributionGrid` can:
  - configure hosted bootstrap catalog sources
  - auto-pull trusted `GRID_REGISTRY` listings on init
  - publish, renew, and remove public node or registry listings
- the grid DSL now exposes `bootstrapCatalogSource(...)`

## Implemented Files

- `src/main/kotlin/P2P/P2PHostedRegistryModels.kt`
- `src/main/kotlin/P2P/P2PHostedRegistry.kt`
- `src/main/kotlin/P2P/P2PHostedRegistryTools.kt`
- `src/main/kotlin/P2P/P2PRegistry.kt`
- `src/main/kotlin/Pipeline/DistributionGridProtocolModels.kt`
- `src/main/kotlin/Pipeline/DistributionGrid.kt`
- `src/main/kotlin/Pipeline/DistributionGridDsl.kt`

## Focused Verification

Focused suites added:

- `src/test/kotlin/P2PHostedRegistryTest.kt`
- `src/test/kotlin/P2PHostedRegistryToolsTest.kt`
- `src/test/kotlin/Pipeline/DistributionGridHostedRegistryIntegrationTest.kt`

Focused validation run currently completed:

- `./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx8g -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx16g compileKotlin`
- `./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx8g -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx16g test --tests "com.TTT.P2PHostedRegistryTest" --tests "com.TTT.Pipeline.DistributionGridHostedRegistryIntegrationTest" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test`

Result:

- compile passed
- focused hosted-registry and grid integration suites passed after fixing:
  - typed hosted-registry RPC response serialization
  - the hosted-grid integration test double so it preserves container ownership like a real bound component

## Known Remaining Work

- broader `DistributionGrid*` verification still needs to be rerun after the hosted-registry additions
- public docs still need broader sync beyond the steering files
- the first slice ships only an in-memory store; durable hosted-registry backing stores remain future work
