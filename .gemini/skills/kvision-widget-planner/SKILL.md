---
name: kvision-widget-planner
description: "Helps plan KVision widgets by discussing requirements, style, generating mockups, researching codebase and KVision documentation, creating a steering document, and generating an implementation plan for the ui-builder workflow."
---

# KVision Widget Planner Workflow

## Step 1: Requirement Gathering and Mockups

The user will request help planning the creation or modification of a KVision widget. You must:
- Discuss and gather all requirements from the user.
- Ask about the desired style and incorporate that information.
- Generate mock-up images to illustrate potential visual appearances.
- Iterate with the user until they indicate that this phase is complete.

## Step 2: Research and Validation

- Research the existing codebase to understand how successful KVision widgets are implemented.
- Use web search to thoroughly document and understand KVision.
- Provide grounding evidence for your research to avoid hallucination.
- Validate that you are not using "cowboy coding" practices such as raw DOM writes, CSS hacks, HTML hacks, or any other methods that bypass KVision's correct implementation.

## Step 3: Steering Document Generation

- Generate a steering document to guide the implementation process and ensure adherence to KVision rules.
- This document should confirm understanding of what is needed for correct implementation and keep the requirements at the forefront.

## Step 4: Implementation Plan Generation

- Generate a detailed plan that the user can review and iterate on.
- Ensure the plan incorporates the steering document and requirements gathered.
- This plan should be suitable for invoking the `ui-builder` workflow for implementation.
