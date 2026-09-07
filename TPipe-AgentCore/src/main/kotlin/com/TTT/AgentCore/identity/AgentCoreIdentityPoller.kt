package com.TTT.AgentCore.identity

import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ConsentPortalStatus
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetConsentPortalRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetConsentPortalResponse
import com.TTT.AgentCore.AgentCoreClients
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Bounded, cancellation-aware polling helpers for Identity resources. */
object AgentCoreIdentityPoller
{
    /** Wait until a consent portal becomes active or reaches a terminal state. */
    suspend fun <T> awaitConsentPortalActive(
        read: suspend () -> T,
        status: (T) -> ConsentPortalStatus?,
        timeoutMillis: Long = 30_000L,
        initialDelayMillis: Long = 100L,
        maxDelayMillis: Long = 2_000L
    ): T
    {
        require(timeoutMillis > 0L) { "Identity polling timeout must be positive." }
        require(initialDelayMillis > 0L) { "Identity polling delay must be positive." }
        require(maxDelayMillis >= initialDelayMillis) {
            "Identity polling maximum delay must not be less than the initial delay."
        }

        return withTimeout<T>(timeoutMillis)
        {
            var waitMillis = initialDelayMillis
            var terminalValue: T? = null
            while(terminalValue == null)
            {
                val value = read()
                when(status(value))
                {
                    ConsentPortalStatus.Active -> terminalValue = value
                    ConsentPortalStatus.Failed,
                    ConsentPortalStatus.UpdateFailed,
                    ConsentPortalStatus.Deleting -> error("Consent portal reached a terminal state.")
                    else -> {
                        delay(waitMillis)
                        waitMillis = if(waitMillis >= maxDelayMillis / 2L)
                        {
                            maxDelayMillis
                        }
                        else
                        {
                            waitMillis * 2L
                        }
                    }
                }
            }

            checkNotNull(terminalValue)
        }
    }

    /** Wait for a consent portal read to become active. */
    suspend fun awaitConsentPortalActive(
        admin: AgentCoreIdentityAdmin,
        request: GetConsentPortalRequest,
        timeoutMillis: Long = 30_000L,
        initialDelayMillis: Long = 100L,
        maxDelayMillis: Long = 2_000L
    ): GetConsentPortalResponse = awaitConsentPortalActive(
        read = { admin.getConsentPortal(request) },
        status = { it.status },
        timeoutMillis = timeoutMillis,
        initialDelayMillis = initialDelayMillis,
        maxDelayMillis = maxDelayMillis
    )
}

/** Named consent-portal poller facade for callers that prefer resource names. */
object AgentCoreConsentPortalPoller
{
    /** Poll a caller-owned consent portal read until it becomes active. */
    suspend fun <T> awaitActive(
        read: suspend () -> T,
        status: (T) -> ConsentPortalStatus?,
        timeoutMillis: Long = 30_000L,
        initialDelayMillis: Long = 100L,
        maxDelayMillis: Long = 2_000L
    ): T = AgentCoreIdentityPoller.awaitConsentPortalActive(
        read,
        status,
        timeoutMillis,
        initialDelayMillis,
        maxDelayMillis
    )

    /** Poll a control-plane consent portal read until it becomes active. */
    suspend fun awaitActive(
        admin: AgentCoreIdentityAdmin,
        request: GetConsentPortalRequest,
        timeoutMillis: Long = 30_000L,
        initialDelayMillis: Long = 100L,
        maxDelayMillis: Long = 2_000L
    ): GetConsentPortalResponse = AgentCoreIdentityPoller.awaitConsentPortalActive(
        admin,
        request,
        timeoutMillis,
        initialDelayMillis,
        maxDelayMillis
    )
}

/** Build the Identity poller API from shared clients. */
fun AgentCoreClients.identityPoller(): AgentCoreIdentityPoller = AgentCoreIdentityPoller
