# AgentCore Payments

Payments are explicit opt-in operations. `AgentCorePaymentAdmin` manages
PaymentManager, PaymentConnector, and payment credential-provider resources;
`AgentCorePaymentsClient` exposes instrument/session lifecycle and
`processPayment` as a direct caller operation.

The facade never retries or automatically completes a payment after an HTTP
402 or a payment challenge. A caller must inspect the challenge, obtain
authorization for the intended amount, and explicitly call the provider-native
payment operation. A network timeout after submission is ambiguous and should
be resolved by reading session state before another attempt.

Payment credentials belong in AgentCore's managed credential storage or an
existing Secrets Manager reference. TPipe redacts payment proof and
credential-shaped metadata and does not write payment material to traces,
P2P descriptors, or ContextBank persistence.
