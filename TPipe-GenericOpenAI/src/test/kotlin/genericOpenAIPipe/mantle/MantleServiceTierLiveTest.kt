package genericOpenAIPipe.mantle

import genericOpenAIPipe.GenericOpenAIPipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live verification that Amazon Bedrock Mantle honors and echoes the
 * `service_tier` request field end-to-end.
 *
 * Request Flex against `google.gemma-4-31b` in us-east-1 and read back
 * the tier that actually served the response via
 * [GenericOpenAIPipe.lastResponseServiceTier]. Flex shares on-demand
 * quota with the standard tier, so Mantle may legally serve the request
 * on `default` or `priority` — the assertion is echo-presence plus
 * membership in the legal value set, not exact match of the requested
 * tier. A `null` echo is also tolerated (the field is provider-optional
 * on the OpenAI wire spec): the marker line records what the provider
 * did, and the operator decides whether the contract changed.
 *
 * Gating: runs ONLY when `BEDROCK_MANTLE_LIVE_TEST=true`; the live call
 * additionally skips cleanly when AWS SigV4 credentials are not
 * configured (JUnit `assumeTrue`), matching [genericOpenAIPipe.BedrockMantleLiveTest].
 *
 * Run with:
 * ```
 * BEDROCK_MANTLE_LIVE_TEST=true \
 * AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... \
 * BEDROCK_MANTLE_REGION=us-east-1 \
 * ./gradlew :TPipe-GenericOpenAI:test --tests "genericOpenAIPipe.mantle.MantleServiceTierLiveTest" --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MantleServiceTierLiveTest
{
    companion object
    {
        private const val DEFAULT_MODEL: String = "google.gemma-4-31b"
        private const val TEST_PROMPT: String = "Reply with the single word 'pong'."
        private const val MAX_TOKENS: Int = 64
    }

    @BeforeAll
    fun assumeCredentialsConfigured()
    {
        val accessKeyId = System.getenv("AWS_ACCESS_KEY_ID") ?: ""
        val secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY") ?: ""
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY env vars must be set to run live tests"
        )
    }

    @Test
    fun testMantleServiceTierEchoesServedTier() = runBlocking<Unit>
    {
        val region = System.getenv("BEDROCK_MANTLE_REGION")?.takeIf { it.isNotBlank() } ?: "us-east-1"
        val pipe = GenericOpenAIPipe()
            .setBedrockMantle(region, DEFAULT_MODEL)
            .setBedrockMantleServiceTier(MantleServiceTier.Flex)
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.init()
        val response = pipe.execute(TEST_PROMPT)
        val served = pipe.lastResponseServiceTier
        assertTrue(
            served == null || served in setOf("default", "priority", "flex"),
            "served tier must be null or one of default/priority/flex, got: $served"
        )
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "expected the single-word reply, got: $response"
        )
        System.out.println("MANTLE-TIER-LIVE region=$region requested=flex served=$served text=$response")
    }
}
