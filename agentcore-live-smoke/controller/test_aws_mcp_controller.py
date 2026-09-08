"""Deterministic tests for controller-only AgentCore policy construction."""

import ast
from pathlib import Path


def _controller_policy_action_name():
    source = Path(__file__).with_name("aws_mcp_controller.py").read_text()
    tree = ast.parse(source)
    function = next(
        node for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "_policy_action_name"
    )
    namespace = {}
    exec(compile(ast.Module(body=[function], type_ignores=[]), "controller", "exec"), namespace)
    return namespace["_policy_action_name"]


def test_policy_action_name_is_gateway_qualified():
    gateway = "arn:aws:bedrock-agentcore:us-east-1:123456789012:gateway/gateway-1"
    assert _controller_policy_action_name()(gateway, "target_1", "smoke_forbidden") == "target_1___smoke_forbidden"


def test_consent_provider_uses_the_run_owned_authorization_client():
    source = Path(__file__).with_name("aws_mcp_controller.py").read_text()
    consent_section = source.split("consent_oauth = await", 1)[1].split("api_key = await", 1)[0]

    assert '"GenerateSecret": True' in source
    assert '"clientId": auth_client_data["ClientId"]' in consent_section
    assert '"credentialProviderVendor": "CognitoOauth2"' in consent_section
    assert '"includedOauth2ProviderConfig"' in consent_section
    assert '"authorizationEndpoint": authorization_endpoint' in consent_section
    assert '"tokenEndpoint": token_endpoint' in consent_section
    assert 'mcp_endpoint + "/authorize"' not in consent_section
    assert '"clientId": m2m_data["ClientId"]' not in consent_section


def test_fixture_consent_role_has_the_documented_token_flow_permissions():
    template = Path(__file__).parents[1].joinpath(
        "src/main/resources/cloudformation/tpipe-agentcore-live-fixtures.yaml"
    ).read_text()
    for action in (
        "bedrock-agentcore:GetOauth2CredentialProvider",
        "bedrock-agentcore:ListOauth2CredentialProviders",
        "bedrock-agentcore:CompleteResourceTokenAuth",
        "bedrock-agentcore:GetResourceOauth2Token",
        "bedrock-agentcore:GetWorkloadAccessTokenForJWT",
    ):
        assert action in template
    assert "token-vault/default/oauth2credentialprovider/*" in template
    assert "secretsmanager:GetSecretValue" in template
    assert "bedrock-agentcore-identity!default/oauth2/*" in template
    assert "aws:secretsmanager:owningService" in template
    assert "token-vault/default'" in template
