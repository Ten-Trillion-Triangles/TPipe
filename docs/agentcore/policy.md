# Policy

`AgentCorePolicyAdmin` exposes the pinned Policy Engine and Cedar lifecycle
operations. `AgentCorePolicyConfig` defaults to `LOG_ONLY`; promotion to
`ENFORCE` must be explicit. Do not compile PCP rules or arbitrary TPipe
validation code into Cedar automatically.
