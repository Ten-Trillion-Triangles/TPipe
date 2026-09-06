"""AWS MCP control-plane adapter for the AgentCore live-smoke harness.

This file is intentionally written for an agent invoking the AWS MCP
``run_script`` control-plane bridge.  The bridge supplies ``call_boto3``;
local Docker/ECR data-path work is performed separately by the deployment
controller.  Set CONFIG from the deployment wrapper before execution.  The
safe default is PREVIEW; deployment is refused unless APPLY is explicitly
set to True and a non-empty template/image configuration is supplied.
"""

import asyncio
import json
import re
import time


RUN_ID = "tpipe_smoke_REPLACE_ME"
REGION = "us-east-1"
MODEL_ID = ""
TEMPLATE_BODY = ""
APPLY = False
MODE = "preflight"
IMAGES = {
    "HTTP": "",
    "MCP": "",
    "AGUI": "",
}
MANIFEST = []
OBSERVABILITY_LOG_GROUPS = []
TRACE_IDS = []


def _redact(value):
    text = str(value)
    text = re.sub(r"(?i)bearer\s+[^\s,]+", "Bearer [REDACTED]", text)
    text = re.sub(r"(?i)(authorization\s*[:=]\s*)[^,\s]+", r"\1[REDACTED]", text)
    return text[:2048]


def _is_access_denied(value):
    text = str(value).lower()
    return "accessdenied" in text or "access denied" in text or "not authorized" in text


async def _read(service_name, operation_name, params=None):
    try:
        value = await call_boto3(
            service_name=service_name,
            operation_name=operation_name,
            region_name=REGION,
            params=params or {},
        )
        return {"status": "ok", "operation": operation_name, "value": value}
    except Exception as error:
        status = "access_denied" if _is_access_denied(error) else "error"
        return {"status": status, "operation": operation_name, "error": _redact(error)}


def _stack_name(protocol):
    return f"tpipe-smoke-{protocol.lower()}-{RUN_ID.replace('_', '-')}"


def _runtime_name(protocol):
    return f"tpipe_{protocol.lower()}_{RUN_ID}"


def _endpoint_name(protocol):
    return f"endpoint_{protocol.lower()}_{RUN_ID}"


def _image_repository(image_uri):
    if not image_uri:
        return ""
    without_digest = image_uri.split("@", 1)[0]
    without_tag = without_digest.rsplit(":", 1)[0]
    return without_tag.split("/", 1)[1] if "/" in without_tag else without_tag


def _desired_names():
    names = {_stack_name(protocol) for protocol in IMAGES}
    names.update(_runtime_name(protocol) for protocol in IMAGES)
    names.update(_endpoint_name(protocol) for protocol in IMAGES)
    names.update(_image_repository(uri) for uri in IMAGES.values())
    return {name for name in names if name}


async def _preflight():
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,47}", RUN_ID):
        return {"status": "blocked", "reason": "invalid run ID"}
    if REGION == "" or not all(IMAGES.values()):
        return {"status": "blocked", "reason": "region and all three ARM64 image URIs are required"}

    reads = [
        _read("sts", "GetCallerIdentity"),
        _read("cloudformation", "ListStacks"),
        _read("ecr", "DescribeRepositories"),
        _read("bedrock", "ListFoundationModels"),
        _read("service-quotas", "ListServiceQuotas", {"ServiceCode": "bedrock-agentcore"}),
    ]
    reads.extend(
        _read("bedrock-agentcore-control", operation)
        for operation in (
            "ListAgentRuntimes",
            "ListGateways",
            "ListMemories",
            "ListPolicyEngines",
            "ListWorkloadIdentities",
            "ListHarnesses",
            "ListEvaluators",
            "ListOnlineEvaluationConfigs",
        )
    )
    results = await asyncio.gather(*reads)
    denied = [item["operation"] for item in results if item["status"] == "access_denied"]
    errors = [item for item in results if item["status"] == "error"]
    if denied:
        return {
            "status": "blocked",
            "reason": "AccessDenied is unknown state; deployment is not allowed",
            "accessDeniedChecks": denied,
        }
    if errors:
        return {
            "status": "blocked",
            "reason": "preflight could not complete",
            "errors": [{"operation": item["operation"], "error": item["error"]} for item in errors],
        }

    existing = set()
    stacks = next(item["value"] for item in results if item["operation"] == "ListStacks")
    existing.update(item.get("StackName", "") for item in stacks.get("StackSummaries", []))
    repositories = next(item["value"] for item in results if item["operation"] == "DescribeRepositories")
    existing.update(item.get("repositoryName", "") for item in repositories.get("repositories", []))
    for item in results:
        if item["status"] != "ok" or item["operation"] in {
            "GetCallerIdentity",
            "DescribeRegions",
            "ListServiceQuotas",
            "ListFoundationModels",
            "ListStacks",
            "DescribeRepositories",
        }:
            continue
        value = item["value"]
        for collection in value.values():
            if not isinstance(collection, list):
                continue
            for resource in collection:
                if not isinstance(resource, dict):
                    continue
                for key in (
                    "name",
                    "id",
                    "arn",
                    "agentRuntimeName",
                    "agentRuntimeId",
                    "gatewayId",
                    "memoryId",
                    "policyEngineId",
                    "workloadIdentityName",
                    "harnessName",
                    "harnessId",
                    "evaluatorName",
                    "evaluatorId",
                    "onlineEvaluationConfigName",
                    "onlineEvaluationConfigId",
                ):
                    if resource.get(key):
                        existing.add(resource[key])
    desired = _desired_names()
    collisions = sorted(
        name
        for name in existing
        if name and any(name == target or name.startswith(target) or target.startswith(name) for target in desired)
    )

    models = next(item["value"] for item in results if item["operation"] == "ListFoundationModels")
    active_models = {
        item.get("modelId")
        for item in models.get("modelSummaries", [])
        if item.get("modelLifecycle", {}).get("status") == "ACTIVE"
    }
    model_access = not MODEL_ID or MODEL_ID in active_models
    return {
        "status": "blocked" if collisions or not model_access else "ready",
        "accountId": next(item["value"].get("Account", "") for item in results if item["operation"] == "GetCallerIdentity"),
        "region": REGION,
        "quotaCount": len(next(item["value"].get("Quotas", []) for item in results if item["operation"] == "ListServiceQuotas")),
        "activeModelCount": len(active_models),
        "modelAccess": model_access,
        "existingNames": sorted(existing),
        "collisions": collisions,
        "desiredNames": sorted(desired),
    }


async def _create_stack(protocol, image_uri):
    params = {
        "StackName": _stack_name(protocol),
        "TemplateBody": TEMPLATE_BODY,
        "Parameters": [
            {"ParameterKey": "RuntimeName", "ParameterValue": _runtime_name(protocol)},
            {"ParameterKey": "RuntimeProtocol", "ParameterValue": protocol},
            {"ParameterKey": "RuntimeImageUri", "ParameterValue": image_uri},
            {"ParameterKey": "RuntimeEndpointName", "ParameterValue": _endpoint_name(protocol)},
            {"ParameterKey": "RuntimeEndpointVersion", "ParameterValue": "1"},
        ],
        "Tags": [
            {"Key": "TPipeSmokeRun", "Value": RUN_ID},
            {"Key": "ManagedBy", "Value": "TPipeAgentCoreLiveSmoke"},
        ],
        "Capabilities": ["CAPABILITY_IAM"],
        "OnFailure": "DELETE",
    }
    try:
        response = await call_boto3(
            service_name="cloudformation",
            operation_name="CreateStack",
            region_name=REGION,
            params=params,
        )
        stack_id = response.get("StackId", "")
        if not stack_id:
            raise RuntimeError("CreateStack returned no StackId")
        # Record immediately in the returned manifest before waiting for the next stack.
        created = {
            "type": "cloudformation-stack",
            "name": _stack_name(protocol),
            "stackId": stack_id,
            "region": REGION,
            "createdAt": "controller-create-response",
        }
        return {"status": "created", "resource": created}
    except Exception as error:
        return {"status": "error", "error": _redact(error)}


async def _wait_stack(stack_id):
    for _ in range(120):
        response = await _read("cloudformation", "DescribeStacks", {"StackName": stack_id})
        if response["status"] != "ok":
            return response
        stack = response["value"].get("Stacks", [{}])[0]
        status = stack.get("StackStatus", "")
        if status.endswith("_COMPLETE"):
            outputs = {
                item.get("OutputKey", ""): item.get("OutputValue", "")
                for item in stack.get("Outputs", [])
                if item.get("OutputKey") and item.get("OutputValue")
            }
            return {"status": "ready", "stackStatus": status, "outputs": outputs}
        if status.endswith("_FAILED") or status.endswith("_ROLLBACK_COMPLETE"):
            return {"status": "failed", "stackStatus": status}
        await asyncio.sleep(5)
    return {"status": "blocked", "reason": "CloudFormation stack did not reach a terminal state in 10 minutes"}


async def _deploy():
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    if not TEMPLATE_BODY:
        return {"status": "blocked", "reason": "exact template body is required"}
    preflight = await _preflight()
    if preflight.get("status") != "ready":
        return {"status": "blocked", "preflight": preflight}

    created = []
    outputs = {}
    for protocol, image_uri in IMAGES.items():
        result = await _create_stack(protocol, image_uri)
        if result["status"] != "created":
            return {"status": "failed", "created": created, "error": result.get("error", "create failed")}
        created.append(result["resource"])
        waited = await _wait_stack(result["resource"]["stackId"])
        if waited.get("status") != "ready":
            return {"status": "failed", "created": created, "stack": waited}
        outputs[protocol] = waited.get("outputs", {})
    return {"status": "ready", "created": created, "outputs": outputs}


async def _observability():
    if not OBSERVABILITY_LOG_GROUPS or not TRACE_IDS:
        return {
            "status": "blocked",
            "reason": "run-owned log groups and trace IDs are required for the AWS observability check",
        }
    start_time = int((time.time() - 900) * 1000)
    reads = [
        _read("cloudwatch", "ListMetrics", {"Namespace": "AWS/Bedrock-AgentCore"}),
        _read("xray", "BatchGetTraces", {"traceIds": TRACE_IDS[:5]}),
    ]
    reads.extend(
        _read(
            "logs",
            "FilterLogEvents",
            {"logGroupName": log_group, "startTime": start_time, "limit": 20},
        )
        for log_group in OBSERVABILITY_LOG_GROUPS
    )
    results = await asyncio.gather(*reads)
    denied = [item["operation"] for item in results if item["status"] == "access_denied"]
    errors = [item for item in results if item["status"] == "error"]
    if denied or errors:
        return {
            "status": "blocked",
            "reason": "AWS observability read was incomplete",
            "accessDeniedChecks": denied,
            "errors": [{"operation": item["operation"], "error": item["error"]} for item in errors],
        }
    metrics = next(item["value"] for item in results if item["operation"] == "ListMetrics")
    traces = next(item["value"] for item in results if item["operation"] == "BatchGetTraces")
    log_events = sum(
        len(item["value"].get("events", []))
        for item in results
        if item["operation"] == "FilterLogEvents"
    )
    trace_documents = len(traces.get("traces", []))
    metric_count = len(metrics.get("Metrics", []))
    return {
        "status": "ready" if metric_count and log_events and trace_documents else "blocked",
        "metricCount": metric_count,
        "logEventCount": log_events,
        "traceCount": trace_documents,
        "ingestionWindowSeconds": 900,
    }


async def _delete_resource(resource):
    if resource.get("region") != REGION or not _owns_manifest_resource(resource):
        return {"status": "blocked", "reason": "manifest identity is outside this run"}
    resource_type = resource.get("type")
    try:
        if resource_type == "cloudformation-stack":
            if not resource.get("stackId"):
                return {"status": "blocked", "reason": "stackId is required"}
            await call_boto3(
                service_name="cloudformation",
                operation_name="DeleteStack",
                region_name=REGION,
                params={"StackName": resource["stackId"]},
            )
        elif resource_type == "runtime-endpoint":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteAgentRuntimeEndpoint",
                region_name=REGION,
                params={
                    "agentRuntimeId": resource["parentId"],
                    "endpointName": resource["name"],
                },
            )
        elif resource_type == "runtime":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteAgentRuntime",
                region_name=REGION,
                params={"agentRuntimeId": resource["id"]},
            )
        elif resource_type == "gateway-target":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteGatewayTarget",
                region_name=REGION,
                params={
                    "gatewayIdentifier": resource["parentId"],
                    "targetId": resource["id"],
                },
            )
        elif resource_type == "gateway":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteGateway",
                region_name=REGION,
                params={"gatewayIdentifier": resource["id"]},
            )
        elif resource_type == "memory":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteMemory",
                region_name=REGION,
                params={"memoryId": resource["id"]},
            )
        elif resource_type == "policy":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeletePolicy",
                region_name=REGION,
                params={
                    "policyEngineId": resource["parentId"],
                    "policyId": resource["id"],
                },
            )
        elif resource_type == "policy-engine":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeletePolicyEngine",
                region_name=REGION,
                params={"policyEngineId": resource["id"]},
            )
        elif resource_type == "resource-policy":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteResourcePolicy",
                region_name=REGION,
                params={"resourceArn": resource["arn"]},
            )
        elif resource_type == "workload-identity":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteWorkloadIdentity",
                region_name=REGION,
                params={"name": resource["name"]},
            )
        elif resource_type == "harness-endpoint":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteHarnessEndpoint",
                region_name=REGION,
                params={
                    "harnessId": resource["parentId"],
                    "endpointName": resource["name"],
                },
            )
        elif resource_type == "harness":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteHarness",
                region_name=REGION,
                params={"harnessId": resource["id"]},
            )
        elif resource_type == "evaluator":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteEvaluator",
                region_name=REGION,
                params={"evaluatorId": resource["id"]},
            )
        elif resource_type == "online-evaluation-config":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteOnlineEvaluationConfig",
                region_name=REGION,
                params={"onlineEvaluationConfigId": resource["id"]},
            )
        elif resource_type == "oauth2-credential-provider":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteOauth2CredentialProvider",
                region_name=REGION,
                params={"name": resource["name"]},
            )
        elif resource_type == "api-key-credential-provider":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteApiKeyCredentialProvider",
                region_name=REGION,
                params={"name": resource["name"]},
            )
        elif resource_type == "ecr-image":
            await call_boto3(
                service_name="ecr",
                operation_name="BatchDeleteImage",
                region_name=REGION,
                params={"repositoryName": resource["name"], "imageIds": [{"imageDigest": resource["id"]}]},
            )
        elif resource_type == "ecr-repository":
            await call_boto3(
                service_name="ecr",
                operation_name="DeleteRepository",
                region_name=REGION,
                params={"repositoryName": resource["name"], "force": False},
            )
        elif resource_type == "log-group":
            await call_boto3(
                service_name="logs",
                operation_name="DeleteLogGroup",
                region_name=REGION,
                params={"logGroupName": resource["name"]},
            )
        elif resource_type == "iam-role":
            role_policies = await call_boto3(
                service_name="iam",
                operation_name="ListRolePolicies",
                region_name=REGION,
                params={"RoleName": resource["name"]},
            )
            for policy_name in role_policies.get("PolicyNames", []):
                await call_boto3(
                    service_name="iam",
                    operation_name="DeleteRolePolicy",
                    region_name=REGION,
                    params={"RoleName": resource["name"], "PolicyName": policy_name},
                )
            await call_boto3(
                service_name="iam",
                operation_name="DeleteRole",
                region_name=REGION,
                params={"RoleName": resource["name"]},
            )
        else:
            return {"status": "blocked", "reason": f"unsupported manifest type: {resource_type}"}
        return {"status": "deleted", "resource": resource.get("name", "")}
    except Exception as error:
        return {"status": "failed", "error": _redact(error)}


async def _cleanup():
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    deleted = []
    for resource in reversed(MANIFEST):
        result = await _delete_resource(resource)
        if result["status"] in ("blocked", "failed"):
            return {"status": result["status"], "deleted": deleted, "error": result.get("reason", result.get("error", ""))}
        deleted.append(resource.get("name", ""))
    scan = await _post_cleanup_scan()
    if scan.get("status") != "clean":
        return {
            "status": "blocked",
            "deleted": deleted,
            "remaining": scan.get("remaining", []),
            "reason": scan.get("reason", "post-cleanup scan was incomplete"),
        }
    return {"status": "clean", "deleted": deleted, "remaining": []}


def _owns_manifest_resource(resource):
    run_tag = resource.get("runTag")
    if run_tag is not None:
        return run_tag == RUN_ID
    legacy_name = str(resource.get("name", ""))
    legacy_arn = str(resource.get("arn", ""))
    normalized = RUN_ID.replace("_", "-")
    return RUN_ID in legacy_name or normalized in legacy_name or RUN_ID in legacy_arn or normalized in legacy_arn


async def _post_cleanup_scan():
    """Read exact service inventories and return only live run-owned resources."""
    reads = [
        _read("cloudformation", "ListStacks"),
        _read("ecr", "DescribeRepositories"),
        _read("iam", "ListRoles"),
        _read("logs", "DescribeLogGroups", {"logGroupNamePrefix": "/aws/bedrock-agentcore/"}),
        _read("bedrock-agentcore-control", "ListAgentRuntimes"),
        _read("bedrock-agentcore-control", "ListGateways"),
        _read("bedrock-agentcore-control", "ListMemories"),
        _read("bedrock-agentcore-control", "ListPolicyEngines"),
        _read("bedrock-agentcore-control", "ListWorkloadIdentities"),
        _read("bedrock-agentcore-control", "ListHarnesses"),
        _read("bedrock-agentcore-control", "ListOnlineEvaluationConfigs"),
    ]
    results = await asyncio.gather(*reads)
    denied = [item["operation"] for item in results if item["status"] == "access_denied"]
    errors = [item for item in results if item["status"] == "error"]
    if denied or errors:
        return {
            "status": "blocked",
            "reason": "post-cleanup scan was incomplete; AccessDenied is unknown state",
            "accessDeniedChecks": denied,
            "errors": [{"operation": item["operation"], "error": item["error"]} for item in errors],
        }

    remaining = []
    for item in results:
        operation = item["operation"]
        value = item["value"]
        if operation == "ListStacks":
            for stack in value.get("StackSummaries", []):
                if stack.get("StackStatus") != "DELETE_COMPLETE" and _owns_manifest_resource({"name": stack.get("StackName", "")}):
                    remaining.append({"type": "cloudformation-stack", "name": stack.get("StackName", "")})
        elif operation == "DescribeRepositories":
            for repo in value.get("repositories", []):
                if _owns_manifest_resource({"name": repo.get("repositoryName", "")}):
                    remaining.append({"type": "ecr-repository", "name": repo.get("repositoryName", "")})
        elif operation == "ListRoles":
            for role in value.get("Roles", []):
                if _owns_manifest_resource({"name": role.get("RoleName", ""), "arn": role.get("Arn", "")}):
                    remaining.append({"type": "iam-role", "name": role.get("RoleName", "")})
        elif operation == "DescribeLogGroups":
            for group in value.get("logGroups", []):
                if _owns_manifest_resource({"name": group.get("logGroupName", "")}):
                    remaining.append({"type": "log-group", "name": group.get("logGroupName", "")})
        else:
            collections = [entry for entry in value.values() if isinstance(entry, list)]
            for collection in collections:
                for resource in collection:
                    if isinstance(resource, dict):
                        identity = {
                            "name": next((resource.get(key) for key in ("name", "agentRuntimeName", "gatewayId", "memoryId", "policyEngineId", "harnessName", "harnessId", "onlineEvaluationConfigName", "onlineEvaluationConfigId") if resource.get(key)), ""),
                            "arn": next((resource.get(key) for key in ("arn", "agentRuntimeArn", "policyEngineArn") if resource.get(key)), ""),
                        }
                        if _owns_manifest_resource(identity):
                            remaining.append({"type": operation, "name": identity["name"] or identity["arn"]})
    return {"status": "clean" if not remaining else "blocked", "remaining": remaining}


async def _main():
    if MODE == "preflight":
        return await _preflight()
    if MODE == "deploy":
        return await _deploy()
    if MODE == "observability":
        return await _observability()
    if MODE == "cleanup":
        return await _cleanup()
    return {"status": "blocked", "reason": "MODE must be preflight, deploy, cleanup, or observability"}


result = await _main()
result
