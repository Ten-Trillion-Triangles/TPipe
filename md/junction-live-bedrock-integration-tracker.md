# Junction Live Bedrock Integration Tracker

## Status
- `completed`
- The live Bedrock-backed Junction matrix test is implemented and verified.

## Purpose
This tracker preserves the implementation state for the live Junction proof so it can be resumed after compaction without re-deriving the model lookup, fixture shape, trace paths, or verification commands.

## Bedrock Lookup
- Requested model name: `NVIDIA Nemotron 3 Nano 30B A3B`
- Bedrock model id: `nvidia.nemotron-nano-3-30b`
- Bedrock model arn: `arn:aws:bedrock:us-west-2::foundation-model/nvidia.nemotron-nano-3-30b`
- Region: `us-west-2`
- Status: `ACTIVE`
- Inference type: `ON_DEMAND`
- Modalities: `TEXT` input, `TEXT` output

## Implementation
- Test file: [TPipe-Bedrock/src/test/kotlin/bedrockPipe/JunctionLiveBedrockIntegrationTest.kt](/home/cage/Desktop/Workspaces/TPipe/TPipe/TPipe-Bedrock/src/test/kotlin/bedrockPipe/JunctionLiveBedrockIntegrationTest.kt)
- Fixture shape:
  - each live role is a real `BedrockMultimodalPipe`
  - each role pipe is hosted inside a real `Pipeline`
  - each pipeline is wrapped by a test-only `P2PInterface` container so Junction can address it as a nested harness participant
  - Junction is traced alongside every live role pipeline
- JSON output hardening:
  - discussion roles normalize to `ModeratorDirective` and `ParticipantOpinion`
  - workflow roles normalize to `JunctionWorkflowPhaseResult`
  - the wrapper preserves the raw Bedrock output in `rawOutput` / `modelReasoning`

## Matrix
The live test executes the full supported Junction matrix:
- Discussion strategies:
  - `SIMULTANEOUS`
  - `ROUND_ROBIN`
  - `CONVERSATIONAL`
- Workflow recipes:
  - `VOTE_ACT_VERIFY_REPEAT`
  - `ACT_VOTE_VERIFY_REPEAT`
  - `VOTE_PLAN_ACT_VERIFY_REPEAT`
  - `PLAN_VOTE_ACT_VERIFY_REPEAT`
  - `VOTE_PLAN_OUTPUT_EXIT`
  - `PLAN_VOTE_ADJUST_OUTPUT_EXIT`

## Trace Output
- Default TPipe trace root: `~/.tpipe/debug/trace`
- Live Junction trace directory:
  - `~/.tpipe/debug/trace/Library/junction-live-bedrock/<case>/`
- Saved artifacts:
  - `junction.html`
  - one HTML trace per live role pipeline used in the case
- Verified cases were written for every matrix entry and the files were non-empty after the run.

## Verification
- `./gradlew :TPipe-Bedrock:testClasses --no-daemon`
- `AllowTest=true ./gradlew :TPipe-Bedrock:test --tests bedrockPipe.JunctionLiveBedrockIntegrationTest --no-daemon`

## Notes
- The live test uses real Bedrock calls, so it is gated by `AllowTest=true` plus AWS credentials.
- The wrapper container approach is intentional: it keeps the test in `TPipe-Bedrock` while still exercising Junction through `P2PInterface`-compatible nested containers.
- If this work needs to be resumed, start by opening the live test file and the trace directory above, then rerun the same Bedrock test command.
