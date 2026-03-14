package com.TTT.TraceServer

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.jupiter.api.*
import java.nio.file.Paths
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceServerE2ETest {

    private lateinit var server: ApplicationEngine
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private val port = 8082

    @BeforeAll
    fun setupServerAndPlaywright() {
        println("Starting Ktor Server on port $port for Playwright test...")

        // Setup simple demo auth
        TraceServerRegistry.agentAuthMechanism = { token -> token == "Bearer agent-key" }
        TraceServerRegistry.clientAuthMechanism = { key -> key == "demo123" }

        server = embeddedServer(Netty, port = port, host = "127.0.0.1", module = io.ktor.server.application.Application::traceServerModule)
        server.start(wait = false)

        // Wait for server to bind
        Thread.sleep(2000)

        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))

        // Dispatch test traces directly via Registry (avoids Core JVM runtime version mismatch in daemon)
        injectMockTraces()
    }

    private fun injectMockTraces() {
        val mockHtml = """
            <html>
            <body style="background-color: #0f111a; color: #e2e8f0; font-family: monospace; padding: 20px;">
                <h2>Trace Rendered Output</h2>
                <div style="border-left: 2px solid #38bdf8; padding-left: 10px;">
                    <p><strong>Pipeline:</strong> E2E Testing Pipe</p>
                    <p>This is a simulated HTML payload for E2E tests showing rendered pipeline data.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        // Directly use Registry to ensure stability in the background test environment
        TraceServerRegistry.registerTrace(TracePayload(
            pipelineId = "test-e2e-pipeline-1",
            htmlContent = mockHtml,
            name = "E2E Automated Sync",
            status = "SUCCESS"
        ))

        Thread.sleep(500) // Ensure trace arrives before test
    }

    @Test
    fun `test dashboard renders trace list and iframe`() {
        val page = browser.newPage()

        try {
            // Navigate to Dashboard
            page.navigate("http://127.0.0.1:$port")

            // 1. Authenticate
            page.waitForSelector("#authOverlay", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE))
            page.fill("#authKey", "demo123")
            page.click(".auth-btn")

            // Wait for overlay to disappear
            page.waitForSelector("#authOverlay", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN))

            // 2. Wait for traces to load in the sidebar
            page.waitForSelector(".trace-item")

            // Verify trace is in the list
            val traceNameElements = page.locator(".trace-name")
            assertTrue(traceNameElements.count() > 0, "No traces found in the dashboard list.")
            assertTrue(traceNameElements.nth(0).textContent().contains("E2E Automated Sync"), "Expected trace name not found.")

            // Screenshot the list view
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/dashboard_list_test.png")))
            println("Saved screenshot: build/dashboard_list_test.png")

            // 3. Click the trace to load the iframe
            page.click(".trace-item")
            Thread.sleep(1000) // Wait for iframe to render

            // 4. Verify IFrame loaded correctly
            val iframeElement = page.locator("#trace-frame")
            assertTrue(iframeElement.isVisible, "Trace iframe is not visible.")

            // Since we use srcdoc or document.write, we can check the iframe content
            val frameLocator = page.frameLocator("#trace-frame")
            val frameHeading = frameLocator.locator("h2")
            assertTrue(frameHeading.count() > 0, "Rendered HTML content not found in iframe.")
            assertTrue(frameHeading.textContent().contains("Trace Rendered Output"), "Iframe HTML content did not render as expected.")

            // Screenshot the trace rendered view
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/dashboard_rendered_test.png")))
            println("Saved screenshot: build/dashboard_rendered_test.png")

        } catch (e: Exception) {
            // Take screenshot on failure for debugging
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/dashboard_failure.png")))
            throw e
        } finally {
            page.close()
        }
    }

    @AfterAll
    fun teardown() {
        println("Stopping Playwright and Ktor Server...")
        if (this::browser.isInitialized) browser.close()
        if (this::playwright.isInitialized) playwright.close()
        if (this::server.isInitialized) server.stop(1000, 5000)
    }
}
