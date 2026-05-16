# Sub-Plan: Phase 6–7 — tpipe-abi-10step

## Covered Phases
- Phase 6: Execute Implementation
- Phase 7: Verify

## Implementation Order (15 Tasks)
Tasks must execute SEQUENTIALLY, one at a time:

1. Task 1: GraalVM Native Image build setup verification
2. Task 2: C header file (tpipe-abi.h)
3. Task 3: Bootstrap class with 8 @CEntryPoint phantom functions
4. Task 4: Core type bindings — TPipe_Handle system
5. Task 5: MultimodalContent (ContentHandle)
6. Task 6: BinaryContent (BinaryHandle)
7. Task 7: Enum mappings
8. Task 8: Collection handles (List/Map builders)
9. Task 9: Pipe API — execute/executeAsync
10. Task 10: Pipeline API — create/orchestrate
11. Task 11: Context API — ContextWindow/MiniBank/LoreBook handles
12. Task 12: PCP API
13. Task 13: P2P API
14. Task 14: reflection-config (resource-config.json)
15. Task 15: Gap analysis verification

## Key Constraints
- GraalVM Serial GC only (G1 Enterprise)
- No JIT — AOT-only compilation
- Library-owned global state (no context pointers)
- All entry points as extern "C" function pointers
- Handle-based object model (uint64_t opaque handles, refcounted)