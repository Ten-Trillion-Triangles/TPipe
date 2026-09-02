# CloudFormation and containers

`TPipe-AgentCore/src/main/resources/cloudformation/tpipe-agentcore.yaml`
creates an ARM64 container Runtime and conditionally creates RuntimeEndpoint,
Memory, Gateway, and Policy Engine resources. Runtime protocol values are
limited to `HTTP`, `MCP`, and `AGUI`; A2A is intentionally unsupported.

The Dockerfile is a reference JVM 24 ARM64 image. Consumers provide their own
application image and IAM role. Secrets belong in IAM, Secrets Manager, or
Identity resources, never CloudFormation parameters or runtime environment
variables.
