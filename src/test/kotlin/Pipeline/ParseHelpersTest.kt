package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseHelpersTest
{
    @Test
    fun testParseJudgeVerdictExtractsIsComplete()
    {
        val content = MultimodalContent(text = """{"isComplete": true, "shouldTerminate": false, "reason": "done"}""")
        val station = PumpStation()
        val verdict = station.parseJudgeVerdict(content)
        assertTrue(verdict.isComplete)
        assertEquals(null, verdict.reason)  // reason field is for halt, not for the JSON's "reason"
    }

    @Test
    fun testParseJudgeVerdictOnMalformedJsonReturnsEmpty()
    {
        val content = MultimodalContent(text = "garbage")
        val station = PumpStation()
        val verdict = station.parseJudgeVerdict(content)
        assertEquals(false, verdict.isComplete)
        assertEquals(false, verdict.shouldTerminate)
    }

    @Test
    fun testParseDispatchOutputExtractsPathName()
    {
        val content = MultimodalContent(text = """{"pathName": "test_path", "pathSchema": "{}"}""")
        val station = PumpStation()
        val request = station.parseDispatchOutput(content)
        assertNotNull(request)
        assertEquals("test_path", request.pathName)
    }

    @Test
    fun testParseDispatchOutputOnMalformedJsonReturnsNull()
    {
        val content = MultimodalContent(text = "garbage")
        val station = PumpStation()
        val request = station.parseDispatchOutput(content)
        assertNull(request)
    }
}
