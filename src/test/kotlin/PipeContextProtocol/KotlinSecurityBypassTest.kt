package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KotlinSecurityBypassTest
{
    @Test
    fun testSystemExitBypass() = runBlocking {
        val script = "java.lang.System.exit(0)"
        val context = PcpContext()
        val validation = KotlinSecurityManager().validateKotlinRequest(script, KotlinContext(cinit = true), context)
        assertFalse(validation.isValid, "Should block System.exit even if fully qualified")
    }

    @Test
    fun testWildcardBypass() = runBlocking {
        val script = "import java.io.*"
        val context = PcpContext()
        val validation = KotlinSecurityManager().validateKotlinRequest(script, KotlinContext(cinit = true), context)
        assertFalse(validation.isValid, "Should block wildcard import")
    }

    @Test
    fun testSensitiveFileReadBypass() = runBlocking {
        val script = "val stream = java.io.FileInputStream(\"/etc/passwd\"); val content = stream.bufferedReader().readLine()"
        val context = PcpContext()
        val validation = KotlinSecurityManager().validateKotlinRequest(script, KotlinContext(cinit = true), context)
        assertFalse(validation.isValid, "Should block FileInputStream and readLine")
    }

    @Test
    fun testImportAliasBypass() = runBlocking {
        val script = "import java.io.File as JavaFile\nval f = JavaFile(\"test.txt\")"
        val context = PcpContext()
        val validation = KotlinSecurityManager().validateKotlinRequest(script, KotlinContext(cinit = true), context)
        assertFalse(validation.isValid, "Should block aliased import")
    }
}
