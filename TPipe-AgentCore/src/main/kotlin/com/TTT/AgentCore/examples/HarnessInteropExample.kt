package com.TTT.AgentCore.examples

import com.TTT.AgentCore.harness.AgentCoreHarnessAgent
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse

/** Wrap an external Harness worker as an explicitly selected generic P2P agent. */
fun harnessInteropExample(worker: suspend (P2PRequest) -> P2PResponse): AgentCoreHarnessAgent =
    AgentCoreHarnessAgent(worker)
