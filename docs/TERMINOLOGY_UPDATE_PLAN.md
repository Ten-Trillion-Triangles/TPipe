# TPipe Documentation Terminology Update Plan

## Overview
This plan outlines the systematic update of TPipe documentation to replace "human-in-the-loop" terminology with "developer-in-the-loop" to better reflect the actual functionality and differentiate from competitors who use "human-in-the-loop" to refer to end users rather than developers.

## Rationale
- **Competitive Differentiation**: Other frameworks use "human-in-the-loop" to specify end user interaction, while TPipe's functionality is specifically designed for developers to maintain control over LLM behavior
- **Accuracy**: TPipe's HITL functions are developer-focused tools for validation, transformation, and control - not end-user interaction points
- **Clarity**: "Developer-in-the-loop" more accurately describes the technical nature and target audience of these features

## Terminology Mapping

### Primary Terms
- `human-in-the-loop` → `developer-in-the-loop`
- `Human-in-the-Loop` → `Developer-in-the-Loop`
- `HITL` → `DITL` (Developer-in-the-Loop)

### File Names
- `human-in-the-loop.md` → `developer-in-the-loop.md`
- `human-in-the-loop-pipes.md` → `developer-in-the-loop-pipes.md`

### URL References and Links
- All internal documentation links must be updated to reflect new file names
- All anchor links containing "human-in-the-loop" must be updated

## Files Requiring Updates

### 1. Core Documentation Files

#### `/README.md`
**Lines to Update:**
- Line 35: `#### Human-in-the-Loop Processing` → `#### Developer-in-the-Loop Processing`
- Line 36: `- [Human-in-the-Loop Functions](docs/core-concepts/human-in-the-loop.md)` → `- [Developer-in-the-Loop Functions](docs/core-concepts/developer-in-the-loop.md)`
- Line 37: `- [Human-in-the-Loop Pipes](docs/core-concepts/human-in-the-loop-pipes.md)` → `- [Developer-in-the-Loop Pipes](docs/core-concepts/developer-in-the-loop-pipes.md)`
- Line 132: `human-in-the-loop workflows` → `developer-in-the-loop workflows`
- Line 135: `**Human-in-the-loop integration**` → `**Developer-in-the-loop integration**`

#### `/docs/core-concepts/human-in-the-loop.md` → `/docs/core-concepts/developer-in-the-loop.md`
**Complete File Transformation:**
- Rename file from `human-in-the-loop.md` to `developer-in-the-loop.md`
- Update title: `# Human-in-the-Loop Functions` → `# Developer-in-the-Loop Functions`
- Update all section headers containing "HITL" → "DITL"
- Update all references to "human-in-the-loop" → "developer-in-the-loop"
- Update all instances of "HITL" → "DITL"
- Update description text to emphasize developer control and technical nature
- Update final link: `[Human-in-the-Loop Pipes](human-in-the-loop-pipes.md)` → `[Developer-in-the-Loop Pipes](developer-in-the-loop-pipes.md)`

#### `/docs/core-concepts/human-in-the-loop-pipes.md` → `/docs/core-concepts/developer-in-the-loop-pipes.md`
**Complete File Transformation:**
- Rename file from `human-in-the-loop-pipes.md` to `developer-in-the-loop-pipes.md`
- Update title: `# Human-in-the-Loop Pipes` → `# Developer-in-the-Loop Pipes`
- Update all section headers and anchor links
- Update all references to "human-in-the-loop" → "developer-in-the-loop"
- Update all instances of "HITL" → "DITL"
- Update description to emphasize developer-focused AI-powered processing chains

### 2. Cross-Reference Files

#### `/docs/core-concepts/pipeline-context-integration.md`
**Lines to Update:**
- Line 537: `human-in-the-loop functions` → `developer-in-the-loop functions`
- Line 539: `### Accessing MiniBank in HITL Functions` → `### Accessing MiniBank in DITL Functions`
- Line 560: `### MiniBank vs ContextWindow in HITL Functions` → `### MiniBank vs ContextWindow in DITL Functions`
- Line 1134: `**→ [Human-in-the-Loop Functions](human-in-the-loop.md)**` → `**→ [Developer-in-the-Loop Functions](developer-in-the-loop.md)**`

#### `/docs/core-concepts/pipeline-class.md`
**Lines to Update:**
- Line 381: `human-in-the-loop workflows` → `developer-in-the-loop workflows`

#### `/docs/bedrock/getting-started.md`
**Lines to Update:**
- Line 324: `### Human-in-the-Loop Functions` → `### Developer-in-the-Loop Functions`

## Implementation Steps

### Phase 1: File Renaming and Core Content Updates
1. **Rename core files:**
   ```bash
   mv docs/core-concepts/human-in-the-loop.md docs/core-concepts/developer-in-the-loop.md
   mv docs/core-concepts/human-in-the-loop-pipes.md docs/core-concepts/developer-in-the-loop-pipes.md
   ```

2. **Update primary documentation files:**
   - Update `developer-in-the-loop.md` (formerly `human-in-the-loop.md`)
   - Update `developer-in-the-loop-pipes.md` (formerly `human-in-the-loop-pipes.md`)
   - Update `README.md`

### Phase 2: Cross-Reference Updates
3. **Update all cross-referencing files:**
   - `pipeline-context-integration.md`
   - `pipeline-class.md`
   - `bedrock/getting-started.md`

### Phase 3: Validation and Testing
4. **Validate all internal links work correctly**
5. **Ensure all anchor links are properly updated**
6. **Verify no broken references remain**

## Content Guidelines

### Tone and Messaging
- Emphasize **developer control** and **technical precision**
- Highlight that these are **developer tools** for managing AI behavior
- Stress the **programmatic nature** of the intervention points
- Differentiate from **end-user interaction** patterns used by competitors

### Key Messaging Updates
- Replace phrases like "human intervention" with "developer intervention"
- Emphasize "developer control over AI processing"
- Highlight "programmatic validation and transformation"
- Stress "technical intervention points for developers"

### Consistency Requirements
- All instances of "HITL" must become "DITL"
- All file references must use new file names
- All anchor links must be updated to match new section headers
- All cross-references between documentation files must be updated

## Quality Assurance Checklist

### Pre-Implementation
- [ ] Identify all files containing the terminology
- [ ] Map all cross-references and links
- [ ] Plan file renaming strategy
- [ ] Prepare content transformation guidelines

### During Implementation
- [ ] Update file names systematically
- [ ] Transform content while maintaining technical accuracy
- [ ] Update all internal links and references
- [ ] Maintain consistent terminology throughout

### Post-Implementation
- [ ] Test all internal documentation links
- [ ] Verify anchor links work correctly
- [ ] Ensure no broken references exist
- [ ] Validate terminology consistency across all files
- [ ] Review content for clarity and accuracy

## Risk Mitigation

### Potential Issues
1. **Broken Links**: Internal documentation links may break during file renaming
2. **Inconsistent Updates**: Some references might be missed during bulk updates
3. **Anchor Link Failures**: Section header changes may break anchor links
4. **External References**: Any external documentation or code comments referencing the old terminology

### Mitigation Strategies
1. **Systematic Approach**: Update files in dependency order (referenced files first)
2. **Comprehensive Search**: Use multiple search patterns to catch all variations
3. **Link Validation**: Test all internal links after updates
4. **Staged Implementation**: Update in phases to catch issues early

## Success Criteria

### Completion Indicators
- [ ] All documentation files use "developer-in-the-loop" terminology consistently
- [ ] All file names reflect new terminology
- [ ] All internal links function correctly
- [ ] All cross-references are accurate
- [ ] Documentation maintains technical accuracy and clarity
- [ ] Terminology clearly differentiates TPipe from competitors

### Validation Methods
1. **Automated Search**: Verify no instances of old terminology remain
2. **Link Testing**: Validate all internal documentation links work
3. **Content Review**: Ensure messaging emphasizes developer focus
4. **Consistency Check**: Confirm uniform terminology usage across all files

## Timeline Estimate

- **Phase 1 (File Renaming and Core Updates)**: 2-3 hours
- **Phase 2 (Cross-Reference Updates)**: 1-2 hours  
- **Phase 3 (Validation and Testing)**: 1 hour
- **Total Estimated Time**: 4-6 hours

This systematic approach ensures complete and accurate terminology updates while maintaining documentation quality and preventing broken references.
