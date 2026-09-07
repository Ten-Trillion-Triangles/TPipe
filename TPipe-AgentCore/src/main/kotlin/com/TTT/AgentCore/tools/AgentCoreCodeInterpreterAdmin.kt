package com.TTT.AgentCore.tools

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Control-plane administration for custom AgentCore Code Interpreter resources. */
class AgentCoreCodeInterpreterAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a custom Code Interpreter. */
    suspend fun create(request: CreateCodeInterpreterRequest): CreateCodeInterpreterResponse =
        client.createCodeInterpreter(request)
    /** Get a custom Code Interpreter. */
    suspend fun get(request: GetCodeInterpreterRequest): GetCodeInterpreterResponse =
        client.getCodeInterpreter(request)
    /** List custom Code Interpreters. */
    suspend fun list(request: ListCodeInterpretersRequest): ListCodeInterpretersResponse =
        client.listCodeInterpreters(request)
    /** Delete a custom Code Interpreter. */
    suspend fun delete(request: DeleteCodeInterpreterRequest): DeleteCodeInterpreterResponse =
        client.deleteCodeInterpreter(request)
}

/** Build Code Interpreter administration from the shared client bundle. */
fun AgentCoreClients.codeInterpreterAdmin(): AgentCoreCodeInterpreterAdmin = AgentCoreCodeInterpreterAdmin(control)
