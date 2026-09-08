"""AWS MCP control-plane adapter for the AgentCore live-smoke harness.

This file is intentionally written for an agent invoking the AWS MCP
``run_script`` control-plane bridge.  The bridge supplies ``call_boto3``;
local Docker/ECR data-path work is performed separately by the deployment
controller.  Set CONFIG from the deployment wrapper before execution.  The
safe default is PREVIEW; deployment is refused unless APPLY is explicitly
set to True and a non-empty template/image configuration is supplied.
"""

import asyncio
import datetime
import json
import re
import time


RUN_ID = "tpipe_smoke_REPLACE_ME"
REGION = "us-east-1"
MODEL_ID = ""
TEMPLATE_BODY = ""
FIXTURE_TEMPLATE_BODY = ""
EVALUATION_TEMPLATE_BODY = ""
CAPACITY_TEMPLATE_BODY = ""
CAPACITY_PROVIDER_ID = ""
CAPACITY_PROVIDER_ARN = ""
STAGE_PROTOCOL = ""
INSTANCES_IMAGE = ""
RUNTIME_STACK_ID = ""
GATEWAY_ID = ""
GATEWAY_ARN = ""
GATEWAY_URL = ""
CONSENT_PROVIDER_ARN = ""
EVALUATION_STACK_ID = ""
APPLY = False
MODE = "preflight"
ECR_REPOSITORIES_READY = False
IMAGES = {
    "HTTP": "",
    "MCP": "",
    "AGUI": "",
}
MANIFEST = []
OBSERVABILITY_LOG_GROUPS = []
TRACE_IDS = []

# Every read in this inventory is intentional.  A failed or denied read is
# never interpreted as an empty inventory because that would make teardown
# unverifiable and could leave a run-owned resource behind.
AGENTCORE_INVENTORY = (
    "ListAgentRuntimes",
    "ListGateways",
    "ListMemories",
    "ListPolicyEngines",
    "ListWorkloadIdentities",
    "ListHarnesses",
    "ListEvaluators",
    "ListOnlineEvaluationConfigs",
    "ListDatasets",
    "ListConfigurationBundles",
    "ListCapacityProviders",
    "ListOauth2CredentialProviders",
    "ListApiKeyCredentialProviders",
    "ListBrowsers",
    "ListBrowserProfiles",
    "ListCodeInterpreters",
)

EXTERNAL_INVENTORY = (
    ("lambda", "ListFunctions", {}),
    ("secretsmanager", "ListSecrets", {}),
    ("cognito-idp", "ListUserPools", {"MaxResults": 60}),
    ("ec2", "DescribeVpcs", {}),
    ("ec2", "DescribeSubnets", {}),
    ("ec2", "DescribeSecurityGroups", {}),
    ("ec2", "DescribeRouteTables", {}),
    ("ec2", "DescribeInternetGateways", {}),
    ("ec2", "DescribeInstances", {}),
    ("iam", "ListInstanceProfiles", {}),
)


def _redact(value):
    text = str(value)
    text = re.sub(r"(?i)bearer\s+[^\s,]+", "Bearer [REDACTED]", text)
    text = re.sub(r"(?i)(authorization\s*[:=]\s*)[^,\s]+", r"\1[REDACTED]", text)
    return text[:2048]


def _policy_action_name(gateway_arn, target_name, tool_name):
    """Return the Gateway target/tool action name used in Dogwood policies."""
    return f"{target_name}___{tool_name}"


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


def _fixture_stack_name():
    return f"tpipe-smoke-fixtures-{RUN_ID.replace('_', '-')}"


def _evaluation_stack_name():
    return f"tpipe-smoke-evaluations-{RUN_ID.replace('_', '-')}"


def _capacity_stack_name():
    return f"tpipe-smoke-capacity-{RUN_ID.replace('_', '-')}"


def _registry_name():
    return f"tpipe-smoke-registry-{RUN_ID.replace('_', '-')}"


def _registry_record_name():
    # Registry record names currently accept only the compact alphanumeric form.
    return "tpipe" + RUN_ID.replace("_", "")


def _oauth_pool_name():
    return f"tpipe-smoke-pool-{RUN_ID.replace('_', '-')}"


def _oauth_domain_name():
    return f"tpipe-smoke-{RUN_ID.replace('_', '-')}"


def _oauth_m2m_client_name():
    return f"tpipe-smoke-m2m-{RUN_ID.replace('_', '-')}"


def _oauth_auth_client_name():
    return f"tpipe-smoke-auth-{RUN_ID.replace('_', '-')}"


def _oauth_auth_provider_name():
    return f"tpipe-oauth-auth-{RUN_ID.replace('_', '-')}"


def _workload_name():
    return f"tpipe-workload-{RUN_ID.replace('_', '-')}"


def _consent_portal_name():
    return f"tpipe-consent-{RUN_ID.replace('_', '-')}"


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
    if CAPACITY_TEMPLATE_BODY:
        names.update({
            _stack_name("INSTANCES"),
            _runtime_name("INSTANCES"),
            _endpoint_name("INSTANCES"),
        })
    names.add(f"tpipe-gateway-{RUN_ID.replace('_', '-')}")
    names.add(f"tpipe-target-{RUN_ID.replace('_', '-')}")
    names.add(_registry_name())
    names.add(_registry_record_name())
    names.update({
        _oauth_pool_name(),
        _oauth_domain_name(),
        _oauth_m2m_client_name(),
        _oauth_auth_client_name(),
        _oauth_auth_provider_name(),
        _workload_name(),
        _consent_portal_name(),
    })
    if FIXTURE_TEMPLATE_BODY:
        names.add(_fixture_stack_name())
    if EVALUATION_TEMPLATE_BODY:
        names.add(_evaluation_stack_name())
    if CAPACITY_TEMPLATE_BODY:
        names.add(_capacity_stack_name())
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
        for operation in AGENTCORE_INVENTORY
    )
    reads.extend(_read("agent-registry-control", operation) for operation in ("ListRegistries",))
    reads.extend(_read(service, operation, params) for service, operation, params in EXTERNAL_INVENTORY)
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
    existing.update(
        item.get("StackName", "")
        for item in stacks.get("StackSummaries", [])
        if item.get("StackStatus") != "DELETE_COMPLETE"
    )
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
                    "targetId",
                    "targetName",
                    "memoryId",
                    "policyEngineId",
                    "workloadIdentityName",
                    "harnessName",
                    "harnessId",
                    "evaluatorName",
                    "evaluatorId",
                    "onlineEvaluationConfigName",
                    "onlineEvaluationConfigId",
                    "UserPoolName",
                    "ClientName",
                    "Domain",
                    "Name",
                ):
                    if resource.get(key):
                        existing.add(resource[key])
    desired = _desired_names()
    collisions = sorted(
        name
        for name in existing
        if name and not (ECR_REPOSITORIES_READY and name in {_image_repository(uri) for uri in IMAGES.values()})
        and any(name == target or name.startswith(target) or target.startswith(name) for target in desired)
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


async def _create_ecr_repositories():
    """Create only the run-owned repositories needed for the later image push."""
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    created = []
    try:
        for image_uri in IMAGES.values():
            repository_name = _image_repository(image_uri)
            response = await call_boto3(
                service_name="ecr",
                operation_name="CreateRepository",
                region_name=REGION,
                params={
                    "repositoryName": repository_name,
                    "imageTagMutability": "IMMUTABLE",
                    "imageScanningConfiguration": {"scanOnPush": True},
                    "tags": [
                        {"Key": "TPipeSmokeRun", "Value": RUN_ID},
                        {"Key": "ManagedBy", "Value": "TPipeAgentCoreLiveSmoke"},
                    ],
                },
            )
            repository = response.get("repository", {})
            resource = {
                "type": "ecr-repository",
                "name": repository_name,
                "arn": repository.get("repositoryArn", ""),
                "region": REGION,
                "runTag": RUN_ID,
                "createdAt": "controller-create-response",
            }
            created.append(resource)
        return {"status": "created", "created": created}
    except Exception as error:
        return {"status": "failed", "created": created, "error": _redact(error)}


async def _create_stack(protocol, image_uri, extra_parameters=None, runtime_protocol=None):
    params = {
        "StackName": _stack_name(protocol),
        "TemplateBody": TEMPLATE_BODY,
        "Parameters": [
            {"ParameterKey": "RuntimeName", "ParameterValue": _runtime_name(protocol)},
            {"ParameterKey": "RuntimeProtocol", "ParameterValue": runtime_protocol or protocol},
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
    for key, value in (extra_parameters or {}).items():
        params["Parameters"].append({"ParameterKey": key, "ParameterValue": value})
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
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        return {"status": "created", "resource": created}
    except Exception as error:
        return {"status": "error", "error": _redact(error)}


async def _create_named_template_stack(stack_name, template_body, parameters):
    """Create one auxiliary stack and return its exact manifest entry."""
    params = {
        "StackName": stack_name,
        "TemplateBody": template_body,
        "Parameters": [
            {"ParameterKey": key, "ParameterValue": value}
            for key, value in parameters.items()
        ],
        "Tags": [
            {"Key": "TPipeSmokeRun", "Value": RUN_ID},
            {"Key": "ManagedBy", "Value": "TPipeAgentCoreLiveSmoke"},
        ],
        "Capabilities": ["CAPABILITY_IAM", "CAPABILITY_NAMED_IAM"],
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
            raise RuntimeError(f"CreateStack returned no StackId for {stack_name}")
        return {
            "status": "created",
            "resource": {
                "type": "cloudformation-stack",
                "name": stack_name,
                "stackId": stack_id,
                "region": REGION,
                "runTag": RUN_ID,
                "createdAt": "controller-create-response",
            },
        }
    except Exception as error:
        return {"status": "error", "error": _redact(error)}


async def _create_fixture_stack():
    """Create the Lambda/secret/network/IAM fixture as one exact stack."""
    if not FIXTURE_TEMPLATE_BODY:
        return None
    params = {
        "StackName": _fixture_stack_name(),
        "TemplateBody": FIXTURE_TEMPLATE_BODY,
        "Parameters": [{"ParameterKey": "RunId", "ParameterValue": RUN_ID}],
        "Tags": [
            {"Key": "TPipeSmokeRun", "Value": RUN_ID},
            {"Key": "ManagedBy", "Value": "TPipeAgentCoreLiveSmoke"},
        ],
        "Capabilities": ["CAPABILITY_IAM", "CAPABILITY_NAMED_IAM"],
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
            raise RuntimeError("fixture CreateStack returned no StackId")
        return {
            "status": "created",
            "resource": {
                "type": "cloudformation-stack",
                "name": _fixture_stack_name(),
                "stackId": stack_id,
                "region": REGION,
                "runTag": RUN_ID,
                "createdAt": "controller-create-response",
            },
        }
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


async def _existing_stack_outputs(stack_name):
    """Read outputs from one exact run-owned stack for staged MCP execution."""
    response = await _read("cloudformation", "DescribeStacks", {"StackName": stack_name})
    if response["status"] != "ok":
        return response
    stacks = response["value"].get("Stacks", [])
    if not stacks:
        return {"status": "blocked", "reason": f"stack {stack_name} was not found"}
    stack = stacks[0]
    if stack.get("StackStatus") != "CREATE_COMPLETE":
        return {"status": "blocked", "reason": f"stack {stack_name} is {stack.get('StackStatus', '')}"}
    return {
        "status": "ready",
        "stackId": stack.get("StackId", ""),
        "outputs": {
            item.get("OutputKey", ""): item.get("OutputValue", "")
            for item in stack.get("Outputs", [])
            if item.get("OutputKey") and item.get("OutputValue")
        },
    }


async def _wait_stack_deleted(stack_id):
    """Wait for an exact stack deletion before inventory is trusted."""
    for _ in range(120):
        response = await _read("cloudformation", "DescribeStacks", {"StackName": stack_id})
        if response["status"] == "error" and "does not exist" in response.get("error", "").lower():
            return {"status": "deleted"}
        if response["status"] != "ok":
            return response
        status = response["value"].get("Stacks", [{}])[0].get("StackStatus", "")
        if status == "DELETE_COMPLETE":
            return {"status": "deleted"}
        if status == "DELETE_FAILED":
            return {"status": "failed", "stackStatus": status}
        await asyncio.sleep(5)
    return {"status": "blocked", "reason": "CloudFormation stack deletion did not complete in 10 minutes"}


async def _wait_gateway(gateway_id):
    for _ in range(120):
        response = await _read(
            "bedrock-agentcore-control",
            "GetGateway",
            {"gatewayIdentifier": gateway_id},
        )
        if response["status"] != "ok":
            return response
        gateway = response["value"]
        status = gateway.get("status", "")
        if status == "READY":
            return {"status": "ready", "gateway": gateway}
        if status in {"FAILED", "UPDATE_UNSUCCESSFUL", "DELETE_UNSUCCESSFUL"}:
            return {"status": "failed", "gateway": gateway}
        await asyncio.sleep(5)
    return {"status": "blocked", "reason": "Gateway did not become READY in 10 minutes"}


async def _wait_gateway_target(gateway_id, target_id):
    for _ in range(120):
        response = await _read(
            "bedrock-agentcore-control",
            "GetGatewayTarget",
            {"gatewayIdentifier": gateway_id, "targetId": target_id},
        )
        if response["status"] != "ok":
            return response
        target = response["value"]
        status = target.get("status", "")
        if status == "READY":
            return {"status": "ready", "target": target}
        if status in {"FAILED", "SYNCHRONIZE_UNSUCCESSFUL", "UPDATE_UNSUCCESSFUL"}:
            return {"status": "failed", "target": target}
        await asyncio.sleep(5)
    return {"status": "blocked", "reason": "Gateway target did not become READY in 10 minutes"}


async def _wait_registry(registry_id):
    for _ in range(120):
        response = await _read(
            "agent-registry-control",
            "GetRegistry",
            {"registryId": registry_id},
        )
        if response["status"] != "ok":
            return response
        registry = response["value"]
        status = registry.get("status", "")
        if status == "READY":
            return {"status": "ready", "registry": registry}
        if status in {"CREATE_FAILED", "UPDATE_FAILED"}:
            return {"status": "failed", "registry": registry}
        await asyncio.sleep(2)
    return {"status": "blocked", "reason": "Registry did not become READY in 4 minutes"}


async def _wait_registry_record(registry_id, record_id):
    for _ in range(120):
        response = await _read(
            "agent-registry-control",
            "GetRegistryRecord",
            {"registryId": registry_id, "recordId": record_id},
        )
        if response["status"] != "ok":
            return response
        record = response["value"]
        status = record.get("status", "")
        if status == "DRAFT":
            return {"status": "ready", "record": record}
        if status in {"CREATE_FAILED", "UPDATE_FAILED"}:
            return {"status": "failed", "record": record}
        await asyncio.sleep(2)
    return {"status": "blocked", "reason": "Registry record did not become DRAFT in 4 minutes"}


async def _wait_credential_provider(operation_name, name):
    # Keep one fixture deployment below the AWS MCP script timeout.  A
    # provider that is not READY after this bounded window is a real live
    # failure, not a reason to keep issuing unbounded reads.
    for _ in range(30):
        response = await _read(
            "bedrock-agentcore-control",
            operation_name,
            {"name": name},
        )
        if response["status"] != "ok":
            return response
        provider = response["value"]
        status = provider.get("status", "")
        if status == "READY":
            return {"status": "ready", "provider": provider}
        if status in {"CREATE_FAILED", "UPDATE_FAILED"}:
            return {"status": "failed", "provider": provider}
        await asyncio.sleep(2)
    return {"status": "blocked", "reason": "Credential provider did not become READY in 60 seconds"}


async def _wait_capacity_provider(capacity_provider_id):
    """Poll a run-owned capacity provider until it is usable or terminally failed."""
    for _ in range(180):
        response = await _read(
            "bedrock-agentcore-control",
            "GetCapacityProvider",
            {"capacityProviderId": capacity_provider_id},
        )
        if response["status"] != "ok":
            return response
        provider = response["value"]
        status = provider.get("status", "")
        if status == "READY":
            return {"status": "ready", "provider": provider}
        if status in {"CREATE_FAILED", "UPDATE_FAILED", "DELETE_FAILED"}:
            return {"status": "failed", "provider": provider}
        await asyncio.sleep(5)
    return {"status": "blocked", "reason": "capacity provider did not become READY in 15 minutes"}


async def _create_capacity_provider_fixture(fixture_outputs, wait=True):
    """Create the exact run-owned Instances capacity provider from the fixture network."""
    role_arn = fixture_outputs.get("CapacityOperatorRoleArn", "")
    profile_arn = fixture_outputs.get("CapacityInstanceProfileArn", "")
    subnet_id = fixture_outputs.get("SubnetId", "")
    security_group_id = fixture_outputs.get("SecurityGroupId", "")
    if not all((role_arn, profile_arn, subnet_id, security_group_id)):
        return {"status": "blocked", "reason": "capacity fixture outputs are incomplete"}
    name = f"tpipe_capacity_{RUN_ID}_retry2"
    try:
        response = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateCapacityProvider",
            region_name=REGION,
            params={
                "name": name,
                "description": f"Run-owned TPipe Instances smoke capacity provider {RUN_ID}",
                "clientToken": f"tpipe-capacity-{RUN_ID.replace('_', '-')}-retry2-create",
                "permissionsConfiguration": {
                    "capacityProviderOperatorRoleArn": role_arn,
                },
                "computeConfiguration": {
                    "ec2Configuration": {
                        "launchTemplateSource": {
                            "launchParameters": {
                                "operatingSystem": "LINUX_X86_64",
                                "instanceRequirements": {"allowedInstanceTypes": ["m5.large"]},
                                "instanceProfileArn": profile_arn,
                                "monitoring": "BASIC",
                            },
                        },
                        "vpcConfiguration": {
                            "subnets": [subnet_id],
                            "securityGroups": [security_group_id],
                        },
                    },
                },
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeAgentCoreLiveSmoke"},
            },
        )
        provider_id = response.get("capacityProviderId", "")
        if not provider_id:
            raise RuntimeError("CreateCapacityProvider returned no capacityProviderId")
        resource = {
            "type": "capacity-provider",
            "name": name,
            "id": provider_id,
            "arn": response.get("capacityProviderArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        if not wait:
            return {
                "status": "created",
                "created": [resource],
                "outputs": {
                    "CapacityProviderId": provider_id,
                    "CapacityProviderArn": response.get("capacityProviderArn", ""),
                },
            }
        waited = await _wait_capacity_provider(provider_id)
        if waited.get("status") != "ready":
            return {"status": "failed", "created": [resource], "capacity": waited}
        return {
            "status": "ready",
            "created": [resource],
            "outputs": {
                "CapacityProviderId": provider_id,
                "CapacityProviderArn": response.get("capacityProviderArn", ""),
            },
        }
    except Exception as error:
        return {"status": "failed", "error": _redact(error)}


async def _wait_cognito_domain(domain):
    for _ in range(120):
        response = await _read(
            "cognito-idp",
            "DescribeUserPoolDomain",
            {"Domain": domain},
        )
        if response["status"] != "ok":
            return response
        status = response["value"].get("DomainDescription", {}).get("Status", "")
        if status == "ACTIVE":
            return {"status": "ready"}
        if status in {"FAILED", "INACTIVE"}:
            return {"status": "failed", "domainStatus": status}
        await asyncio.sleep(5)
    return {"status": "blocked", "reason": "Cognito domain did not become ACTIVE in 10 minutes"}


async def _create_registry_fixture():
    """Create the exact Registry and valid MCP record owned by this run."""
    try:
        registry = await call_boto3(
            service_name="agent-registry-control",
            operation_name="CreateRegistry",
            region_name=REGION,
            params={
                "name": _registry_name(),
                "description": f"Run-owned TPipe AgentCore Registry {RUN_ID}",
                "clientToken": f"tpipe-registry-{RUN_ID.replace('_', '-')}",
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        registry = registry or {}
        registry_id = registry.get("registryId", "")
        registry_arn = registry.get("registryArn", "")
        if not registry_id:
            # The AWS MCP bridge may acknowledge a mutation without returning
            # its body. Resolve the exact just-created name before proceeding;
            # never invent an identifier.
            for _ in range(30):
                listed = await _read("agent-registry-control", "ListRegistries")
                if listed["status"] != "ok":
                    return {"status": "failed", "error": listed.get("error", "registry inventory failed")}
                match = next(
                    (item for item in listed["value"].get("registries", [])
                     if item.get("name") == _registry_name()),
                    None,
                )
                if match:
                    registry_id = match.get("registryId", "")
                    registry_arn = match.get("registryArn", "")
                    break
                await asyncio.sleep(2)
        if not registry_id:
            raise RuntimeError("created Registry could not be resolved by its exact name")
        registry_resource = {
            "type": "registry",
            "name": _registry_name(),
            "id": registry_id,
            "arn": registry_arn,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        registry_ready = await _wait_registry(registry_id)
        if registry_ready.get("status") != "ready":
            return {"status": "failed", "created": [registry_resource], "registry": registry_ready}

        server_data = json.dumps({
            "name": "tpipe/smoke-server",
            "title": "TPipe AgentCore live smoke MCP server",
            "description": f"{RUN_ID} run-owned MCP descriptor",
            "version": "1.0.0",
        }, separators=(",", ":"))
        record = await call_boto3(
            service_name="agent-registry-control",
            operation_name="CreateRegistryRecord",
            region_name=REGION,
            params={
                "registryId": registry_id,
                "clientToken": f"tpipe-registry-record-{RUN_ID.replace('_', '-')}",
                "name": _registry_record_name(),
                "displayName": "TPipe live smoke MCP record",
                "description": f"Run-owned TPipe AgentCore MCP record {RUN_ID}",
                "recordType": "MCP",
                "recordVersion": "1.0",
                "descriptors": {
                    "mcpServer": {
                        "data": server_data,
                        "dataSchemaVersion": "2025-12-11",
                    },
                },
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        record = record or {}
        record_arn = record.get("recordArn", "")
        record_id = record_arn.rsplit("/", 1)[-1] if record_arn else ""
        if not record_id:
            for _ in range(30):
                listed = await _read(
                    "agent-registry-control",
                    "ListRegistryRecords",
                    {"registryId": registry_id},
                )
                if listed["status"] != "ok":
                    return {
                        "status": "failed",
                        "created": [registry_resource],
                        "error": listed.get("error", "registry-record inventory failed"),
                    }
                match = next(
                    (item for item in listed["value"].get("registryRecords", [])
                     if item.get("name") == _registry_record_name()),
                    None,
                )
                if match:
                    record_arn = match.get("recordArn", "")
                    record_id = match.get("recordId", "") or record_arn.rsplit("/", 1)[-1]
                    break
                await asyncio.sleep(2)
        if not record_id:
            raise RuntimeError("created Registry record could not be resolved by its exact name")
        # Record the child immediately, before any eventual-consistency polling.
        record_resource = {
            "type": "registry-record",
            "name": _registry_record_name(),
            "id": record_id,
            "parentId": registry_id,
            "arn": record_arn,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        record_ready = await _wait_registry_record(registry_id, record_id)
        if record_ready.get("status") != "ready":
            return {"status": "failed", "created": [registry_resource, record_resource], "record": record_ready}
        return {
            "status": "ready",
            "created": [registry_resource, record_resource],
            "outputs": {"RegistryId": registry_id, "RegistryRecordId": record_id},
        }
    except Exception as error:
        return {"status": "failed", "error": _redact(error)}


async def _create_credential_provider_fixtures(outputs):
    """Create owned Cognito OAuth endpoints and external-secret providers."""
    oauth_secret = outputs.get("OAuthSecretArn", "")
    consent_oauth_secret = outputs.get("ConsentOAuthSecretArn", "")
    api_key_secret = outputs.get("ApiKeySecretArn", "")
    mcp_endpoint = outputs.get("McpEndpoint", "").rstrip("/")
    if not oauth_secret or not consent_oauth_secret or not api_key_secret:
        return {"status": "blocked", "reason": "fixture outputs lack secret ARNs"}
    oauth_name = f"tpipe-oauth-{RUN_ID.replace('_', '-')}"
    api_key_name = f"tpipe-api-key-{RUN_ID.replace('_', '-')}"
    created = []
    try:
        pool = await call_boto3(
            service_name="cognito-idp",
            operation_name="CreateUserPool",
            region_name=REGION,
            params={
                "PoolName": _oauth_pool_name(),
                "Policies": {"PasswordPolicy": {"MinimumLength": 12}},
                "AutoVerifiedAttributes": [],
                "UsernameAttributes": ["email"],
            },
        )
        pool_data = pool["UserPool"]
        pool_id = pool_data["Id"]
        await call_boto3(
            service_name="cognito-idp",
            operation_name="TagResource",
            region_name=REGION,
            params={
                "ResourceArn": pool_data["Arn"],
                "Tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        created.append({
            "type": "cognito-user-pool",
            "name": _oauth_pool_name(),
            "id": pool_id,
            "arn": pool_data.get("Arn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })

        resource_server = await call_boto3(
            service_name="cognito-idp",
            operation_name="CreateResourceServer",
            region_name=REGION,
            params={
                "UserPoolId": pool_id,
                "Identifier": "tpipe-smoke",
                "Name": "TPipe Smoke Resource Server",
                "Scopes": [{"ScopeName": "smoke", "ScopeDescription": "Run-owned smoke scope"}],
            },
        )
        created.append({
            "type": "cognito-resource-server",
            "name": "tpipe-smoke",
            "id": "tpipe-smoke",
            "parentId": pool_id,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })

        await call_boto3(
            service_name="cognito-idp",
            operation_name="CreateUserPoolDomain",
            region_name=REGION,
            params={"UserPoolId": pool_id, "Domain": _oauth_domain_name()},
        )
        created.append({
            "type": "cognito-user-pool-domain",
            "name": _oauth_domain_name(),
            "id": _oauth_domain_name(),
            "parentId": pool_id,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })
        domain_ready = await _wait_cognito_domain(_oauth_domain_name())
        if domain_ready.get("status") != "ready":
            return {"status": "failed", "created": created, "domain": domain_ready}

        m2m_client = await call_boto3(
            service_name="cognito-idp",
            operation_name="CreateUserPoolClient",
            region_name=REGION,
            params={
                "UserPoolId": pool_id,
                "ClientName": _oauth_m2m_client_name(),
                "GenerateSecret": True,
                "AllowedOAuthFlowsUserPoolClient": True,
                "AllowedOAuthFlows": ["client_credentials"],
                "AllowedOAuthScopes": ["tpipe-smoke/smoke"],
                "SupportedIdentityProviders": ["COGNITO"],
            },
        )
        m2m_data = m2m_client["UserPoolClient"]
        m2m_id = m2m_data["ClientId"]
        created.append({
            "type": "cognito-user-pool-client",
            "name": _oauth_m2m_client_name(),
            "id": m2m_id,
            "parentId": pool_id,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })

        auth_client = await call_boto3(
            service_name="cognito-idp",
            operation_name="CreateUserPoolClient",
            region_name=REGION,
            params={
                "UserPoolId": pool_id,
                "ClientName": _oauth_auth_client_name(),
                "GenerateSecret": True,
                "AllowedOAuthFlowsUserPoolClient": True,
                "AllowedOAuthFlows": ["code"],
                "AllowedOAuthScopes": ["openid", "email"],
                "CallbackURLs": ["https://example.invalid/callback"],
                "LogoutURLs": ["https://example.invalid/logout"],
                "SupportedIdentityProviders": ["COGNITO"],
            },
        )
        created.append({
            "type": "cognito-user-pool-client",
            "name": _oauth_auth_client_name(),
            "id": auth_client["UserPoolClient"]["ClientId"],
            "parentId": pool_id,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })

        auth_client_data = auth_client["UserPoolClient"]

        # Keep the M2M and authorization-code client credentials in separate,
        # run-owned Secrets Manager secrets. Values never enter the manifest or
        # controller result.
        await call_boto3(
            service_name="secretsmanager",
            operation_name="PutSecretValue",
            region_name=REGION,
            params={
                "SecretId": oauth_secret,
                "SecretString": json.dumps({
                    "client_id": m2m_data["ClientId"],
                    "client_secret": m2m_data["ClientSecret"],
                }),
            },
        )
        await call_boto3(
            service_name="secretsmanager",
            operation_name="PutSecretValue",
            region_name=REGION,
            params={
                "SecretId": consent_oauth_secret,
                "SecretString": json.dumps({
                    "client_id": auth_client_data["ClientId"],
                    "client_secret": auth_client_data["ClientSecret"],
                }),
            },
        )

        issuer = f"https://cognito-idp.{REGION}.amazonaws.com/{pool_id}"
        authorization_endpoint = f"https://{_oauth_domain_name()}.auth.{REGION}.amazoncognito.com/oauth2/authorize"
        token_endpoint = f"https://{_oauth_domain_name()}.auth.{REGION}.amazoncognito.com/oauth2/token"
        oauth = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateOauth2CredentialProvider",
            region_name=REGION,
            params={
                "name": oauth_name,
                "credentialProviderVendor": "CustomOauth2",
                "oauth2ProviderConfigInput": {
                    "customOauth2ProviderConfig": {
                        "oauthDiscovery": {
                            "authorizationServerMetadata": {
                                "issuer": issuer,
                                "authorizationEndpoint": authorization_endpoint,
                                "tokenEndpoint": token_endpoint,
                                "responseTypes": ["code"],
                            },
                        },
                        "clientId": m2m_data["ClientId"],
                        "clientSecretSource": "EXTERNAL",
                        "clientSecretConfig": {"secretId": oauth_secret, "jsonKey": "client_secret"},
                        "clientAuthenticationMethod": "CLIENT_SECRET_BASIC",
                    },
                },
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        created.append({
            "type": "oauth2-credential-provider",
            "name": oauth_name,
            "id": oauth_name,
            "arn": oauth.get("credentialProviderArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })
        oauth_ready = await _wait_credential_provider("GetOauth2CredentialProvider", oauth_name)
        if oauth_ready.get("status") != "ready":
            return {"status": "failed", "created": created, "oauth": oauth_ready}

        # A second provider uses the run-owned deterministic endpoint for the
        # interactive authorization-code path.  The secret value remains in
        # Secrets Manager and is never returned by this controller.
        consent_oauth_name = _oauth_auth_provider_name()
        consent_oauth = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateOauth2CredentialProvider",
            region_name=REGION,
            params={
                "name": consent_oauth_name,
                "credentialProviderVendor": "CognitoOauth2",
                "oauth2ProviderConfigInput": {
                    "includedOauth2ProviderConfig": {
                        "clientId": auth_client_data["ClientId"],
                        "clientSecret": auth_client_data["ClientSecret"],
                        "authorizationEndpoint": authorization_endpoint,
                        "tokenEndpoint": token_endpoint,
                        "issuer": issuer,
                    },
                },
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        created.append({
            "type": "oauth2-credential-provider",
            "name": consent_oauth_name,
            "id": consent_oauth_name,
            "arn": consent_oauth.get("credentialProviderArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })

        consent_callback_url = consent_oauth.get("callbackUrl", "")
        if not consent_callback_url:
            raise RuntimeError("authorization-code provider did not return its Cognito callback URL")
        await call_boto3(
            service_name="cognito-idp",
            operation_name="UpdateUserPoolClient",
            region_name=REGION,
            params={
                "UserPoolId": pool_id,
                "ClientId": auth_client_data["ClientId"],
                "AllowedOAuthFlowsUserPoolClient": True,
                "AllowedOAuthFlows": ["code"],
                "AllowedOAuthScopes": ["openid", "email"],
                "CallbackURLs": [consent_callback_url],
                "LogoutURLs": ["https://example.invalid/logout"],
                "SupportedIdentityProviders": ["COGNITO"],
            },
        )
        consent_oauth_ready = await _wait_credential_provider(
            "GetOauth2CredentialProvider",
            consent_oauth_name,
        )
        if consent_oauth_ready.get("status") != "ready":
            return {"status": "failed", "created": created, "consentOauth": consent_oauth_ready}

        api_key = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateApiKeyCredentialProvider",
            region_name=REGION,
            params={
                "name": api_key_name,
                "apiKeySecretSource": "EXTERNAL",
                "apiKeySecretConfig": {"secretId": api_key_secret, "jsonKey": "api_key"},
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        created.append({
            "type": "api-key-credential-provider",
            "name": api_key_name,
            "id": api_key_name,
            "arn": api_key.get("credentialProviderArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })
        api_key_ready = await _wait_credential_provider("GetApiKeyCredentialProvider", api_key_name)
        if api_key_ready.get("status") != "ready":
            return {"status": "failed", "created": created, "apiKey": api_key_ready}
        return {
            "status": "ready",
            "created": created,
            "outputs": {
                "Oauth2ProviderName": oauth_name,
                "ConsentOauth2ProviderName": consent_oauth_name,
                "ConsentOauth2ProviderArn": consent_oauth.get("credentialProviderArn", ""),
                "ApiKeyProviderName": api_key_name,
                "Oauth2WorkloadName": _workload_name(),
                "Oauth2AuthorizationEndpoint": authorization_endpoint,
                "Oauth2TokenEndpoint": token_endpoint,
            },
        }
    except Exception as error:
        return {"status": "failed", "created": created, "error": _redact(error)}


async def _create_gateway_fixture(outputs):
    """Create the exact Gateway and Lambda target owned by this run."""
    function_arn = outputs.get("McpFunctionArn", "")
    role_arn = outputs.get("GatewayServiceRoleArn", "")
    if not function_arn or not role_arn:
        return {"status": "blocked", "reason": "fixture stack did not return Gateway role and Lambda ARN"}
    gateway_name = f"tpipe-gateway-{RUN_ID.replace('_', '-')}"
    target_name = f"tpipe-target-{RUN_ID.replace('_', '-')}"
    try:
        gateway = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateGateway",
            region_name=REGION,
            params={
                "name": gateway_name,
                "roleArn": role_arn,
                "protocolType": "MCP",
                "authorizerType": "NONE",
                "description": f"Run-owned TPipe smoke Gateway {RUN_ID}",
                "clientToken": f"tpipe-gateway-{RUN_ID.replace('_', '-')}",
            },
        )
        gateway = gateway or {}
        gateway_id = gateway.get("gatewayId", "")
        gateway_arn = gateway.get("gatewayArn", "")
        gateway_url = gateway.get("gatewayUrl", "")
        if not gateway_id:
            for _ in range(30):
                listed = await _read("bedrock-agentcore-control", "ListGateways")
                if listed["status"] != "ok":
                    return {"status": "failed", "error": listed.get("error", "Gateway inventory failed")}
                match = next(
                    (item for item in listed["value"].get("items", [])
                     if item.get("name") == gateway_name),
                    None,
                )
                if match:
                    gateway_id = match.get("gatewayId", "")
                    gateway_arn = match.get("gatewayArn", "")
                    gateway_url = match.get("gatewayUrl", "")
                    break
                await asyncio.sleep(2)
        if not gateway_id:
            raise RuntimeError("created Gateway could not be resolved by its exact name")
        gateway_resource = {
            "type": "gateway",
            "name": gateway_name,
            "id": gateway_id,
            "arn": gateway_arn,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        gateway_ready = await _wait_gateway(gateway_id)
        if gateway_ready.get("status") != "ready":
            return {"status": "failed", "created": [gateway_resource], "gateway": gateway_ready}
        target = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateGatewayTarget",
            region_name=REGION,
            params={
                "gatewayIdentifier": gateway_id,
                "name": target_name,
                "description": f"Run-owned deterministic MCP target {RUN_ID}",
                "targetConfiguration": {
                    "mcp": {
                        "mcpServer": {
                            "endpoint": fixture_outputs["McpEndpoint"],
                            "listingMode": "DEFAULT",
                        },
                    },
                },
                "credentialProviderConfigurations": [
                    {
                        "credentialProviderType": "GATEWAY_IAM_ROLE",
                        "credentialProvider": {
                            "iamCredentialProvider": {
                                "service": "lambda",
                                "region": REGION,
                            },
                        },
                    },
                ],
                "clientToken": f"tpipe-target-{RUN_ID.replace('_', '-')}",
            },
        )
        target = target or {}
        target_id = target.get("targetId", "")
        target_arn = target.get("targetArn", "")
        if not target_id:
            for _ in range(30):
                listed = await _read(
                    "bedrock-agentcore-control",
                    "ListGatewayTargets",
                    {"gatewayIdentifier": gateway_id},
                )
                if listed["status"] != "ok":
                    return {"status": "failed", "created": [gateway_resource], "error": listed.get("error", "Gateway target inventory failed")}
                candidates = [
                    item for collection in listed["value"].values() if isinstance(collection, list)
                    for item in collection if isinstance(item, dict)
                ]
                match = next((item for item in candidates if item.get("name") == target_name), None)
                if match:
                    target_id = match.get("targetId", "")
                    target_arn = match.get("targetArn", "")
                    break
                await asyncio.sleep(2)
        if not target_id:
            raise RuntimeError("created Gateway target could not be resolved by its exact name")
        target_resource = {
            "type": "gateway-target",
            "name": target_name,
            "id": target_id,
            "parentId": gateway_id,
            "arn": target_arn,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        target_ready = await _wait_gateway_target(gateway_id, target_id)
        if target_ready.get("status") != "ready":
            return {"status": "failed", "created": [gateway_resource, target_resource], "target": target_ready}
        return {
            "status": "ready",
            "created": [gateway_resource, target_resource],
            "outputs": {
                "GatewayId": gateway_id,
                "GatewayArn": gateway_arn,
                "GatewayUrl": gateway_url,
                "TargetName": target_name,
                "TargetId": target_id,
            },
        }
    except Exception as error:
        return {"status": "failed", "error": _redact(error)}


async def _create_identity_fixture(fixture_outputs, credential_outputs, gateway_outputs):
    """Create the workload identity; consent portals use the CLI fallback."""
    workload_name = _workload_name()
    return_url = "http://127.0.0.1:43127/callback"
    created = []
    try:
        workload = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreateWorkloadIdentity",
            region_name=REGION,
            params={
                "name": workload_name,
                "allowedResourceOauth2ReturnUrls": [return_url],
                "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
            },
        )
        created.append({
            "type": "workload-identity",
            "name": workload_name,
            "id": workload_name,
            "arn": workload.get("workloadIdentityArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })

        return {
            "status": "ready",
            "created": created,
            "outputs": {
                "WorkloadName": workload_name,
                "ConsentReturnUrl": return_url,
                "ConsentProviderArn": credential_outputs["ConsentOauth2ProviderArn"],
                "ConsentGatewayId": gateway_outputs["GatewayId"],
            },
        }
    except Exception as error:
        return {"status": "failed", "created": created, "error": _redact(error)}


async def _create_temporal_policy_fixture(gateway_outputs):
    """Create a run-owned policy engine and a minimal Dogwood approval rule."""
    # AgentCore policy identifiers accept only the alphanumeric/underscore
    # form, unlike Gateway and CloudFormation names.
    engine_name = f"tpipe_policy_{RUN_ID}"
    policy_name = f"tpipe_temporal_{RUN_ID}"
    base_policy_name = f"tpipe_temporal_base_{RUN_ID}"
    target_name = gateway_outputs.get(
        "TargetName",
        f"tpipe-target-{RUN_ID.replace('_', '-')}",
    )
    gateway_arn = gateway_outputs.get("GatewayArn", "")
    created = []
    try:
        try:
            engine = await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="CreatePolicyEngine",
                region_name=REGION,
                params={
                    "name": engine_name,
                    "description": f"Run-owned TPipe temporal smoke engine {RUN_ID}",
                    "clientToken": f"tpipe-policy-engine-{RUN_ID.replace('_', '-')}",
                    "tags": {"TPipeSmokeRun": RUN_ID, "ManagedBy": "TPipeLiveSmoke"},
                },
            )
        except Exception:
            engine = {}
        engine = engine or {}
        engine_id = engine.get("policyEngineId", "")
        engine_arn = engine.get("policyEngineArn", "")
        if not engine_id:
            listed = await _read("bedrock-agentcore-control", "ListPolicyEngines")
            if listed["status"] == "ok":
                match = next(
                    (item for item in listed["value"].get("policyEngines", [])
                     if item.get("name") == engine_name),
                    None,
                )
                if match:
                    engine_id = match.get("policyEngineId", "")
                    engine_arn = match.get("policyEngineArn", "")
        if not engine_id:
            raise RuntimeError("created policy engine could not be resolved by its exact name")
        engine_resource = {
            "type": "policy-engine",
            "name": engine_name,
            "id": engine_id,
            "arn": engine_arn,
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        }
        created.append(engine_resource)
        for _ in range(120):
            status = await _read(
                "bedrock-agentcore-control",
                "GetPolicyEngine",
                {"policyEngineId": engine_id},
            )
            if status["status"] != "ok":
                return {"status": "failed", "created": created, "engine": status}
            value = status["value"]
            if value.get("status") in {"READY", "ACTIVE"}:
                break
            if value.get("status") in {"CREATE_FAILED", "UPDATE_FAILED"}:
                return {"status": "failed", "created": created, "engine": value}
            await asyncio.sleep(2)
        else:
            return {"status": "blocked", "created": created, "reason": "policy engine did not become READY"}

        echo_action = _policy_action_name(gateway_arn, target_name, "smoke_echo")
        approve_action = _policy_action_name(gateway_arn, target_name, "smoke_approve")
        forbidden_action = _policy_action_name(gateway_arn, target_name, "smoke_forbidden")
        base_statement = (
            "permit (principal, action in ["
            f'AgentCore::Action::"{echo_action}", '
            f'AgentCore::Action::"{approve_action}"], '
            f'resource == AgentCore::Gateway::"{gateway_arn}");'
        )
        base_policy = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreatePolicy",
            region_name=REGION,
            params={
                "policyEngineId": engine_id,
                "name": base_policy_name,
                "description": f"Run-owned unconditional temporal smoke tool permissions {RUN_ID}",
                "definition": {"policy": {"statement": base_statement}},
                "enforcementMode": "ACTIVE",
                "validationMode": "IGNORE_ALL_FINDINGS",
                "clientToken": f"tpipe-temporal-base-{RUN_ID.replace('_', '-')}",
            },
        )
        base_policy = base_policy or {}
        base_policy_id = base_policy.get("policyId", "")
        if not base_policy_id:
            raise RuntimeError("CreatePolicy returned no base policyId")
        created.append({
            "type": "policy",
            "name": base_policy_name,
            "id": base_policy_id,
            "parentId": engine_id,
            "arn": base_policy.get("policyArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })
        statement = (
            "permit (principal, "
            f'action == AgentCore::Action::"{forbidden_action}", '
            f'resource == AgentCore::Gateway::"{gateway_arn}") '
            "when temporal { "
            f'formerly within 1h AgentCore::Action::"{approve_action}"::response '
            "{ eventResource: resource } };"
        )
        policy = await call_boto3(
            service_name="bedrock-agentcore-control",
            operation_name="CreatePolicy",
            region_name=REGION,
            params={
                "policyEngineId": engine_id,
                "name": policy_name,
                "description": f"Run-owned temporal approval policy {RUN_ID}",
                "definition": {"policy": {"statement": statement}},
                "enforcementMode": "ACTIVE",
                "validationMode": "IGNORE_ALL_FINDINGS",
                "clientToken": f"tpipe-temporal-policy-{RUN_ID.replace('_', '-')}",
            },
        )
        policy = policy or {}
        policy_id = policy.get("policyId", "")
        if not policy_id:
            raise RuntimeError("CreatePolicy returned no policyId")
        created.append({
            "type": "policy",
            "name": policy_name,
            "id": policy_id,
            "parentId": engine_id,
            "arn": policy.get("policyArn", ""),
            "region": REGION,
            "runTag": RUN_ID,
            "createdAt": "controller-create-response",
        })
        return {
            "status": "ready",
            "created": created,
            "outputs": {
                "PolicyEngineId": engine_id,
                "PolicyId": policy_id,
                "BasePolicyId": base_policy_id,
            },
        }
    except Exception as error:
        return {"status": "failed", "created": created, "error": _redact(error)}


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
    fixture = await _create_fixture_stack()
    if fixture is not None:
        if fixture["status"] != "created":
            return {"status": "failed", "created": created, "error": fixture.get("error", "fixture create failed")}
        created.append(fixture["resource"])
        waited = await _wait_stack(fixture["resource"]["stackId"])
        if waited.get("status") != "ready":
            return {"status": "failed", "created": created, "stack": waited}
        outputs["FIXTURES"] = waited.get("outputs", {})
        providers = await _create_credential_provider_fixtures(outputs["FIXTURES"])
        if providers["status"] != "ready":
            return {"status": "failed", "created": created + providers.get("created", []), "providers": providers}
        created.extend(providers["created"])
        outputs["CREDENTIALS"] = providers["outputs"]
        registry = await _create_registry_fixture()
        if registry["status"] != "ready":
            return {"status": "failed", "created": created + registry.get("created", []), "registry": registry}
        created.extend(registry["created"])
        outputs["REGISTRY"] = registry["outputs"]
        gateway = await _create_gateway_fixture(outputs["FIXTURES"])
        if gateway["status"] != "ready":
            return {"status": "failed", "created": created + gateway.get("created", []), "gateway": gateway}
        created.extend(gateway["created"])
        outputs["GATEWAY"] = gateway["outputs"]
        policy = await _create_temporal_policy_fixture(outputs["GATEWAY"])
        if policy["status"] != "ready":
            return {"status": "failed", "created": created + policy.get("created", []), "policy": policy}
        created.extend(policy["created"])
        outputs["POLICY"] = policy["outputs"]
        identity = await _create_identity_fixture(
            outputs["FIXTURES"],
            outputs["CREDENTIALS"],
            outputs["GATEWAY"],
        )
        if identity["status"] != "ready":
            return {"status": "failed", "created": created + identity.get("created", []), "identity": identity}
        created.extend(identity["created"])
        outputs["IDENTITY"] = identity["outputs"]
        if EVALUATION_TEMPLATE_BODY:
            evaluation = await _create_named_template_stack(
                _evaluation_stack_name(),
                EVALUATION_TEMPLATE_BODY,
                {
                    "DatasetName": f"tpipe_dataset_{RUN_ID}",
                    "BundleName": f"tpipe_bundle_{RUN_ID}",
                    "EvaluatorName": f"tpipe_evaluator_{RUN_ID}",
                    "EvaluatorLambdaArn": outputs["FIXTURES"]["EvaluationLambdaArn"],
                    "EvaluationRoleArn": outputs["FIXTURES"]["EvaluationExecutionRoleArn"],
                    "EvaluationLogGroupName": outputs["FIXTURES"]["LogGroupName"],
                    "EvaluationServiceName": "bedrock-agentcore",
                    "EnableEvaluationResources": "true",
                },
            )
            if evaluation["status"] != "created":
                return {"status": "failed", "created": created, "evaluation": evaluation}
            created.append(evaluation["resource"])
            waited = await _wait_stack(evaluation["resource"]["stackId"])
            if waited.get("status") != "ready":
                return {"status": "failed", "created": created, "evaluation": waited}
            outputs["EVALUATION"] = waited.get("outputs", {})
        if CAPACITY_TEMPLATE_BODY:
            capacity = await _create_capacity_provider_fixture(outputs["FIXTURES"])
            if capacity["status"] != "ready":
                return {"status": "failed", "created": created + capacity.get("created", []), "capacity": capacity}
            created.extend(capacity["created"])
            outputs["CAPACITY"] = capacity.get("outputs", {})
    for protocol, image_uri in IMAGES.items():
        result = await _create_stack(protocol, image_uri)
        if result["status"] != "created":
            return {"status": "failed", "created": created, "error": result.get("error", "create failed")}
        created.append(result["resource"])
        waited = await _wait_stack(result["resource"]["stackId"])
        if waited.get("status") != "ready":
            return {"status": "failed", "created": created, "stack": waited}
        outputs[protocol] = waited.get("outputs", {})
    if CAPACITY_TEMPLATE_BODY and outputs.get("CAPACITY", {}).get("CapacityProviderArn"):
        result = await _create_stack(
            "INSTANCES",
            IMAGES["HTTP"],
            extra_parameters={
                "NetworkMode": "VPC",
                "CapacityProviderArn": outputs["CAPACITY"]["CapacityProviderArn"],
            },
            runtime_protocol="HTTP",
        )
        if result["status"] != "created":
            return {"status": "failed", "created": created, "error": result.get("error", "Instances create failed")}
        created.append(result["resource"])
        waited = await _wait_stack(result["resource"]["stackId"])
        if waited.get("status") != "ready":
            return {"status": "failed", "created": created, "stack": waited}
        outputs["INSTANCES"] = waited.get("outputs", {})
    return {"status": "ready", "created": created, "outputs": outputs}


async def _deploy_foundation():
    """Create only the fixture stack so later MCP calls can resume safely."""
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    if not FIXTURE_TEMPLATE_BODY:
        return {"status": "blocked", "reason": "fixture template body is required"}
    preflight = await _preflight()
    if preflight.get("status") != "ready":
        return {"status": "blocked", "preflight": preflight}
    fixture = await _create_fixture_stack()
    if fixture is None:
        return {"status": "blocked", "reason": "fixture template body is required"}
    if fixture.get("status") != "created":
        return {"status": "failed", "error": fixture.get("error", "fixture create failed")}
    return {"status": "created", "created": [fixture["resource"]]}


async def _deploy_control_plane():
    """Resume from the exact fixture stack and create AgentCore control-plane fixtures."""
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    fixture = await _existing_stack_outputs(_fixture_stack_name())
    if fixture.get("status") != "ready":
        return fixture
    fixture_outputs = fixture["outputs"]
    created = [{
        "type": "cloudformation-stack",
        "name": _fixture_stack_name(),
        "stackId": fixture["stackId"],
        "region": REGION,
        "runTag": RUN_ID,
        "createdAt": "staged-deployment-existing-manifest",
    }]
    providers = await _create_credential_provider_fixtures(fixture_outputs)
    if providers.get("status") != "ready":
        return {"status": "failed", "created": created + providers.get("created", []), "providers": providers}
    created.extend(providers["created"])
    registry = await _create_registry_fixture()
    if registry.get("status") != "ready":
        return {"status": "failed", "created": created + registry.get("created", []), "registry": registry}
    created.extend(registry["created"])
    gateway = await _create_gateway_fixture(fixture_outputs)
    if gateway.get("status") != "ready":
        return {"status": "failed", "created": created + gateway.get("created", []), "gateway": gateway}
    created.extend(gateway["created"])
    policy = await _create_temporal_policy_fixture(gateway["outputs"])
    if policy.get("status") != "ready":
        return {"status": "failed", "created": created + policy.get("created", []), "policy": policy}
    created.extend(policy["created"])
    identity = await _create_identity_fixture(fixture_outputs, providers["outputs"], gateway["outputs"])
    if identity.get("status") != "ready":
        return {"status": "failed", "created": created + identity.get("created", []), "identity": identity}
    created.extend(identity["created"])
    return {
        "status": "ready",
        "created": created,
        "outputs": {
            "FIXTURES": fixture_outputs,
            "CREDENTIALS": providers["outputs"],
            "REGISTRY": registry["outputs"],
            "GATEWAY": gateway["outputs"],
            "POLICY": policy["outputs"],
            "IDENTITY": identity["outputs"],
        },
    }


async def _deploy_registry_stage():
    """Create and return only the exact Registry manifest."""
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    return await _create_registry_fixture()


async def _deploy_gateway_stage():
    """Create and return only the exact Gateway and synchronized target manifest."""
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    fixture = await _existing_stack_outputs(_fixture_stack_name())
    if fixture.get("status") != "ready":
        return fixture
    return await _create_gateway_fixture(fixture["outputs"])


async def _deploy_policy_stage():
    """Create the temporal policy from explicit Gateway identifiers."""
    if not APPLY or not GATEWAY_ID or not GATEWAY_ARN:
        return {"status": "blocked", "reason": "Gateway identifiers and APPLY are required"}
    return await _create_temporal_policy_fixture({
        "GatewayId": GATEWAY_ID,
        "GatewayArn": GATEWAY_ARN,
        "GatewayUrl": GATEWAY_URL,
    })


async def _deploy_identity_stage():
    """Create the workload identity from explicit provider and Gateway identifiers."""
    if not APPLY or not GATEWAY_ID or not CONSENT_PROVIDER_ARN:
        return {"status": "blocked", "reason": "Gateway ID, consent provider ARN, and APPLY are required"}
    return await _create_identity_fixture(
        {},
        {"ConsentOauth2ProviderArn": CONSENT_PROVIDER_ARN},
        {"GatewayId": GATEWAY_ID},
    )


async def _deploy_evaluation_stage():
    """Create and wait for the isolated evaluation resource stack."""
    if not APPLY or not EVALUATION_TEMPLATE_BODY:
        return {"status": "blocked", "reason": "evaluation template body and APPLY are required"}
    fixture = await _existing_stack_outputs(_fixture_stack_name())
    if fixture.get("status") != "ready":
        return fixture
    result = await _create_named_template_stack(
        _evaluation_stack_name(),
        EVALUATION_TEMPLATE_BODY,
        {
            "DatasetName": f"tpipe_dataset_{RUN_ID}",
            "BundleName": f"tpipe_bundle_{RUN_ID}",
            "EvaluatorName": f"tpipe_evaluator_{RUN_ID}",
            "EvaluatorLambdaArn": fixture["outputs"].get("EvaluationLambdaArn", ""),
            "EvaluationRoleArn": fixture["outputs"].get("EvaluationExecutionRoleArn", ""),
            "EvaluationLogGroupName": fixture["outputs"].get("LogGroupName", ""),
            "EvaluationServiceName": "bedrock-agentcore",
            "EnableEvaluationResources": "true",
        },
    )
    if result.get("status") != "created":
        return result
    waited = await _wait_stack(result["resource"]["stackId"])
    return {
        "status": "ready" if waited.get("status") == "ready" else waited.get("status", "failed"),
        "created": [result["resource"]],
        "outputs": waited.get("outputs", {}),
        "stack": waited,
    }


async def _create_evaluation_stage():
    """Create the evaluation stack and return immediately with its manifest."""
    if not APPLY or not EVALUATION_TEMPLATE_BODY:
        return {"status": "blocked", "reason": "evaluation template body and APPLY are required"}
    fixture = await _existing_stack_outputs(_fixture_stack_name())
    if fixture.get("status") != "ready":
        return fixture
    result = await _create_named_template_stack(
        _evaluation_stack_name(),
        EVALUATION_TEMPLATE_BODY,
        {
            "DatasetName": f"tpipe_dataset_{RUN_ID}",
            "BundleName": f"tpipe_bundle_{RUN_ID}",
            "EvaluatorName": f"tpipe_evaluator_{RUN_ID}",
            "EvaluatorLambdaArn": fixture["outputs"].get("EvaluationLambdaArn", ""),
            "EvaluationRoleArn": fixture["outputs"].get("EvaluationExecutionRoleArn", ""),
            "EvaluationLogGroupName": fixture["outputs"].get("LogGroupName", ""),
            "EvaluationServiceName": "bedrock-agentcore",
            "EnableEvaluationResources": "true",
        },
    )
    return {"status": result.get("status"), "created": [result["resource"]] if result.get("resource") else [], "error": result.get("error")}


async def _wait_evaluation_stage():
    """Wait for one exact evaluation stack in a separate MCP task."""
    if not APPLY or not EVALUATION_STACK_ID:
        return {"status": "blocked", "reason": "evaluation stack ID and APPLY are required"}
    return await _wait_stack(EVALUATION_STACK_ID)


async def _deploy_capacity_stage():
    """Create the capacity provider and return before the asynchronous build completes."""
    if not APPLY:
        return {"status": "preview_only", "reason": "APPLY is false"}
    fixture = await _existing_stack_outputs(_fixture_stack_name())
    if fixture.get("status") != "ready":
        return fixture
    return await _create_capacity_provider_fixture(fixture["outputs"], wait=False)


async def _wait_capacity_stage():
    """Poll one exact run-owned capacity provider in a separate MCP task."""
    if not APPLY or not CAPACITY_PROVIDER_ID:
        return {"status": "blocked", "reason": "capacity provider ID and APPLY are required"}
    return await _wait_capacity_provider(CAPACITY_PROVIDER_ID)


async def _deploy_runtime_stage():
    """Create one runtime stack at a time so MCP task limits cannot hide its manifest."""
    if not APPLY or not TEMPLATE_BODY:
        return {"status": "blocked", "reason": "runtime template body and APPLY are required"}
    instances = STAGE_PROTOCOL == "INSTANCES"
    image_uri = INSTANCES_IMAGE if instances else IMAGES.get(STAGE_PROTOCOL, "")
    if not STAGE_PROTOCOL or not image_uri:
        return {"status": "blocked", "reason": "STAGE_PROTOCOL must name one configured image"}
    result = await _create_stack(
        STAGE_PROTOCOL,
        image_uri,
        extra_parameters=(
            {
                "NetworkMode": "VPC",
                "CapacityProviderArn": CAPACITY_PROVIDER_ARN,
            }
            if instances else None
        ),
        runtime_protocol="HTTP" if instances else None,
    )
    if result.get("status") != "created":
        return result
    waited = await _wait_stack(result["resource"]["stackId"])
    return {
        "status": "ready" if waited.get("status") == "ready" else waited.get("status", "failed"),
        "created": [result["resource"]],
        "outputs": waited.get("outputs", {}),
        "stack": waited,
    }


async def _create_runtime_stage():
    """Create one runtime stack and return its exact manifest before waiting."""
    if not APPLY or not TEMPLATE_BODY:
        return {"status": "blocked", "reason": "runtime template body and APPLY are required"}
    instances = STAGE_PROTOCOL == "INSTANCES"
    image_uri = INSTANCES_IMAGE if instances else IMAGES.get(STAGE_PROTOCOL, "")
    if not STAGE_PROTOCOL or not image_uri:
        return {"status": "blocked", "reason": "STAGE_PROTOCOL and its image are required"}
    result = await _create_stack(
        STAGE_PROTOCOL,
        image_uri,
        extra_parameters=(
            {"NetworkMode": "VPC", "CapacityProviderArn": CAPACITY_PROVIDER_ARN}
            if instances else None
        ),
        runtime_protocol="HTTP" if instances else None,
    )
    return {"status": result.get("status"), "created": [result["resource"]] if result.get("resource") else [], "error": result.get("error")}


async def _wait_runtime_stage():
    """Wait for one exact runtime stack in a separate MCP task."""
    if not APPLY or not RUNTIME_STACK_ID:
        return {"status": "blocked", "reason": "runtime stack ID and APPLY are required"}
    return await _wait_stack(RUNTIME_STACK_ID)


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


async def _cost_reading():
    """Read account-level daily cost data without claiming run attribution."""
    today = datetime.date.today()
    start = (today - datetime.timedelta(days=2)).isoformat()
    end = (today + datetime.timedelta(days=1)).isoformat()
    result = await _read(
        "ce",
        "GetCostAndUsage",
        {
            "TimePeriod": {"Start": start, "End": end},
            "Granularity": "DAILY",
            "Metrics": ["UnblendedCost"],
            "GroupBy": [{"Type": "DIMENSION", "Key": "SERVICE"}],
        },
    )
    if result["status"] != "ok":
        return {
            "status": "blocked",
            "reason": "Cost Explorer reading was incomplete",
            "error": result.get("error", ""),
            "period": {"start": start, "end": end},
        }
    return {
        "status": "ready",
        "period": {"start": start, "end": end},
        "accountLevel": True,
        "billingLag": "Cost Explorer is account-level and may lag; no run attribution is claimed.",
        "resultsByTime": result["value"].get("ResultsByTime", []),
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
        elif resource_type in {"gateway", "consent-gateway"}:
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
        elif resource_type == "capacity-provider":
            await call_boto3(
                service_name="bedrock-agentcore-control",
                operation_name="DeleteCapacityProvider",
                region_name=REGION,
                params={"capacityProviderId": resource["id"]},
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
        elif resource_type == "cognito-user-pool-client":
            await call_boto3(
                service_name="cognito-idp",
                operation_name="DeleteUserPoolClient",
                region_name=REGION,
                params={"UserPoolId": resource["parentId"], "ClientId": resource["id"]},
            )
        elif resource_type == "cognito-user":
            await call_boto3(
                service_name="cognito-idp",
                operation_name="AdminDeleteUser",
                region_name=REGION,
                params={"UserPoolId": resource["parentId"], "Username": resource["name"]},
            )
        elif resource_type == "cognito-user-pool-domain":
            await call_boto3(
                service_name="cognito-idp",
                operation_name="DeleteUserPoolDomain",
                region_name=REGION,
                params={"UserPoolId": resource["parentId"], "Domain": resource["name"]},
            )
        elif resource_type == "cognito-resource-server":
            await call_boto3(
                service_name="cognito-idp",
                operation_name="DeleteResourceServer",
                region_name=REGION,
                params={"UserPoolId": resource["parentId"], "Identifier": resource["id"]},
            )
        elif resource_type == "cognito-user-pool":
            await call_boto3(
                service_name="cognito-idp",
                operation_name="DeleteUserPool",
                region_name=REGION,
                params={"UserPoolId": resource["id"]},
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
                params={"repositoryName": resource["name"], "force": True},
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
        elif resource_type == "lambda-function":
            await call_boto3(
                service_name="lambda",
                operation_name="DeleteFunction",
                region_name=REGION,
                params={"FunctionName": resource.get("arn") or resource["name"]},
            )
        elif resource_type == "lambda-url":
            await call_boto3(
                service_name="lambda",
                operation_name="DeleteFunctionUrlConfig",
                region_name=REGION,
                params={"FunctionName": resource.get("parentId") or resource["name"]},
            )
        elif resource_type == "secret":
            await call_boto3(
                service_name="secretsmanager",
                operation_name="DeleteSecret",
                region_name=REGION,
                params={"SecretId": resource.get("arn") or resource["name"], "ForceDeleteWithoutRecovery": True},
            )
        elif resource_type == "ec2-instance":
            await call_boto3(
                service_name="ec2",
                operation_name="TerminateInstances",
                region_name=REGION,
                params={"InstanceIds": [resource["id"]]},
            )
        elif resource_type == "security-group":
            await call_boto3(
                service_name="ec2",
                operation_name="DeleteSecurityGroup",
                region_name=REGION,
                params={"GroupId": resource["id"]},
            )
        elif resource_type == "subnet":
            await call_boto3(
                service_name="ec2",
                operation_name="DeleteSubnet",
                region_name=REGION,
                params={"SubnetId": resource["id"]},
            )
        elif resource_type == "route-table":
            await call_boto3(
                service_name="ec2",
                operation_name="DeleteRouteTable",
                region_name=REGION,
                params={"RouteTableId": resource["id"]},
            )
        elif resource_type == "internet-gateway":
            if resource.get("parentId"):
                await call_boto3(
                    service_name="ec2",
                    operation_name="DetachInternetGateway",
                    region_name=REGION,
                    params={"InternetGatewayId": resource["id"], "VpcId": resource["parentId"]},
                )
            await call_boto3(
                service_name="ec2",
                operation_name="DeleteInternetGateway",
                region_name=REGION,
                params={"InternetGatewayId": resource["id"]},
            )
        elif resource_type == "vpc":
            await call_boto3(
                service_name="ec2",
                operation_name="DeleteVpc",
                region_name=REGION,
                params={"VpcId": resource["id"]},
            )
        elif resource_type == "instance-profile":
            role_name = resource.get("parentId")
            if role_name:
                await call_boto3(
                    service_name="iam",
                    operation_name="RemoveRoleFromInstanceProfile",
                    region_name=REGION,
                    params={"InstanceProfileName": resource["name"], "RoleName": role_name},
                )
            await call_boto3(
                service_name="iam",
                operation_name="DeleteInstanceProfile",
                region_name=REGION,
                params={"InstanceProfileName": resource["name"]},
            )
        elif resource_type == "registry-record":
            await call_boto3(
                service_name="agent-registry-control",
                operation_name="DeleteRegistryRecord",
                region_name=REGION,
                params={"registryId": resource["parentId"], "recordId": resource["id"]},
            )
        elif resource_type == "registry":
            await call_boto3(
                service_name="agent-registry-control",
                operation_name="DeleteRegistry",
                region_name=REGION,
                params={"registryId": resource["id"]},
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
    for resource in sorted(MANIFEST, key=_cleanup_priority, reverse=True):
        result = await _delete_resource(resource)
        if result["status"] in ("blocked", "failed"):
            return {"status": result["status"], "deleted": deleted, "error": result.get("reason", result.get("error", ""))}
        if resource.get("type") == "cloudformation-stack":
            deletion = await _wait_stack_deleted(resource["stackId"])
            if deletion.get("status") != "deleted":
                return {"status": deletion.get("status", "blocked"), "deleted": deleted, "error": deletion.get("reason", "stack deletion incomplete")}
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


def _cleanup_priority(resource):
    """Return a stable dependency rank; children are always deleted first."""
    return {
        "lambda-url": 120,
        "ec2-instance": 115,
        "gateway-target": 110,
        "registry-record": 110,
        "runtime-endpoint": 105,
        "browser-custom": 100,
        "browser-profile": 100,
        "code-interpreter-custom": 100,
        "evaluator": 100,
        "online-evaluation-config": 100,
        "dataset": 100,
        "configuration-bundle": 100,
        "oauth2-credential-provider": 100,
        "api-key-credential-provider": 100,
        "cognito-user": 125,
        "cognito-user-pool-domain": 120,
        "cognito-user-pool-client": 115,
        "cognito-resource-server": 110,
        "secret": 95,
        "lambda-function": 95,
        "gateway": 90,
        "runtime": 90,
        "capacity-provider": 90,
        "memory": 90,
        "policy": 90,
        "workload-identity": 90,
        "registry": 90,
        "cognito-user-pool": 85,
        "security-group": 60,
        "subnet": 55,
        "route-table": 55,
        "internet-gateway": 50,
        "instance-profile": 45,
        "iam-role": 40,
        "log-group": 35,
        "vpc": 10,
        "ecr-image": 10,
        "ecr-repository": 5,
        "cloudformation-stack": 0,
    }.get(resource.get("type"), 50)


async def _post_cleanup_scan():
    """Read exact service inventories and return only live run-owned resources."""
    reads = [
        _read("cloudformation", "ListStacks"),
        _read("ecr", "DescribeRepositories"),
        _read("iam", "ListRoles"),
        _read("logs", "DescribeLogGroups", {"logGroupNamePrefix": "/aws/bedrock-agentcore/"}),
        *(_read("bedrock-agentcore-control", operation) for operation in AGENTCORE_INVENTORY),
        _read("agent-registry-control", "ListRegistries"),
        *(_read(service, operation, params) for service, operation, params in EXTERNAL_INVENTORY),
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
        elif operation == "ListSecrets":
            for secret in value.get("SecretList", []):
                identity = {
                    "name": secret.get("Name", ""),
                    "arn": secret.get("ARN", ""),
                }
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "secret", "name": identity["name"] or identity["arn"]})
        elif operation == "ListRegistries":
            for registry in value.get("registries", []):
                identity = {
                    "name": registry.get("name", ""),
                    "arn": registry.get("registryArn", ""),
                }
                registry_owned = _owns_manifest_resource(identity)
                if registry_owned:
                    remaining.append({"type": "registry", "name": identity["name"] or identity["arn"]})
                registry_id = registry.get("registryId")
                if registry_id:
                    records = await _read(
                        "agent-registry-control",
                        "ListRegistryRecords",
                        {"registryId": registry_id},
                    )
                    if records["status"] != "ok":
                        return {
                            "status": "blocked",
                            "reason": "registry-record inventory was incomplete",
                            "errors": [records],
                        }
                    for record in records["value"].get("registryRecords", []):
                        record_identity = {
                            "name": record.get("name", ""),
                            "arn": record.get("recordArn", ""),
                        }
                        if registry_owned or _owns_manifest_resource(record_identity):
                            remaining.append({"type": "registry-record", "name": record_identity["name"] or record_identity["arn"]})
        elif operation == "ListGateways":
            for gateway in value.get("gateways", []):
                gateway_id = gateway.get("gatewayId")
                if gateway_id:
                    targets = await _read(
                        "bedrock-agentcore-control",
                        "ListGatewayTargets",
                        {"gatewayIdentifier": gateway_id},
                    )
                    if targets["status"] != "ok":
                        return {
                            "status": "blocked",
                            "reason": "Gateway target inventory was incomplete",
                            "errors": [targets],
                        }
                    for target in targets["value"].get("targets", []):
                        identity = {
                            "name": target.get("name", "") or target.get("targetId", ""),
                            "arn": target.get("targetArn", ""),
                        }
                        if _owns_manifest_resource(identity):
                            remaining.append({"type": "gateway-target", "name": identity["name"] or identity["arn"]})
        elif operation == "ListConsentPortals":
            for portal in value.get("consentPortals", []):
                identity = {
                    "name": portal.get("name", ""),
                    "arn": portal.get("consentPortalArn", ""),
                }
                if _owns_manifest_resource(identity):
                    remaining.append({
                        "type": "consent-portal",
                        "name": identity["name"] or identity["arn"],
                    })
        elif operation == "ListFunctions":
            for function in value.get("Functions", []):
                identity = {"name": function.get("FunctionName", ""), "arn": function.get("FunctionArn", "")}
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "lambda-function", "name": identity["name"] or identity["arn"]})
        elif operation == "ListSecrets":
            for secret in value.get("SecretList", []):
                identity = {"name": secret.get("Name", ""), "arn": secret.get("ARN", "")}
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "secret", "name": identity["name"] or identity["arn"]})
        elif operation == "ListUserPools":
            for pool in value.get("UserPools", []):
                identity = {
                    "name": pool.get("Name", ""),
                    "arn": pool.get("LambdaConfig", {}).get("UserPoolArn", ""),
                }
                if not _owns_manifest_resource(identity):
                    continue
                remaining.append({"type": "cognito-user-pool", "name": identity["name"]})
                pool_id = pool.get("Id")
                if not pool_id:
                    continue
                clients = await _read(
                    "cognito-idp",
                    "ListUserPoolClients",
                    {"UserPoolId": pool_id},
                )
                if clients["status"] != "ok":
                    return {
                        "status": "blocked",
                        "reason": "Cognito client inventory was incomplete",
                        "errors": [clients],
                    }
                for client in clients["value"].get("UserPoolClients", []):
                    if _owns_manifest_resource({"name": client.get("ClientName", "")}):
                        remaining.append({
                            "type": "cognito-user-pool-client",
                            "name": client.get("ClientName", ""),
                        })
                resource_servers = await _read(
                    "cognito-idp",
                    "ListResourceServers",
                    {"UserPoolId": pool_id, "MaxResults": 50},
                )
                if resource_servers["status"] != "ok":
                    return {
                        "status": "blocked",
                        "reason": "Cognito resource-server inventory was incomplete",
                        "errors": [resource_servers],
                    }
                for server in resource_servers["value"].get("ResourceServers", []):
                    if _owns_manifest_resource({"name": server.get("Identifier", "")}):
                        remaining.append({
                            "type": "cognito-resource-server",
                            "name": server.get("Identifier", ""),
                        })
                domain = await _read(
                    "cognito-idp",
                    "DescribeUserPoolDomain",
                    {"Domain": _oauth_domain_name()},
                )
                if domain["status"] == "ok":
                    remaining.append({
                        "type": "cognito-user-pool-domain",
                        "name": _oauth_domain_name(),
                    })
                elif domain["status"] == "access_denied" or "ResourceNotFound" not in domain.get("error", ""):
                    return {
                        "status": "blocked",
                        "reason": "Cognito domain inventory was incomplete",
                        "errors": [domain],
                    }
        elif operation == "DescribeVpcs":
            for vpc in value.get("Vpcs", []):
                identity = {"name": vpc.get("VpcId", ""), "arn": ""}
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "vpc", "name": identity["name"]})
        elif operation == "DescribeSubnets":
            for subnet in value.get("Subnets", []):
                identity = {"name": subnet.get("SubnetId", ""), "arn": ""}
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "subnet", "name": identity["name"]})
        elif operation == "DescribeSecurityGroups":
            for group in value.get("SecurityGroups", []):
                identity = {"name": group.get("GroupName", ""), "arn": group.get("GroupId", "")}
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "security-group", "name": identity["name"] or identity["arn"]})
        elif operation == "DescribeRouteTables":
            for route_table in value.get("RouteTables", []):
                tags = {tag.get("Key"): tag.get("Value") for tag in route_table.get("Tags", [])}
                if tags.get("TPipeSmokeRun") == RUN_ID or _owns_manifest_resource({"name": tags.get("Name", "")}):
                    remaining.append({"type": "route-table", "name": route_table.get("RouteTableId", "")})
        elif operation == "DescribeInternetGateways":
            for gateway in value.get("InternetGateways", []):
                tags = {tag.get("Key"): tag.get("Value") for tag in gateway.get("Tags", [])}
                if tags.get("TPipeSmokeRun") == RUN_ID or _owns_manifest_resource({"name": tags.get("Name", "")}):
                    remaining.append({"type": "internet-gateway", "name": gateway.get("InternetGatewayId", "")})
        elif operation == "DescribeInstances":
            for reservation in value.get("Reservations", []):
                for instance in reservation.get("Instances", []):
                    identity = {"name": instance.get("InstanceId", ""), "arn": ""}
                    if _owns_manifest_resource(identity):
                        remaining.append({"type": "ec2-instance", "name": identity["name"]})
        elif operation == "ListInstanceProfiles":
            for profile in value.get("InstanceProfiles", []):
                identity = {"name": profile.get("InstanceProfileName", ""), "arn": profile.get("Arn", "")}
                if _owns_manifest_resource(identity):
                    remaining.append({"type": "instance-profile", "name": identity["name"] or identity["arn"]})
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
    if MODE == "create-ecr":
        return await _create_ecr_repositories()
    if MODE == "deploy-foundation":
        return await _deploy_foundation()
    if MODE == "deploy-control-plane":
        return await _deploy_control_plane()
    if MODE == "deploy-registry":
        return await _deploy_registry_stage()
    if MODE == "deploy-gateway":
        return await _deploy_gateway_stage()
    if MODE == "deploy-policy":
        return await _deploy_policy_stage()
    if MODE == "deploy-identity":
        return await _deploy_identity_stage()
    if MODE == "deploy-evaluation":
        return await _deploy_evaluation_stage()
    if MODE == "create-evaluation":
        return await _create_evaluation_stage()
    if MODE == "wait-evaluation":
        return await _wait_evaluation_stage()
    if MODE == "deploy-capacity":
        return await _deploy_capacity_stage()
    if MODE == "wait-capacity":
        return await _wait_capacity_stage()
    if MODE == "deploy-runtime":
        return await _deploy_runtime_stage()
    if MODE == "create-runtime":
        return await _create_runtime_stage()
    if MODE == "wait-runtime":
        return await _wait_runtime_stage()
    if MODE == "preflight":
        return await _preflight()
    if MODE == "deploy":
        return await _deploy()
    if MODE == "observability":
        return await _observability()
    if MODE == "cost":
        return await _cost_reading()
    if MODE == "cleanup":
        return await _cleanup()
    return {"status": "blocked", "reason": "MODE must be create-ecr, preflight, deploy, cleanup, observability, or cost"}


result = await _main()
result
