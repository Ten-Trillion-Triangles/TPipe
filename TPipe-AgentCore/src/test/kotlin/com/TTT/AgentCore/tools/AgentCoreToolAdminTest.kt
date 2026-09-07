package com.TTT.AgentCore.tools

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeCodeInterpreterRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StartBrowserSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StartBrowserSessionResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StartCodeInterpreterSessionResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.BrowserNetworkMode
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.BrowserStatus
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CodeInterpreterNetworkMode
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CodeInterpreterStatus
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateBrowserRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateBrowserResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateBrowserProfileRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateBrowserProfileResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateCodeInterpreterRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateCodeInterpreterResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.BrowserProfileStatus
import aws.smithy.kotlin.runtime.time.Instant
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AgentCoreToolAdminTest
{
    @Test
    fun controlPlaneAdminsForwardGeneratedRequestsWithoutCopyingThem()
    {
        runBlocking {
            val requests = mutableMapOf<String, Any>()
            val controlClient = controlClientProxy(requests)
            val browserRequest = CreateBrowserRequest {
                name = "browser"
                networkConfiguration {
                    networkMode = BrowserNetworkMode.Vpc
                }
            }
            val profileRequest = CreateBrowserProfileRequest {
                name = "profile"
            }
            val codeInterpreterRequest = CreateCodeInterpreterRequest {
                name = "code"
                networkConfiguration {
                    networkMode = CodeInterpreterNetworkMode.Sandbox
                }
            }

            assertEquals("browser", AgentCoreBrowserAdmin(controlClient).create(browserRequest).browserId)
            assertEquals("profile", AgentCoreBrowserProfileAdmin(controlClient).create(profileRequest).profileId)
            assertEquals(
                "code",
                AgentCoreCodeInterpreterAdmin(controlClient).create(codeInterpreterRequest).codeInterpreterId
            )

            assertSame(browserRequest, requests["createBrowser"])
            assertSame(profileRequest, requests["createBrowserProfile"])
            assertSame(codeInterpreterRequest, requests["createCodeInterpreter"])
            assertEquals(BrowserNetworkMode.Vpc, browserRequest.networkConfiguration?.networkMode)
            assertEquals(CodeInterpreterNetworkMode.Sandbox, codeInterpreterRequest.networkConfiguration?.networkMode)
        }
    }

    @Test
    fun generatedSessionAndFilesystemArgumentsReachDataPlaneWithoutCopies()
    {
        runBlocking {
            val requests = mutableMapOf<String, Any>()
            val dataClient = dataClientProxy(requests)
            val browserRequest = StartBrowserSessionRequest {
                browserIdentifier = "browser"
                profileConfiguration {
                    profileIdentifier = "profile"
                }
                name = "session"
            }
            val codeInterpreterRequest = StartCodeInterpreterSessionRequest {
                codeInterpreterIdentifier = "code"
                name = "session"
            }
            val invokeRequest = InvokeCodeInterpreterRequest {
                codeInterpreterIdentifier = "code"
                sessionId = "code-session"
                arguments {
                    directoryPath = "/workspace"
                    path = "/workspace/input.csv"
                    paths = listOf("/workspace/input.csv", "/workspace/output.csv")
                    command = "must remain in the data-plane request"
                }
            }

            AgentCoreBrowserClient(dataClient).startSession(browserRequest, "owner")
            AgentCoreCodeInterpreterClient(dataClient).startSession(codeInterpreterRequest, "owner")
            AgentCoreCodeInterpreterClient(dataClient).invoke(invokeRequest) { }

            assertSame(browserRequest, requests["startBrowserSession"])
            assertSame(codeInterpreterRequest, requests["startCodeInterpreterSession"])
            assertSame(invokeRequest, requests["invokeCodeInterpreter"])
            assertEquals("profile", browserRequest.profileConfiguration?.profileIdentifier)
            assertEquals("/workspace/input.csv", invokeRequest.arguments?.path)
            assertEquals(listOf("/workspace/input.csv", "/workspace/output.csv"), invokeRequest.arguments?.paths)
        }
    }

    private fun controlClientProxy(requests: MutableMap<String, Any>): BedrockAgentCoreControlClient
    {
        val responses = mapOf(
            "createBrowser" to CreateBrowserResponse {
                browserArn = "arn:browser"
                browserId = "browser"
                createdAt = Instant(java.time.Instant.EPOCH)
                status = BrowserStatus.Ready
            },
            "createBrowserProfile" to CreateBrowserProfileResponse {
                createdAt = Instant(java.time.Instant.EPOCH)
                profileArn = "arn:profile"
                profileId = "profile"
                status = BrowserProfileStatus.Ready
            },
            "createCodeInterpreter" to CreateCodeInterpreterResponse {
                codeInterpreterArn = "arn:code"
                codeInterpreterId = "code"
                createdAt = Instant(java.time.Instant.EPOCH)
                status = CodeInterpreterStatus.Ready
            }
        )
        return proxyFor(BedrockAgentCoreControlClient::class.java, requests, responses)
    }

    private fun dataClientProxy(requests: MutableMap<String, Any>): BedrockAgentCoreClient
    {
        val responses = mapOf(
            "startBrowserSession" to StartBrowserSessionResponse {
                browserIdentifier = "browser"
                createdAt = Instant(java.time.Instant.EPOCH)
                sessionId = "browser-session"
            },
            "startCodeInterpreterSession" to StartCodeInterpreterSessionResponse {
                codeInterpreterIdentifier = "code"
                createdAt = Instant(java.time.Instant.EPOCH)
                sessionId = "code-session"
            },
            "invokeCodeInterpreter" to Unit
        )
        return proxyFor(BedrockAgentCoreClient::class.java, requests, responses)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxyFor(
        serviceType: Class<T>,
        requests: MutableMap<String, Any>,
        responses: Map<String, Any>
    ): T
    {
        val handler = InvocationHandler { proxy, method, arguments ->
            when(method.name)
            {
                "toString" -> "test-proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "close" -> null
                else -> {
                    arguments
                        ?.firstOrNull { argument -> argument !is Continuation<*> }
                        ?.let { request -> requests[method.name] = request }
                    responses[method.name] ?: error("No response configured for ${method.name}")
                }
            }
        }
        return Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType), handler) as T
    }
}
