# Operations

Build and test the optional module locally:

```bash
./gradlew :TPipe-AgentCore:test
./gradlew :TPipe-AgentCore:check
```

The runtime container example is ARM64-friendly and expects the application
to supply AWS credentials through the normal workload identity chain. Keep the
MCP and runtime ports private or place them behind an authenticated ingress.

Before deploying, verify:

1. the runtime session factory creates a fresh ordinary TPipe root for each
   isolated session;
2. idle session eviction is configured for the expected workload;
3. the AgentCore Memory id and namespace prefixes are correct;
4. OTEL exporter credentials and endpoint are configured outside source;
5. no static token appears in descriptors, request logs, or context records;
6. CloudFormation resource properties match the deployed AgentCore service
   version.
