---
name: codebase-data-finder
description: |
  Investigates the codebase using codebase_investigator, searches for user-specified data, 
  and generates chart/bullet-point style reports. Strictly read-only; does not write files. 
  Use for code analysis and data discovery tasks.
---

# Codebase Data Finder Skill

This skill is designed to help you investigate and find specific information within the codebase without making any modifications. It leverages the `codebase_investigator` tool for comprehensive code analysis and searching capabilities.

## Workflow

### 1. Triggering the Skill

The skill is activated when you ask Gemini CLI to:
*   "Investigate the codebase for..."
*   "Find specific data in the code..."
*   "Search for [X] in the codebase..."
*   Any request that clearly indicates a need for code analysis and data discovery.

### 2. Gathering Information

Once activated, the skill will prompt you for the specific data or information you are looking for. For example:
*   "What specific function, variable, or pattern are you searching for?"
*   "Please provide keywords or a regular expression for your search."

### 3. Codebase Investigation

Using the provided information, the skill will invoke the `codebase_investigator` tool. The tool will analyze the codebase to locate the requested data.

### 4. Reporting Findings

The skill will present the findings in a structured format:
*   **Chart Style**: For summarizing high-level architectural information or dependency maps if `codebase_investigator` provides such output.
*   **Bullet Point Style**: For listing specific occurrences, file paths, or code snippets found.

The skill operates strictly in a read-only mode, ensuring no changes are made to the codebase.
