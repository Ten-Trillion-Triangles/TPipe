package Defaults

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipeline.manifold
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the defaults-module manifold DSL bridge.
 */
class ManifoldDslDefaultsTest
{
    /**
     * Verifies that the defaults bridge can configure a Bedrock-backed manager and mirror its manager-memory settings
     * into the core manifold DSL.
     */
    @Test
    fun bedrockDefaultsConfigureManagerAndHistory()
    {
        val builtManifold = manifold {
            defaults {
                bedrock(
                    BedrockConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0"
                    )
                )
            }

            worker("implementation-worker") {
                description("Executes implementation tasks.")
                pipeline {
                    pipelineName = "implementation-worker-pipeline"
                    add(
                        DummyPipe()
                            .setPipeName("implementation-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertEquals("entry pipe", builtManifold.getManagerPipeline().getPipes()[0].pipeName)
        assertEquals("Agent caller pipe", builtManifold.getManagerPipeline().getPipes()[1].pipeName)
        assertTrue(builtManifold.isManagerBudgetControlEnabled())
    }

    /**
     * Verifies that non-default provider memory settings are mirrored into the manifold DSL path instead of being
     * silently dropped.
     */
    @Test
    fun bedrockDefaultsPreserveProviderMemoryConfiguration()
    {
        val builtManifold = manifold {
            defaults {
                bedrock(
                    BedrockConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0",
                        manifoldMemory = ManifoldMemoryConfiguration(
                            managerTokenBudget = TokenBudgetSettings(
                                contextWindowSize = 6144,
                                userPromptSize = 1536,
                                maxTokens = 256
                            ),
                            managerTruncationMethod = ContextWindowSettings.TruncateBottom
                        )
                    )
                )
            }

            worker("implementation-worker") {
                pipeline {
                    add(
                        DummyPipe()
                            .setPipeName("implementation-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertEquals(ContextWindowSettings.TruncateBottom, builtManifold.getTruncationMethod())
        assertEquals(6144, builtManifold.getManagerTokenBudget()!!.contextWindowSize)
        assertEquals(1536, builtManifold.getManagerTokenBudget()!!.userPromptSize)
    }

    /**
     * Verifies that a later history block can override only the desired defaults-seeded manager-memory settings.
     */
    @Test
    fun explicitHistoryOverridesDefaultsHistory()
    {
        val builtManifold = manifold {
            defaults {
                bedrock(
                    BedrockConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0",
                        manifoldMemory = ManifoldMemoryConfiguration(
                            managerTokenBudget = TokenBudgetSettings(
                                contextWindowSize = 6144,
                                userPromptSize = 1536,
                                maxTokens = 256
                            ),
                            managerTruncationMethod = ContextWindowSettings.TruncateBottom
                        )
                    )
                )
            }

            history {
                truncationMethod(ContextWindowSettings.TruncateMiddle)
                contextWindowSize(4096)
            }

            worker("implementation-worker") {
                pipeline {
                    add(
                        DummyPipe()
                            .setPipeName("implementation-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertEquals(ContextWindowSettings.TruncateMiddle, builtManifold.getTruncationMethod())
        assertEquals(6144, builtManifold.getManagerTokenBudget()!!.contextWindowSize)
        assertEquals(1536, builtManifold.getManagerTokenBudget()!!.userPromptSize)
    }

    /**
     * Verifies that defaults-backed DSL setup preserves the original fail-fast behavior when manager budgeting is
     * disabled and only compatibility overrides like truncation method or context-window size are supplied.
     */
    @Test
    fun defaultsFailFastWhenManagerBudgetControlIsDisabledAndOnlyCompatibilityOverridesExist()
    {
        val exception = assertFailsWith<Exception> {
            manifold {
                defaults {
                    bedrock(
                        BedrockConfiguration(
                            region = "us-east-1",
                            model = "anthropic.claude-3-haiku-20240307-v1:0",
                            manifoldMemory = ManifoldMemoryConfiguration(
                                enableManagerBudgetControl = false,
                                managerContextWindowSize = 4096,
                                managerTruncationMethod = ContextWindowSettings.TruncateBottom
                            )
                        )
                    )
                }

                worker("implementation-worker") {
                    pipeline {
                        add(
                            DummyPipe()
                                .setPipeName("implementation-worker")
                                .setContextWindowSize(2048)
                                .autoTruncateContext()
                        )
                    }
                }
            }
        }

        assertTrue(exception.message!!.contains("No method of managing manager shared history was found"))
    }

    /**
     * Verifies that an explicit manager token budget still enables manager history control even when the defaults
     * configuration otherwise disables compatibility auto-truncation.
     */
    @Test
    fun defaultsStillEnableBudgetControlWhenTokenBudgetProvided()
    {
        val builtManifold = manifold {
            defaults {
                bedrock(
                    BedrockConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0",
                        manifoldMemory = ManifoldMemoryConfiguration(
                            enableManagerBudgetControl = false,
                            managerTokenBudget = TokenBudgetSettings(
                                contextWindowSize = 6144,
                                userPromptSize = 1536,
                                maxTokens = 256
                            )
                        )
                    )
                )
            }

            worker("implementation-worker") {
                pipeline {
                    add(
                        DummyPipe()
                            .setPipeName("implementation-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertTrue(builtManifold.isManagerBudgetControlEnabled())
        assertEquals(6144, builtManifold.getManagerTokenBudget()!!.contextWindowSize)
        assertEquals(1536, builtManifold.getManagerTokenBudget()!!.userPromptSize)
    }

    /**
     * Verifies that a later explicit manager token budget remains a valid override even when the defaults profile
     * otherwise disables manager budgeting and only supplies compatibility history settings.
     */
    @Test
    fun explicitHistoryBudgetOverrideEnablesManagerHistoryControl()
    {
        val builtManifold = manifold {
            defaults {
                bedrock(
                    BedrockConfiguration(
                        region = "us-east-1",
                        model = "anthropic.claude-3-haiku-20240307-v1:0",
                        manifoldMemory = ManifoldMemoryConfiguration(
                            enableManagerBudgetControl = false,
                            managerContextWindowSize = 4096,
                            managerTruncationMethod = ContextWindowSettings.TruncateBottom
                        )
                    )
                )
            }

            history {
                managerTokenBudget(
                    TokenBudgetSettings(
                        contextWindowSize = 6144,
                        userPromptSize = 1536,
                        maxTokens = 256
                    )
                )
            }

            worker("implementation-worker") {
                pipeline {
                    add(
                        DummyPipe()
                            .setPipeName("implementation-worker")
                            .setContextWindowSize(2048)
                            .autoTruncateContext()
                    )
                }
            }
        }

        assertTrue(builtManifold.isManagerBudgetControlEnabled())
        assertEquals(6144, builtManifold.getManagerTokenBudget()!!.contextWindowSize)
        assertEquals(1536, builtManifold.getManagerTokenBudget()!!.userPromptSize)
    }

    private class DummyPipe : Pipe()
    {
        /**
         * No-op truncation implementation for defaults bridge tests.
         */
        override fun truncateModuleContext(): Pipe
        {
            return this
        }

        /**
         * Echo implementation used for startup-only defaults bridge tests.
         *
         * @param promptInjector Prompt content supplied by the framework.
         * @return Unchanged prompt text.
         */
        override suspend fun generateText(promptInjector: String): String
        {
            return promptInjector
        }

        /**
         * Echo implementation used for startup-only defaults bridge tests.
         *
         * @param promptInjector Prompt content supplied by the framework.
         * @return Unchanged content object.
         */
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            return content
        }
    }
}
