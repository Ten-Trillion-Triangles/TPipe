package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RunDispatchPhaseTest
{
    @Test
    fun testDispatchParsesPathRequest()
    {
        val station = buildTestStation()
        val dispatchPipe = ScriptedTestPipe(response = """{"pathName": "foo", "pathSchema": "{}"}""")
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("foo"))

        runBlocking {
            val request = station.runDispatchPhase()
            assertNotNull(request)
            assertEquals("foo", request.pathName)
        }
    }

    @Test
    fun testDispatchRepairFlowReparsesOnFailure()
    {
        val station = buildTestStation()
        val dispatchPipe = ScriptedTestPipe(response = """{"pathName": "foo", "pathSchema": "{}"}""")
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("foo"))
        station.failurePolicy.repairInvalidDispatchJson = true
        station.failurePolicy.maxDispatchRepairAttempts = 2

        runBlocking {
            val request = station.runDispatchPhase()
            assertNotNull(request)
        }
    }
}
