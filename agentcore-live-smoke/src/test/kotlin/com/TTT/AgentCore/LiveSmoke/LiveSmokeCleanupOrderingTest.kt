package com.TTT.AgentCore.LiveSmoke

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LiveSmokeCleanupOrderingTest
{
    @Test
    fun removesGatewayTargetsAndNetworkChildrenBeforeTheirParents()
    {
        val resources = listOf(
            resource("vpc"),
            resource("cloudformation-stack"),
            resource("gateway"),
            resource("gateway-target"),
            resource("subnet"),
            resource("security-group")
        )

        assertEquals(
            listOf("gateway-target", "gateway", "security-group", "subnet", "vpc", "cloudformation-stack"),
            LiveSmokeCleanupOrdering.reverseDependencyOrder(resources).map { it.type }
        )
    }

    private fun resource(type: String): OwnedResource = OwnedResource(
        type = type,
        name = "${type}_tpipe_smoke_test01",
        region = "us-east-1",
        runTag = "tpipe_smoke_test01"
    )
}
