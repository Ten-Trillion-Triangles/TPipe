package com.TTT.Structs

import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.*
import com.TTT.Util.serialize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertContains

class ModelReasoningTest {

    @Test
    fun testExplicitReasoningDetailedUnravel() {
        val explicitReasoning = ExplicitReasoningDetailed(
            coreAnalysis = CoreAnalysis(
                analysisSubject = "Test Subject",
                identifiedComponents = listOf("Comp1", "Comp2"),
                underlyingIssues = listOf("Issue1", "Issue2"),
                knownFacts = listOf("Fact1", "Fact2")
            ),
            logicalBreakdown = listOf("Breakdown1.", "Breakdown2."),
            sequentialReasoning = StepByStepProcess(
                totalSteps = 2,
                steps = listOf(
                    LogicalStep(
                        stepId = 1,
                        reasoningStep = "Step1",
                        contextualFocus = "Focus1",
                        considerations = "Cons1",
                        deductionProcess = "Ded1.",
                        conclusion = "Conc1",
                        reasoningExplanation = "Expl1.",
                        connectionToNext = "Conn1."
                    )
                ),
                dependencies = listOf("Dep1"),
                verificationPoints = listOf("Ver1")
            ),
            recommendedSteps = "Rec1."
        )

        val unraveled = explicitReasoning.unravel()

        assertTrue(unraveled.contains("Let me think through this: Test Subject."))
        assertTrue(unraveled.contains("The key components I need to consider are: Comp1, Comp2."))
        assertTrue(unraveled.contains("The main challenges are: Issue1, Issue2."))
        assertTrue(unraveled.contains("What I know: Fact1, Fact2."))
        assertTrue(unraveled.contains("Breakdown1. Breakdown2."))
        assertTrue(unraveled.contains("Step1. Looking at Focus1, I need to consider Cons1."))
        assertTrue(unraveled.contains("Ded1. This means: Conc1."))
        assertTrue(unraveled.contains("Expl1."))
        assertTrue(unraveled.contains("Conn1."))
        assertTrue(unraveled.contains("Based on this reasoning, the next steps should be: Rec1."))
    }

    @Test
    fun testStructuredCotUnravel() {
        val structuredCot = StructuredCot(
            componentIdentification = ComponentIdentification(
                taskElements = listOf("Elem1", "Elem2"),
                informationAndConstraints = listOf("Info1", "Info2"),
                whatNeedsToBeSolved = "Goal1"
            ),
            solutionDecomposition = SolutionDecomposition(
                subProblems = listOf("Sub1", "Sub2"),
                sequenceOfOperations = listOf("Op1", "Op2"),
                dependenciesBetweenSteps = listOf("Dep1", "Dep2")
            ),
            systematicExecution = SystemicExecution(
                subProblemApproaches = listOf(
                    SubProblemApproach("Sub1", "Strat1")
                ),
                intermediateSteps = listOf("IntStep1"),
                purposeOfEachStep = listOf("Purp1")
            ),
            reasoningSynthesis = ProcessFocusedResult(
                proposedApproach = "Prop1",
                approachValidation = "Val1",
                reasoningConclusion = "Conc1"
            )
        )

        val unraveled = structuredCot.unravel()

        assertTrue(unraveled.contains("I need to break this down systematically."))
        assertTrue(unraveled.contains("The key elements I'm working with are: Elem1, Elem2."))
        assertTrue(unraveled.contains("My constraints and available information: Info1, Info2."))
        assertTrue(unraveled.contains("What I'm ultimately trying to solve: Goal1."))
        assertTrue(unraveled.contains("I can decompose this into these sub-problems: Sub1, Sub2."))
        assertTrue(unraveled.contains("The sequence of operations will be: Op1, then Op2."))
        assertTrue(unraveled.contains("Dependencies between steps: Dep1, Dep2."))
        assertTrue(unraveled.contains("Now executing systematically: "))
        assertTrue(unraveled.contains("For Sub1: Strat1."))
        assertTrue(unraveled.contains("IntStep1 (Purp1)."))
        assertTrue(unraveled.contains("My proposed approach: Prop1."))
        assertTrue(unraveled.contains("Approach validation: Val1."))
        assertTrue(unraveled.contains("Conc1"))
    }

    @Test
    fun testProcessFocusedUnravel() {
        val processFocused = ProcessFocused(
            question = "Q1",
            knownData = "KD1",
            approach = "A1",
            workThroughEachStep = listOf(
                ProcessSteps("StepName1", "Just1", "Alt1")
            ),
            findings = "F1"
        )

        val unraveled = processFocused.unravel()

        assertTrue(unraveled.contains("The question I'm addressing: Q1."))
        assertTrue(unraveled.contains("What I know: KD1."))
        assertTrue(unraveled.contains("My approach will be: A1."))
        assertTrue(unraveled.contains("Working through this step by step: "))
        assertTrue(unraveled.contains("StepName1. Just1."))
        assertTrue(unraveled.contains("Alternative consideration: Alt1."))
        assertTrue(unraveled.contains("My findings: F1"))
    }

    @Test
    fun testProcessFocusedUnravelEmptyAlternativeAction() {
        val processFocused = ProcessFocused(
            question = "Q1",
            knownData = "KD1",
            approach = "A1",
            workThroughEachStep = listOf(
                ProcessSteps("StepName1", "Just1", "")
            ),
            findings = "F1"
        )

        val unraveled = processFocused.unravel()
        assertTrue(unraveled.contains("StepName1. Just1."))
        assertTrue(!unraveled.contains("Alternative consideration:"))
    }

    @Test
    fun testBestIdeaResponseUnravel() {
        val bestIdeaResponse = BestIdeaResponse(
            problemAnalysis = "ProbAn1",
            comparativeAnalysis = ComparativeAnalysis(
                consideredApproaches = listOf(
                    SolutionApproach("App1", "Desc1", listOf("Str1"), listOf("Weak1"), 8)
                ),
                selectionCriteria = listOf("Crit1"),
                tradeOffs = listOf("Trade1"),
                eliminatedApproaches = listOf("Elim1")
            ),
            selectedApproach = SolutionApproach("App1", "Desc1", listOf("Str1"), listOf("Weak1"), 8),
            justification = BestIdeaJustification(
                whyOptimal = "Opt1",
                expectedOutcomes = listOf("Out1"),
                riskMitigation = listOf("Risk1"),
                alternativesConsidered = "AltCons1"
            ),
            refinedSolution = RefinedSolution(
                coreConcept = "Core1",
                keyFeatures = listOf("Feat1"),
                implementationSteps = listOf("Impl1", "Impl2"),
                successMetrics = listOf("Succ1")
            )
        )

        val unraveled = bestIdeaResponse.unravel()

        assertTrue(unraveled.contains("ProbAn1"))
        assertTrue(unraveled.contains("I've considered several approaches: "))
        assertTrue(unraveled.contains("App1 - Desc1."))
        assertTrue(unraveled.contains("Strengths: Str1."))
        assertTrue(unraveled.contains("Weaknesses: Weak1."))
        assertTrue(unraveled.contains("Success potential: 8/10."))
        assertTrue(unraveled.contains("My selection criteria: Crit1."))
        assertTrue(unraveled.contains("Key tradeoffs: Trade1."))
        assertTrue(unraveled.contains("I eliminated these approaches: Elim1."))
        assertTrue(unraveled.contains("The best approach is App1: Desc1."))
        assertTrue(unraveled.contains("Why this is optimal: Opt1."))
        assertTrue(unraveled.contains("Expected outcomes: Out1."))
        assertTrue(unraveled.contains("Risk mitigation: Risk1."))
        assertTrue(unraveled.contains("The refined solution centers on: Core1."))
        assertTrue(unraveled.contains("Key features: Feat1."))
        assertTrue(unraveled.contains("Implementation: Impl1, then Impl2."))
        assertTrue(unraveled.contains("Success will be measured by: Succ1"))
    }

    @Test
    fun testMultiPhasePlanUnravel() {
        val multiPhasePlan = MultiPhasePlan(
            analysis = TaskAnalysis(
                whatNeedsSolving = "Solve1",
                limitations = listOf("Lim1"),
                desiredOutcomes = listOf("Out1"),
                importantFactors = listOf("Fact1")
            ),
            phases = listOf(
                Phase(
                    phaseTitle = "Title1",
                    purpose = "Purp1",
                    steps = listOf(
                        ExecutionStep("Desc1", listOf("Need1"), "Time1", listOf("Pre1"))
                    ),
                    checkpoints = listOf("Check1"),
                    phaseRisks = listOf(
                        RiskPlan("Risk1", "Like1", "Imp1", "Mit1", "Back1")
                    )
                )
            ),
            howToMeasureSuccess = listOf("Meas1"),
            totalDuration = "Dur1"
        )

        val unraveled = multiPhasePlan.unravel()

        assertTrue(unraveled.contains("Here's what needs solving: Solve1."))
        assertTrue(unraveled.contains("I'm working within these limitations: Lim1."))
        assertTrue(unraveled.contains("The desired outcomes are: Out1."))
        assertTrue(unraveled.contains("Important factors to consider: Fact1."))
        assertTrue(unraveled.contains("My plan consists of 1 phases over Dur1: "))
        assertTrue(unraveled.contains("Phase 1: Title1. Purp1."))
        assertTrue(unraveled.contains("Desc1."))
        assertTrue(unraveled.contains("Requirements: Need1."))
        assertTrue(unraveled.contains("Timeline: Time1."))
        assertTrue(unraveled.contains("Prerequisites: Pre1."))
        assertTrue(unraveled.contains("Checkpoints: Check1."))
        assertTrue(unraveled.contains("Risk: Risk1 (Like1 likelihood, Imp1 impact)."))
        assertTrue(unraveled.contains("Mitigation: Mit1. Backup: Back1."))
        assertTrue(unraveled.contains("Success will be measured by: Meas1"))
    }

    @Test
    fun testMethodActorResponseUnravel() {
        val methodActorResponse = MethodActorResponse(
            characterProfile = CharacterPerspective("Back1", "Exp1", "World1", listOf("Term1")),
            problemView = CharacterAnalysis("Prob1", listOf("Ins1"), "Meth1"),
            inCharacterThinking = CharacterReasoning(listOf("Thought1"), "AppExp1", "Style1"),
            characterSolution = CharacterSolution("App1", "Rat1", listOf("Adv1")),
            signatureStyle = "Sig1"
        )

        val unraveled = methodActorResponse.unravel()

        assertTrue(unraveled.contains("Drawing from my background in Exp1,"))
        assertTrue(unraveled.contains("Back1."))
        assertTrue(unraveled.contains("My worldview shapes how I see this: World1."))
        assertTrue(unraveled.contains("Prob1."))
        assertTrue(unraveled.contains("Ins1"))
        assertTrue(unraveled.contains("My approach: Meth1."))
        assertTrue(unraveled.contains("Thought1"))
        assertTrue(unraveled.contains("Applying my expertise: AppExp1."))
        assertTrue(unraveled.contains("Style1."))
        assertTrue(unraveled.contains("App1."))
        assertTrue(unraveled.contains("Rat1."))
        assertTrue(unraveled.contains("Adv1"))
        assertTrue(unraveled.contains("Sig1"))
    }

    @Test
    fun testChainOfDraftResponseUnravel() {
        val chainOfDraftResponse = ChainOfDraftResponse(
            problemAnalysis = "Prob1",
            draftSteps = listOf(
                DraftStep(1, "Draft1", "Op1", "Res1")
            ),
            finalCalculation = "Final1",
            answer = "Ans1"
        )

        val unraveled = chainOfDraftResponse.unravel()

        assertTrue(unraveled.contains("Looking at this problem: Prob1."))
        assertTrue(unraveled.contains("Draft1."))
        assertTrue(unraveled.contains("Op1."))
        assertTrue(unraveled.contains("This gives me: Res1."))
        assertTrue(unraveled.contains("Final reasoning: Final1."))
        assertTrue(unraveled.contains("Therefore: Ans1"))
    }

    @Test
    fun testChainOfDraftResponseUnravelEmptyOptionals() {
        val chainOfDraftResponse = ChainOfDraftResponse(
            problemAnalysis = "Prob1",
            draftSteps = listOf(
                DraftStep(1, "Draft1", "", "")
            ),
            finalCalculation = "",
            answer = "Ans1"
        )

        val unraveled = chainOfDraftResponse.unravel()
        assertTrue(unraveled.contains("Looking at this problem: Prob1."))
        assertTrue(unraveled.contains("Draft1."))
        assertTrue(!unraveled.contains("This gives me:"))
        assertTrue(!unraveled.contains("Final reasoning:"))
        assertTrue(unraveled.contains("Therefore: Ans1"))
    }


    @Test
    fun testExtractReasoningContentBestIdea() {
        val json = serialize(BestIdeaResponse(problemAnalysis = "TestBestIdea"))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("BestIdea", content)
        assertTrue(extracted.contains("TestBestIdea"))
    }

    @Test
    fun testExtractReasoningContentComprehensivePlan() {
        val json = serialize(MultiPhasePlan(analysis = TaskAnalysis(whatNeedsSolving = "TestComprehensivePlan")))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("ComprehensivePlan", content)
        assertTrue(extracted.contains("Here's what needs solving: TestComprehensivePlan."))
    }

    @Test
    fun testExtractReasoningContentRolePlay() {
        val json = serialize(MethodActorResponse(signatureStyle = "TestRolePlay"))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("RolePlay", content)
        assertTrue(extracted.contains("TestRolePlay"))
    }

    @Test
    fun testExtractReasoningContentExplicitCot() {
        val json = serialize(ExplicitReasoningDetailed(recommendedSteps = "TestExplicitCot"))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("ExplicitCot", content)
        assertTrue(extracted.contains("Based on this reasoning, the next steps should be: TestExplicitCot"))
    }

    @Test
    fun testExtractReasoningContentStructuredCot() {
        val json = serialize(StructuredCot(reasoningSynthesis = ProcessFocusedResult(reasoningConclusion = "TestStructuredCot")))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("StructuredCot", content)
        assertTrue(extracted.contains("TestStructuredCot"))
    }

    @Test
    fun testExtractReasoningContentProcessFocusedCot() {
        val json = serialize(ProcessFocused(findings = "TestProcessFocusedCot"))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("processFocusedCot", content)
        assertTrue(extracted.contains("My findings: TestProcessFocusedCot"))
    }

    @Test
    fun testExtractReasoningContentChainOfDraft() {
        val json = serialize(ChainOfDraftResponse(answer = "TestChainOfDraft"))
        val content = MultimodalContent(json)
        val extracted = extractReasoningContent("ChainOfDraft", content)
        assertTrue(extracted.contains("Therefore: TestChainOfDraft"))
    }

    @Test
    fun testExtractReasoningContentUnknownMethod() {
        val content = MultimodalContent("{}")
        val extracted = extractReasoningContent("UnknownMethod", content)
        assertEquals("", extracted)
    }

    @Test
    fun testExtractReasoningContentInvalidJson() {
        val content = MultimodalContent("{ invalid json }")
        // extractJson swallows the error and returns null, and it uses default values
        val extracted = extractReasoningContent("ChainOfDraft", content)
        // With default constructor, there should be "Therefore: " at least (or empty string answers)
        assertTrue(extracted.isNotEmpty())
    }
}
