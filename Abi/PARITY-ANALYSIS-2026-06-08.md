# TPipe ABI Parity Analysis — Native vs JVM

**Date:** 2026-06-08
**Branch:** ABI
**Commit:** bb104a13 (Merge branch 'main' into ABI)

---

## Executive Summary

The TPipe GraalVM Native ABI exposes **168 C-callable functions** (@CEntryPoint methods in `TPipeBootstrap.java`) corresponding to **157 declarations in `tpipe-abi.h`** (after excluding 19 handle typedefs).

The JVM side has **~414 public/internal methods** across the core classes (Pipe, Pipeline, Manifold, Junction, Splitter, Connector, MultiConnector, DistributionGrid, ContextWindow, LoreBook, ConverseData).

**Parity ratio: 168 / 414 ≈ 40% by raw method count.**

Feature coverage varies by category:
- LoreBook: 16 native functions vs 2 JVM methods = 800% (over-covered)
- Connector: 5 vs 6 = 83%
- ContextWindow: 8 vs 24 = 33%
- Splitter: 5 vs 30 = 17%
- Manifold: 8 vs 60 = 13%
- Pipe: 20 (Pipe + PipeSettings) vs 173 = 12%
- Junction: 5 vs 100 = 5%
- DistributionGrid: 9 vs 200 = 4.5%

**Estimated work to reach 100% parity (without reducing JVM): add ~250 more native ABI functions.**

---

## Native ABI Surface (168 @CEntryPoint methods)

| Family | Functions | Notes |
|--------|-----------|-------|
| Core init/lifecycle | 9 | TPipe_init, shutdown, getState, isInitialized, getVersion, getCapabilities, getLastError, main, free |
| Handle lifecycle | 4 | addRef, release, getRefCount, isValid |
| Result free | 1 | TPipe_Result_free |
| AsyncHandle | 6 | create, getResult, isDone, poll, wait, cancel |
| Binary | 5 | create, createEmpty, getBytes, getVariant, release |
| Connector | 5 | create, init, execute, release, serialize |
| Content | 29 | create, createWithText, clone, release, get*/set* (text, context, miniBank, binary, jumpTo, terminate, pass, repeat, skip) |
| Context / ContextWindow | 6 | ContextWindow_create + 5 Context_get* (json, count, lorebookKeys, converseHistorySize, version) |
| ConverseHistory | 8 | create, add, addString, getAt, size, isEmpty, clear, toJson |
| DistributionGrid | 9 | create, release, serialize, getNodeCount, getNodeCount_v2, getHealth, getStatusJson, getLastRebalanceMs, rebalance_stub |
| Junction | 5 | create, init, execute, release, serialize |
| List | 4 | create, append, get, size |
| LoreBook | 16 | create, release(?), addEntry/AliasKey/LinkedKey/RequiredKey, get/set Key/Value/Weight, get AliasKeys/LinkedKeys/RequiredKeys, combine, toJson |
| Manifold | 8 | create, init, execute, release, serialize, addWorker, setMaxLoopIterations, getWorkerCount |
| Map | 5 | create, set, get, has, size |
| MiniBank | 8 | create, clear, set, getPageJson, getPageKeys, isEmpty, merge, pageCount |
| P2PHandle | 4 | create, connect, registerAgent, send |
| PCPHandle | 2 | create, execute |
| Pipe (direct) | 9 | create, init, execute, executeContentAsync, getTokenUsage, setProvider, setModel(?), setTemperature, setRepetitionPenalty, setReasoning |
| PipeSettings | 11 | create, release, setBool, setFloat, setInt, setMaxTokens, setModel, setProvider, setString, setTemperature, setTimeout |
| Pipeline | 9 | create, release, add, execute, setName, getName, getOutcome, getContextWindow, getMiniBank |
| Splitter | 5 | create, init, execute, release, serialize |
| **Total** | **168** | |

### Native ABI Functions NOT in tpipe-abi.h (31 orphans)

These are @CEntryPoint methods in Java that have no C header declaration:
- TPipe_AsyncHandle_create, TPipe_Binary_create, TPipe_Binary_createEmpty, TPipe_Connector_create, TPipe_Connector_execute
- TPipe_Content_clone, TPipe_Content_create, TPipe_Content_createWithText
- TPipe_ContextWindow_create
- TPipe_ConverseHistory_create
- TPipe_DistributionGrid_create
- TPipe_Junction_create, TPipe_Junction_execute
- TPipe_List_create
- TPipe_LoreBook_create
- TPipe_Manifold_create, TPipe_Manifold_execute
- TPipe_Map_create
- TPipe_MiniBank_create
- TPipe_P2PHandle_create
- TPipe_PCPHandle_create
- TPipe_PipeSettings_create
- TPipe_Pipe_create, TPipe_Pipe_execute, TPipe_Pipe_executeContentAsync
- TPipe_Pipeline_create, TPipe_Pipeline_getContextWindow, TPipe_Pipeline_getMiniBank
- TPipe_Splitter_create, TPipe_Splitter_execute
- TPipe_free

**Action Required:** Add these declarations to `tpipe-abi.h` so the C ABI surface is documented and usable from C.

---

## JVM Method Surface (~414 public/internal methods)

| Class | Methods | Setters | Getters | Enables | Disables | Execute/Create/Release |
|-------|---------|---------|---------|---------|----------|------------------------|
| Pipe.kt | 173 | 72 | 26 | 16 | 5 | init+execute+(set* model/system/user/etc.) |
| Pipeline.kt | 44 | 13 | 22 | - | - | execute, add, init, insert |
| Manifold.kt | 60+ | 20 | 19 | - | - | execute, init, addWorker, setMaxLoopIterations |
| Junction.kt | 100+ | 26 | 11 | - | - | execute, executeWorkflow, executeWorkflowPhase |
| Splitter.kt | 30+ | - | - | - | - | executePipelines, init, addPipeline |
| Connector.kt | 20+ | - | - | - | - | execute, executeLocal, add, get |
| MultiConnector.kt | 3 | - | - | - | - | - |
| DistributionGrid.kt | 200+ | 26 | 30 | - | - | execute, executeLocal, dispatchExplicitPeerHandoff |
| ContextWindow.kt | 24 | - | - | - | - | addLoreBookEntry, selectLoreBookContext, merge, clear |
| LoreBook.kt | 2+ | - | - | - | - | combineValue, toMap |
| ConverseData.kt | 5 | - | - | - | - | - |
| MemoryTypes.kt | 1 | - | - | - | - | - |
| **Total (approx)** | **~660** | | | | | |

Note: many JVM methods are internal helpers (e.g., `setP`, `setContainerObject`, `setPipeSettingsRecursively`, `setTokenBudgetRecursive`) that are recursive configuration patterns, not user-facing features.

---

## Detailed Parity by Category

### Pipe.kt (JVM) vs TPipe_Pipe_* + TPipe_PipeSettings_* (Native)

**JVM methods (173):**
- Lifecycle: init, execute, executeMultimodal, executeP, executeReasoningPipe
- Configuration: setProvider, getProvider, setModel, getModelName, setSystemPrompt, setUserPrompt, setMiddlePrompt, setFooterPrompt, setPromptMode, getPromptMode
- Temperature/sampling: setTemperature, setTopP, setTopK, setMaxTokens, setRepetitionPenalty, setPresencePenalty, setSeed, setStopSequences
- Reasoning: setReasoning, disableReasoning, getReasoningContent, getFooterPromptForReasoning, getMiddlePromptForReasoning
- JSON I/O: setJsonInput, setJsonInputInstructions, setJsonOutput, setJsonOutputInstructions, requireJsonPromptInjection
- Multimodal: setMultimodalInput, setBinaryInput, addBinary, getBinary
- Streaming: obtainStreamingCallbackManager, createStreamingCallbackBuilder
- Token budget: setTokenBudget, getTokenBudgetSettings, getTokenUsage, getTotalInputTokens, getTotalOutputTokens
- Context: setContextWindowSettings, setContextWindowSize, getContextWindowObject, getMiniContextBankObject
- Lorebook: enableAppendLoreBookScheme, enableImmutableLoreBook, enableLoreBookFillMode, enableLoreBookFillAndSplitMode, enableDynamicFill, enableDynamicSizeFill, setEmplaceLorebook, setAppendLoreBook, setLoreBookFillMode, setLoreBookFillAndSplitMode
- Compression: enableSemanticCompression, enableSemanticDecompression, compressPrompt
- Tracing: enableTracing, disableTracing, addTraceId, removeTraceId, clearTraceIds
- Caching: cacheInput, forceCacheInput, getCachedInput, forceSaveSnapshot
- Timeouts: enablePipeTimeout, setPipeTimeout, setTimeoutStrategy
- Errors: getErrorMessage, getErrorType, clearError
- Retries: getRetryCount, incrementRetryCount, clearRetryCount, setRetryFunction, setMaxRetries
- Hooks: setExceptionFunction, setValidatorFunction, setTransformationFunction, setPreInitFunction
- Branching: setBranchPipe, setValidatorPipe, setTransformationPipe
- P2P/PCP: setP2PAgentList, setP2PDescription, setPcPContext, setPcPDescription
- Token utilities: countTokens, countBinaryTokens, createTokenBanList, createTokenEncourageList
- ContextBank: setContextBank, setPageKey, setAutoInjectContext, setAutoTruncateContext
- Misc: setPipeName, getP, setP, setParentPipe, getParentPipe, isReasoningPipe, createEmptyPreview, copySystemPromptToUserPrompt, setTodoListPageKey, setTodoListInstructions, enableHarnessMode, setJsonInput/Output (KClass variants), enableComprehensiveTokenTracking, disableComprehensiveTokenTracking, enableDeterministicGeneration, disableDeterministicGeneration, enableTextMatchingPreservation, disableTextMatchingPreservation, enableMemoryIntrospection, enableMaxTokenOverflow, appendContextInstructions, emplaceConverseHistory, emplaceConverseHistoryOnlyIfNull

**Native functions (9 direct + 11 PipeSettings = 20):**
- TPipe_Pipe_create, init, execute, executeContentAsync, getTokenUsage
- TPipe_Pipe_setProvider, setTemperature, setRepetitionPenalty, setReasoning
- TPipe_PipeSettings_create, setBool, setFloat, setInt, setString, setModel, setProvider, setTemperature, setMaxTokens, setTimeout, release

**Parity: ~12% (20/173)**

The PipeSettings generic setter pattern (setBool/setFloat/setInt/setString) can map to many JVM setters, but the C ABI doesn't expose specific semantic names. The C side cannot, for example, "set top K" or "set system prompt" without going through generic integer/float fields.

### Pipeline.kt (JVM) vs TPipe_Pipeline_* (Native)

**JVM methods (44):**
- add, addAll, insert (pipe chain)
- execute, executeMultimodal, executeP
- getPipes, getPipeByName, getCurrentPipe, getNextPipe
- setPipelineName, getPipelineName
- getTokenUsage, getTokenCount, getTotalInputTokens, getTotalOutputTokens
- getErrorMessage, getFailedPipeName, getFailureAnalysis, hasError, wasTerminatedByError
- clearErrors, getFullErrorContext
- Trace: enableTracing, getTraceId, getTraceReport, trace
- Pause: enablePausing, enablePausePoints, pause, resume, isPaused, canPause, onPause, onResume, checkPausePoint, checkConditionalPause, pauseBeforePipes, pauseAfterPipes, pauseAfterRepeats, pauseBeforeJumps, pauseOnCompletion, pauseWhen
- Context: setContextWindow, setMiniBank, useGlobalContext
- Token budget: setTokenBudgetRecursive
- Callbacks: setPipeCompletionCallback, setPipelineCompletionCallback
- Validation: setPreValidationFunction
- History: appendContentToConverseHistory, wrapContentWithConverseHistory
- Misc: setP, setParentInterface, setContainerObject, getP, getParentP, getPipelinesFromInterface, getContainerObject, hasContextOverflowProtectionConfigured, getPipesWithoutContextOverflowProtection, setPipeSettingsRecursively, init

**Native functions (9):**
- TPipe_Pipeline_create, release, add, execute, setName, getName, getOutcome, getContextWindow, getMiniBank

**Parity: ~20% (9/44)**

### Manifold.kt (JVM) vs TPipe_Manifold_* (Native)

**JVM methods (60+):**
- Lifecycle: init, execute, executeP, pause, resume
- Configuration: setManagerPipeline, getManagerPipeline, getPrimaryManagerPipe, setMaxLoopIterations, getMaxLoopIterations, hasLoopLimit
- Budget: setManagerTokenBudget, getManagerTokenBudget, setTokenBudgetRecursive, getTokenBudgetSettings, getEffectiveManagerTokenBudget, getEffectiveManagerHistoryTokenBudget, applyBuiltInManagerBudgetControl, isManagerBudgetControlEnabled, resolveManagerBudgetConfiguration, estimateManagerHistoryTokenBudget, setPipeSettingsRecursively
- Workers: addWorkerPipeline, getWorkerPipelines, getWorkerCount, getWorkersWithoutOverflowProtection, workersHaveOverflowProtection, validateWorkerPipelineOverflowProtection
- Truncation: setContextWindowSize, setContextTruncationFunction, setTruncationMethod, getTruncationMethod
- Summary: setSummaryMode, setSummaryPipeline, buildDefaultManagerPipeline, buildSummaryPipelineInput, buildManifoldMetadata
- Functions: setManifoldInitFunction, setFailureFunction, setValidatorFunction, setTransformationFunction
- Trace: enableTracing, disableTracing, getTraceId, getTraceReport, trace
- History: shouldIncludeContent, shouldIncludeContext, autoTruncateContext
- Misc: setP, getP, setParentInterface, getParentP, getPipelinesFromInterface, getContainerObject, setContainerObject, addP, getFailureAnalysis, setContainerObject, checkKillSwitch

**Native functions (8):**
- TPipe_Manifold_create, init, execute, release, serialize, addWorker, setMaxLoopIterations, getWorkerCount

**Parity: ~13% (8/60+)**

### Junction.kt (JVM) vs TPipe_Junction_* (Native)

**JVM methods (100+):**
- Lifecycle: init, execute, executeP, executeLocal, pause, resume
- Voting strategies: simultaneous, roundRobin, conversational, discussionOnly
- Workflows: planVoteActVerifyRepeat, planVoteAdjustOutputExit, voteActVerifyRepeat, votePlanActVerifyRepeat, votePlanOutputExit, actVoteVerifyRepeat
- Workflow execution: conductDiscussion, conductWorkflow, executeWorkflow, executeWorkflowPhase, runVoteWorkflowPhase, runWorkflowBindingPhase
- Moderator: setModerator, setPlanner, setActor, setAdjuster, setVerifier, setModeratorIntervention, resolveModeratorDirective
- Configuration: setRounds, setMaxNestedDepth, setVotingThreshold, setStrategy, setOutputHandler
- Workflow: setWorkflowRecipe, registerWorkflowBinding, runParticipantRound, dispatchParticipant, shouldRepeatWorkflowCycle, parseParticipantOpinion, normalizeVote, tallyVotes
- Memory/budget: setMemoryPolicy, getMemoryPolicy, memoryPolicy, resolveMemoryTruncationSettings, resolveOutboundBudget, countBudgetedTokens, budgetText, budgetEnvelope
- Truncation: compactContentExcerpt
- Trace: enableTracing, disableTracing, getTraceId, getTraceReport, trace
- Containers: getContainerObject, setContainerObject, validateContainerAncestry, validateParticipantGraphs, validateWorkflowGraphs
- Metadata: buildMetadata, buildBinding, allBindings, allBindingNames, bindContainerReference, buildDefaultWorkflowPhaseResult, buildDefaultDirective, buildDefaultPlanText, buildDefaultActText, buildDefaultAdjustText, buildDefaultVerifyText
- Memory envelope: buildModeratorMemoryEnvelope, buildParticipantMemoryEnvelope, buildWorkflowMemoryEnvelope, buildWorkflowCriticalLines, buildWorkflowRecentLines, buildWorkflowSummaryText, buildWorkflowSummarySeed, buildDiscussionCriticalLines, buildDiscussionDecision, buildDiscussionRecentLines, buildDiscussionSummarySeed, buildModeratorPrompt, buildParticipantRequest, buildPromptFromEnvelope, buildSummaryText, buildSectionText, buildMiniBankFromSections
- Workflow results: buildWorkflowOutcome, finalizeWorkflowOutput, recordWorkflowPhaseResult, resolveRoundParticipants
- Misc: setP, getP, setParentInterface, getParentP, getPipelinesFromInterface, canPause, isPaused, checkKillSwitch, getFailureAnalysis, clearTrace, clearRuntimeState, resetRuntimeState, envelopeMetadata, describeContainer, getTokenBudgetSettings, setTokenBudgetRecursive, setPipeSettingsRecursively

**Native functions (5):**
- TPipe_Junction_create, init, execute, release, serialize

**Parity: ~5% (5/100+)**

This is the largest gap in the container layer.

### Splitter.kt (JVM) vs TPipe_Splitter_* (Native)

**JVM methods (30+):**
- Lifecycle: init, executePipelines, executeLocal
- Pipeline: addPipeline, removePipeline, getAllChildPipelines, getChildTraceIds
- Results: storeResult, flush
- Configuration: setOnPipelineFinish, setOnSplitterFinish
- Trace: enableTracing, disableTracing, getTraceId, getTraceReport, trace
- Misc: setParentInterface, getParentP, getTokenBudgetSettings, setTokenBudgetRecursive, setPipeSettingsRecursively, checkKillSwitch, getFailureAnalysis, buildSplitterMetadata, removeKey, handleSplitterCompletion, shouldIncludeContent, addContent, MultimodalContent

**Native functions (5):**
- TPipe_Splitter_create, init, execute, release, serialize

**Parity: ~17% (5/30+)**

### Connector.kt (JVM) vs TPipe_Connector_* (Native)

**JVM methods (20+):**
- Lifecycle: execute, executeLocal
- Pipes: add, get
- Configuration: setDefaultPath
- Trace: enableTracing, getTrace, getTraceId
- Misc: setP, getP, setParentInterface, getParentP, getPipelinesFromInterface, checkKillSwitch, setPipeSettingsRecursively, setTokenBudgetRecursive, getTokenBudgetSettings, trace, MultimodalContent, executeP

**Native functions (5):**
- TPipe_Connector_create, init, execute, release, serialize

**Parity: ~25% (5/20+)**

### DistributionGrid.kt (JVM) vs TPipe_DistributionGrid_* (Native)

**JVM methods (200+):**
- Lifecycle: init, execute, executeP, executeLocal, executeEnvelopeLocally, dispatchExplicitPeerHandoff
- Configuration: setWorker, setRouter, setRoutingPolicy, setMemoryPolicy, setDurableStore, setDiscoveryMode, setMaxHops, setMaxSessionDuration, setRpcTimeout, setTrustVerifier, setRegistryMetadata
- Hooks: setBeforeLocalWorkerHook, setAfterLocalWorkerHook, setBeforePeerDispatchHook, setAfterPeerResponseHook, setBeforeRouteHook, setFailureHook, setOutboundMemoryHook, setOutcomeTransformationHook
- Peers/registries: addPeer, removePeer, replacePeer, addPeerDescriptor, addBootstrapRegistry, removeBootstrapRegistry, addBootstrapCatalogSource, removeBootstrapCatalogSource
- Public listings: publishPublicNodeListing, removePublicNodeListing, renewPublicNodeListing, updatePublicNodeListing, publishPublicRegistryListing, removePublicRegistryListing, renewPublicRegistryListing, updatePublicRegistryListing
- Discovery: getDiscoveredNodeIds, getDiscoveredRegistryIds, getLocalPeerKeys, getExternalPeerKeys, getActiveRegistryLeaseIds, getBootstrapRegistryIds, getBootstrapCatalogSourceIds, getBootstrapCatalogSourceStatuses, getPublicListingAutoRenewIds, getPublicListingAutoRenewStatuses
- Lease management: grantRegistrationLease, renewRegistryLease, getRegistryMetadata, syncRegistryMembershipsFromActiveLeases, tickRegistryMemberships, storeLocalRegistrationLeaseState
- RPC: handleGridRpcRequest, handleRegisterNodeRequest, handleQueryRegistryRequest, handleProbeRegistryRequest, handleHandshakeInitRequest, handleRenewLeaseRequest, handleTaskHandoffRequest, sendGridRpcRequest, sendRegistryRpcRequest, queryRegistries, performPeerHandshake
- Sessions: resolveOrCreatePeerSession, resolveValidCachedSession, resolveInboundSession, revalidateResumedSession, invalidateSession, invalidateAllSessions, isSessionValid, isHandshakeSessionDurationWithinRequestedWindow, isInboundSessionWrapperValid, resolveRequestedSessionSeconds, resolveLeaseRenewalWindowMillis
- Routing: resolveDirective, routeLocalDirective, runLocalWorker, resolveOutboundBudget, resolveMemoryTruncationSettings, countBudgetedTokens, budgetText, compactContentExcerpt
- Memory envelope: buildOutboundMemoryEnvelope, buildOutboundRecentLines, buildOutboundCriticalLines, buildOutboundSummarySeed, buildMiniBankFromSections, buildSectionText, buildSummaryText, buildBoundaryFailureContent
- Validation: validateContainerAncestry, validateExecutionReadiness, validateLocalOwnership, validateOutboundPolicy, validatePeerRegistrationState, validateRegistryClientReadiness, validateRegistryServerReadiness, verifyNodeAdvertisement, verifyRegistryAdvertisement, sanitizeInboundAttributes, sanitizeOutboundContentMetadata
- Negotiation: negotiateCredentialPolicy, negotiateHandshakePolicy, negotiateRoutingPolicy, negotiateStorageClasses, negotiateTracePolicy, resolveDefaultTracePolicy, resolvedPolicySatisfiesHandshakeRequest, resolvedPolicySatisfiesRequestedPolicies, canonicalizeNegotiatedPolicyForComparison, isRemotePcpForwardAllowed, hasForwardingRelevantPcpPayload
- Advertising: synthesizeDescriptor, synthesizeGridDescriptor, synthesizeRequirements, synthesizeGridMetadata, buildRegisteredNodeAdvertisement, buildPublicNodeHostedListing, buildPublicRegistryHostedListing, buildLocalRegistryAdvertisement, buildRemoteDescriptorFromRegistration
- Trace: enableTracing, disableTracing, getTraceReport, trace, tracePublicListingOperation, isTracingEnabled
- Lifecycle/utility: pause, resume, isPaused, canPause, checkKillSwitch, getP, setP, getParentP, setParentInterface, getPipelinesFromInterface, getContainerObject, setContainerObject, setPipeSettingsRecursively, setTokenBudgetRecursive, getTokenBudgetSettings, getFailureAnalysis, buildFailureRpcMessage, buildGridRpcRequest, buildGridRpcContent, buildHopRecord, buildRemoteDispatchHopRecord, buildPeerKey, buildDiscoveredNodeKey, buildPeerSessionCacheKey, buildHostedRegistryMutationFailure, hasGridRpcFrame, parseGridRpcMessage, extractRegistryLeaseFromResponse, normalizeEnvelopeFromDirectInput, normalizeEnvelopeFromP, mapFailureToP, mapRemoteP, externalP, getRouterBindingKey, getWorkerBindingKey, defaultTransport, defaultGridTransport, defaultAgentName, defaultGridAgentName, ensureGridIdentity, resolveCurrentNodeId, resolveCurrentRegistryContextId, resolveCurrentRegistryScope, resolveCurrentTransport, resolveRegistryAdvertisement, resolveExplicitPeerDescriptor, resolveFreshDiscoveredNodeAdvertisement, resolveInboundRegistryProtocolVersion, resolveRegistryProtocolVersion, resolveRegistryScopeFromToken, resolveReturnMode, markShellDirty, sessionPolicySatisfiesEnvelope, applyNegotiatedPolicyToEnvelope, shapeOutboundEnvelopeForPeer, mergeRemoteSuccess, mergeRemoteFailure, finalizeSuccessfulEnvelope, finalizeFailedEnvelope, archiveTaskStateBestEffort, checkpointTaskState, resumeTask, buildTraceContentSnapshot, buildTraceMetadata, clearTrace, clearRuntimeState, clearDiscoveredRegistryState, extractEnvelopeFromTerminalContent, matchesRegistryQuery, toRegistryToken, purgeExpiredDiscoveryState, purgeExpiredLocalRegistrationState, probeTrustedRegistries, pullTrustedBootstrapCatalogs, describeContainer, getDiscoveryMode, getRoutingPolicy, getMaxHops, getMaxSessionDuration, getMemoryPolicy, getDurableStore, getTrustVerifier, getRpcTimeout, buildContextWindowForPrompt, buildPausedContent, continueWithRetryDirective, buildFailure, cacheSession, allBindings, buildBinding, shouldIncludeContent, getDiscoveredRegistryMetadata, getDiscoveredRegistryAdvertisement, injectOutboundCredentials, verifyNodeAdvertisement

**Native functions (9):**
- TPipe_DistributionGrid_create, release, serialize, getNodeCount, getNodeCount_v2, getHealth, getStatusJson, getLastRebalanceMs, rebalance_stub

**Parity: ~4.5% (9/200+)**

This is the most under-covered subsystem.

### ContextWindow.kt (JVM) vs TPipe_Context_* + TPipe_ContextWindow_* (Native)

**JVM methods (24):**
- Lorebook: addLoreBookEntry, addLoreBookEntryWithObject, findLoreBookEntry, findMatchingLoreBookKeys, findMatchingLoreBookKeysSuspend, selectLoreBookContext, selectLoreBookContextSuspend, selectLoreBookContextWithSettings, selectLoreBookContextWithSettingsSuspend, selectAndFillLoreBookContext, selectAndFillLoreBookContextSuspend, selectAndFillLoreBookContextWithSettings, selectAndFillLoreBookContextWithSettingsSuspend, selectConverseHistoryLoreBookContext, cleanLorebook, canSelectLoreBookKey, canSelectLoreBookKeySuspend, countAndSortKeyHits, checkKeyDependencies
- History: extractConverseHistoryText, truncateConverseHistory, truncateConverseHistoryWithObject, countConverseHistoryTokens
- Combine/truncate: combineAndTruncateAsString, combineAndTruncateAsStringWithSettings, combineAndTruncateAsStringWithSettingsSuspend, selectAndTruncateContext, selectAndTruncateContextSuspend, truncateContextElements
- Filter: filterConverseEntriesByText
- Merge: merge
- Misc: clear, isEmpty, isContextLocked, getLockedKeys

**Native functions (6):**
- TPipe_ContextWindow_create
- TPipe_Context_getContextElementsCount
- TPipe_Context_getContextJson
- TPipe_Context_getConverseHistorySize
- TPipe_Context_getLoreBookKeys
- TPipe_Context_getVersion

**Parity: ~25% (6/24)**

The C ABI is mostly read-only on Context (get* only). No lorebook add/remove/find, no truncate, no merge.

### LoreBook.kt (JVM) vs TPipe_LoreBook_* (Native)

**JVM methods (2):**
- combineValue, toMap

**Native functions (16):**
- TPipe_LoreBook_create, addEntry, addAliasKey, addLinkedKey, addRequiredKey, getKey, getValue, getWeight, getAliasKeys, getLinkedKeys, getRequiredKeys, setKey, setValue, setWeight, combine, toJson

**Parity: ~12.5% (2/16) from JVM perspective — but the native ABI is much richer!**

This shows the C ABI is in some places more fully featured than the JVM's main class because the JVM delegates lorebook operations to ContextWindow, while the C ABI has direct lorebook methods.

---

## Test Coverage (JVM)

| Test File | Tests | Pass | Fail | Notes |
|-----------|-------|------|------|-------|
| AbiParityMatrixTest | 9 | 9 | 0 | Tracks @CEntryPoint <-> tpipe-abi.h parity |
| AsyncHandleCompletionTest | ? | ? | ? | Async handle tests |
| BedrockWiringTest | ? | ? | ? | Bedrock provider wiring |
| BinaryHandleTest | ? | ? | ? | Binary content handle |
| CEntryPointBufferBoundsTest | ? | ? | ? | Buffer overflow safety |
| ConnectorHandleCompletionTest | ? | ? | ? | Connector handle |
| ContentHandleTest | ? | ? | ? | Content handle |
| ContextHandleCompletionTest | ? | ? | ? | Context handle |
| ConverseHistoryHandleCompletionTest | ? | ? | ? | Converse history |
| ConverseHistoryHandleTest | ? | ? | ? | Converse history |
| DistributionGridHandleTest | 13 | 13 | 0 | DistributionGrid (after fix) |
| EnumMappingsProviderNameExtensionTest | ? | ? | ? | Enum mapping |
| HandleRegistryTest | ? | ? | ? | Handle registry |
| HandleSanitizationTest | ? | ? | ? | Handle sanitization |
| HandleTypedefSanityTest | ? | ? | ? | Handle typedef |
| JunctionHandleCompletionTest | ? | ? | ? | Junction handle |
| ListHandleTest | ? | ? | ? | List handle |
| LoreBookHandleCompletionTest | ? | ? | ? | LoreBook handle |
| LoreBookHandleTest | ? | ? | ? | LoreBook handle |
| ManifoldHandleTest | ? | ? | ? | Manifold handle |
| MapHandleCompletionTest | ? | ? | ? | Map handle |
| MapHandleTest | ? | ? | ? | Map handle |
| MiniBankHandleCompletionTest | ? | ? | ? | MiniBank handle |
| MiniBankHandleTest | ? | ? | ? | MiniBank handle |
| NativeImageReachabilityTest | ? | ? | ? | Native image reachability |
| OllamaWiringTest | ? | ? | ? | Ollama provider wiring |
| OperationHandleCompletionTest | ? | ? | ? | Operation handle |
| OperationHandleTest | ? | ? | ? | Operation handle |
| P2PHandleTest | ? | ? | ? | P2P handle |
| PCPHandleTest | ? | ? | ? | PCP handle |
| PipeSettingsHandleTest | ? | ? | ? | PipeSettings handle |
| PipelineHandleTest | ? | ? | ? | Pipeline handle |
| ProviderClasspathTest | ? | ? | ? | Provider classpath |
| SplitterHandleCompletionTest | ? | ? | ? | Splitter handle |
| **Total** | **289** | **288** | **1** | (Was 1 failing before fix) |

All 289 tests pass after fixing the `DistributionGridHandleTest` source bug.

---

## Gap Summary

### A. Header ↔ Implementation parity (JVM Internal)
- **31 native @CEntryPoint methods lack C header declarations** in tpipe-abi.h.
- Fix: Add declarations to tpipe-abi.h (preserves Java, adds documentation).

### B. Native ABI ↔ JVM method coverage
- **~40% raw parity** by method count.
- **Major gaps in:**
  - DistributionGrid (4.5%)
  - Junction (5%)
  - Manifold (13%)
  - Splitter (17%)
  - Pipeline (20%)
  - Connector (25%)
  - ContextWindow (25%)
  - Pipe (12%)
  - LoreBook (12.5% from JVM perspective, but native is more complete)

### C. Spec ↔ Implementation parity
- Spec defines many more functions than what's implemented.
- Some spec features have NO native ABI exposure yet:
  - Streaming callbacks (StreamingCallbackBuilder/Manager)
  - Token budget operations
  - Error reporting via TPipe_ErrorHandle
  - P2P transport/descriptor/requirements handles
  - Stdio/HttpContext options
  - StdioExecutor, KotlinExecutor, JavaScriptExecutor, PythonExecutor
  - FunctionRegistry, FunctionInvoker
  - Trace visualizers
  - MemoryIntrospection, LockRequest, MemoryServer, MemoryClient
  - BedrockPipe, NovaPipe, OllamaPipe extended APIs
  - MCP bridge server APIs

---

## Path to 100% Parity (Preserving JVM)

### Phase 1: Header Documentation (Easy, non-destructive)
- Add 31 missing declarations to tpipe-abi.h to match @CEntryPoint
- Estimated time: 1-2 hours
- Impact: 0 JVM changes; C header becomes accurate

### Phase 2: Container Completion (Medium)
Add native functions for:
- **Manifold**: setManagerPipeline, setManagerTokenBudget, setContextWindowSize, setTruncationMethod, setSummaryMode, setSummaryPipeline, setValidatorFunction, setTransformationFunction, setFailureFunction, setManifoldInitFunction, getWorkerPipelines, getFailureAnalysis, enableTracing, getTraceId
- **Junction**: setStrategy (simultaneous/roundRobin/etc.), setRounds, setVotingThreshold, setModerator, setPlanner, setActor, setAdjuster, setVerifier, setWorkflowRecipe, executeWorkflow, getFailureAnalysis, enableTracing
- **Splitter**: addPipeline, removePipeline, setOnPipelineFinish, setOnSplitterFinish, getAllChildPipelines, getChildTraceIds, executePipelines, enableTracing
- **Connector**: add, get, setDefaultPath, enableTracing, getTrace
- **Pipeline**: setPipelineName, getPipelineName, getTokenUsage, getPipes, getErrorMessage, hasError, enablePausing, pause, resume, getTraceId
- **DistributionGrid**: setWorker, setRouter, setRoutingPolicy, addPeer, removePeer, replacePeer, dispatchExplicitPeerHandoff, performPeerHandshake, queryRegistries, etc. (the largest gap)

### Phase 3: Pipe Configuration (Large but tractable)
Add semantic-named setters to expose more JVM Pipe.kt setters:
- setModel, setSystemPrompt, setUserPrompt, setMiddlePrompt, setFooterPrompt, setPromptMode
- setTopP, setTopK, setMaxTokens, setRepetitionPenalty, setPresencePenalty, setSeed, setStopSequences
- setReasoning (already exposed), disableReasoning
- setJsonInput, setJsonOutput, setJsonInputInstructions, setJsonOutputInstructions
- setMultimodalInput, addBinary, getBinary
- setContextWindowSettings, setContextWindowSize
- enableSemanticCompression, enableSemanticDecompression
- enableTracing, disableTracing
- setRetry, setTimeout
- And more

### Phase 4: Spec Parity
- Add native functions for spec-only features:
  - TPipe_StreamingCallback
  - TPipe_Error_create, TPipe_Error_getMessage, TPipe_Error_getType
  - TPipe_P2PTransport, TPipe_P2PDescriptor, TPipe_P2PRequirements
  - TPipe_StdioContext, TPipe_HttpContext
  - TPipe_PCP_createRequest, TPipe_PCP_*
  - TPipe_FunctionRegistry_*

### Phase 5: Native Image Build
- Configure nativeCompile gradle task
- Build TPipe.so
- Verify symbol parity with nm -D

---

## Immediate Next Steps

1. **Add 31 missing header declarations** to tpipe-abi.h
2. **Strengthen AbiParityMatrixTest** to detect "Java has function not in header" (the reverse of the current orphan check)
3. **Add a `ReverseOrphanSetIsEmpty` test** that catches the 31 currently-orphaned Java functions
4. **Document each TPipe_* function** in the spec with: target JVM class, JVM method name, semantics, parameter types
5. **Build a JVM-to-Native mapping table** for all 660+ JVM methods, classifying each as: (a) exposed via native ABI, (b) needs new native function, (c) JVM-internal (not for ABI), (d) overload of existing function

