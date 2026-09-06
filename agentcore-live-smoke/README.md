# TPipe AgentCore live smoke harness

This application is opt-in. It does not participate in the repository's
default Gradle test lifecycle and it never discovers or deletes resources by
prefix alone.

## Local validation

```bash
./gradlew -PagentcoreLiveSmokeTests=true :agentcore-live-smoke:test
./gradlew :agentcore-live-smoke:shadowJar
```

The `agentcore-live-smoke:test` task is opt-in so a repository-wide
`./gradlew test` does not execute or imply a live-smoke run. The `run` task
also requires explicitly configured run-owned endpoints and identifiers; when
those are absent it writes a diagnostic report and exits nonzero with
`BLOCKED` cases.

The three runtime images use the same shaded application artifact and distinct
entrypoint configuration:

```bash
docker buildx build --platform linux/arm64 \
  -f agentcore-live-smoke/src/main/docker/Dockerfile.http \
  -t <run-owned-ecr-repository>/http:<run-id> \
  agentcore-live-smoke/build/libs --push
```

For the MCP and AG-UI images, substitute `Dockerfile.mcp` or
`Dockerfile.agui`. The produced image must be tagged with the run ID and pushed
to a run-owned ECR repository. Runtime containers receive no AWS credentials.

## AWS MCP deployment controller

`controller/aws_mcp_controller.py` is the control-plane adapter. Copy its
source into the AWS MCP `run_script` call after replacing the explicit
configuration constants with the current run ID, three pushed ARM64 image
URIs, and the pinned CloudFormation template body. It performs a read-only
preflight by default. Set `APPLY = True` only for the explicitly authorized
run and select `MODE = "deploy"` or `MODE = "cleanup"`; its returned
`created` resources must be written to the local `manifest.json` immediately.

The adapter refuses exact or prefix collisions, treats `AccessDenied` as
unknown rather than absent, records stack IDs before polling, and deletes
CloudFormation/ECR/IAM/Logs resources only by exact manifest identity. The
Kotlin `LiveSmokeDeploymentController` adds the same checks and supplies the
post-cleanup scan. AgentCore resources are deleted by
`AgentCoreLiveSmokeCleanup` through the pinned SDK.

The controller is intentionally separate from the runtime images. The AWS MCP
connection performs control-plane operations, while the local smoke process may
use the machine's existing AWS credential chain for signed data-plane calls,
Bedrock, and ECR upload. Credentials and bearer tokens never enter a runtime
image, trace, log, or report.

Run the same adapter with `MODE = "observability"`, supplying only owned log
group names and the trace IDs returned by the smoke run. It queries service
metrics, log-event counts, and X-Ray trace presence after a bounded 15-minute
window and returns counts rather than raw log/trace content. Store that
redacted result alongside `report.json` as the AWS-side observability
evidence.

## Explicit live invocation

The controller/deployment layer must first create three unique CloudFormation
stacks from `TPipe-AgentCore/src/main/resources/cloudformation/tpipe-agentcore.yaml`,
one each for `HTTP`, `MCP`, and `AGUI`, and write every successful create
response to the run manifest. It must use the AWS MCP admin connection for
control-plane calls. The JVM may use the machine's existing AWS credential
chain for signed data-plane assertions, but no credential material is passed
to a runtime image.

After deployment, the AWS MCP controller must return the run-owned stack
outputs and the deployment wrapper must construct the Kotlin
`LiveSmokeDeploymentController` with an `AgentCoreLiveSmokeCleanup` callback.
That wrapper owns the sequence `deploy -> run -> stop/close -> exact manifest
cleanup -> post-cleanup scan`. The standalone JVM `run` task is the assertion
process; it intentionally cannot claim `cleanupStatus=CLEAN` on its own.

After the wrapper has supplied the run-owned endpoints and identifiers, the
smoke process can be invoked as follows:

```bash
TPIPE_AGENTCORE_REGION=us-east-1 \
TPIPE_AGENTCORE_RUN_ID=tpipe_smoke_20260906_ab12cd \
TPIPE_AGENTCORE_HTTP_ENDPOINT=https://bedrock-agentcore.us-east-1.amazonaws.com \
TPIPE_AGENTCORE_MCP_ENDPOINT=https://bedrock-agentcore.us-east-1.amazonaws.com \
TPIPE_AGENTCORE_AGUI_ENDPOINT=https://bedrock-agentcore.us-east-1.amazonaws.com \
TPIPE_AGENTCORE_HTTP_RUNTIME_ARN=arn:...:runtime/... \
TPIPE_AGENTCORE_MCP_RUNTIME_ARN=arn:...:runtime/... \
TPIPE_AGENTCORE_AGUI_RUNTIME_ARN=arn:...:runtime/... \
TPIPE_AGENTCORE_GATEWAY_ENDPOINT=https://... \
TPIPE_AGENTCORE_MEMORY_ID=... \
TPIPE_AGENTCORE_MODEL_ID=... \
TPIPE_AGENTCORE_EVALUATOR_ID=... \
TPIPE_AGENTCORE_EVALUATION_TRACE_ID=... \
TPIPE_AGENTCORE_EVALUATION_LOG_GROUP=... \
TPIPE_AGENTCORE_EVALUATION_SERVICE_NAME=... \
TPIPE_AGENTCORE_ONLINE_EVALUATION_CONFIG_ID=... \
TPIPE_AGENTCORE_POLICY_GATEWAY_ID=... \
TPIPE_AGENTCORE_POLICY_ENGINE_ID=... \
TPIPE_AGENTCORE_WORKLOAD_NAME=... \
TPIPE_AGENTCORE_BROWSER_STABLE_URL=https://example.com \
TPIPE_AGENTCORE_MANIFEST=build/agentcore-live-smoke/manifest.json \
./gradlew :agentcore-live-smoke:run
```

Missing optional service identifiers are reported as `BLOCKED`; the harness
does not silently substitute an existing resource. OAuth/API-key provider
cases are `NOT_SAFELY_TESTABLE` unless the deployment manifest records a
disposable provider and exact delete operation. If
`TPIPE_AGENTCORE_IDENTITY_VERIFY_ENDPOINT` is omitted, the identity case
starts a loopback verifier for the duration of the case, records only token
fingerprints in memory, and stops it before returning.

## Teardown contract

The controller must stop sessions first, close clients, delete only exact
manifest entries whose name/ARN and run scope match, delete the three recorded
stack IDs, and perform a read-only post-cleanup scan. It may report
`cleanupStatus=CLEAN` only when that scan finds no run-owned resources.

If any resource identity does not exactly match its manifest entry, cleanup
must stop and the report must remain `BLOCKED`. A2A is always reported as
`UNSUPPORTED` because it is not part of TPipe AgentCore support.
