package genericOpenAIPipe.mantle

import genericOpenAIPipe.api.ApiMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [BedrockMantleConfiguration] — the configuration record
 * used by [GenericOpenAIPipe.setBedrockMantle] and the Mantle setters.
 */
class BedrockMantleConfigurationTest
{

    @Test
    fun endpoint_matchesAwsDocumentedFormat()
    {
        val config = BedrockMantleConfiguration.forRegion("us-east-1", "google.gemma-4-31b")
        assertEquals(
            "https://bedrock-mantle.us-east-1.api.aws/openai/v1",
            config.endpoint()
        )
    }

    @Test
    fun endpoint_variousRegions()
    {
        assertEquals(
            "https://bedrock-mantle.us-east-1.api.aws/openai/v1",
            BedrockMantleConfiguration.forRegion("us-east-1", "x").endpoint()
        )
        assertEquals(
            "https://bedrock-mantle.us-west-2.api.aws/openai/v1",
            BedrockMantleConfiguration.forRegion("us-west-2", "x").endpoint()
        )
        assertEquals(
            "https://bedrock-mantle.eu-west-1.api.aws/openai/v1",
            BedrockMantleConfiguration.forRegion("eu-west-1", "x").endpoint()
        )
    }

    @Test
    fun defaultApiMode_isOpenAI()
    {
        val config = BedrockMantleConfiguration.forRegion("us-east-1", "google.gemma-4-31b")
        assertEquals(ApiMode.OpenAI, config.apiMode)
    }

    @Test
    fun forRegionWithResponses_selectsResponsesApiMode()
    {
        val config = BedrockMantleConfiguration.forRegionWithResponses("us-east-1", "google.gemma-4-31b")
        assertEquals(ApiMode.OpenAIResponses, config.apiMode)
    }

    @Test
    fun blankRegionRejected()
    {
        try
        {
            BedrockMantleConfiguration.forRegion("", "google.gemma-4-31b")
            kotlin.test.fail("Expected IllegalArgumentException for blank region")
        }
        catch (e: IllegalArgumentException)
        {
            assertEquals("region cannot be blank", e.message)
        }
    }

    @Test
    fun blankModelIdRejected()
    {
        try
        {
            BedrockMantleConfiguration.forRegion("us-east-1", "")
            kotlin.test.fail("Expected IllegalArgumentException for blank modelId")
        }
        catch (e: IllegalArgumentException)
        {
            assertEquals("modelId cannot be blank", e.message)
        }
    }
}