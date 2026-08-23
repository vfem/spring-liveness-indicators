# Agent Wiki Guide: Usage & Maintenance

This document provides guidelines for AI agents and human developers on how to navigate, use, and maintain the `docs/` wiki.

---

## 🤖 Guide for AI Agents: Zero-Read Navigation

This wiki is structured to allow AI coding agents to understand the entire architecture, class hierarchy, method contracts, and configuration options without performing token-heavy file reads of the entire repository.

### Recommended Agent Navigation Flow:

1. **Start at [`docs/README.md`](file:///c:/workdir/spring-liveness-indicators/docs/README.md)** to identify which domain is relevant (Auto-configuration, Core Checking Logic, Configuration, or Testing).
2. **Consult specific topic files**:
   - For configuration changes: See [Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md).
   - For auto-config & activation conditions: See [Auto-Configuration](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md).
   - For offset checking & consumer reflection details: See [Core Components](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md).
   - For test structure and assertions: See [Testing Guide](file:///c:/workdir/spring-liveness-indicators/docs/testing-guide.md).
3. **Follow Direct Symbol Links**: Only view targeted line numbers in code if you need to perform an exact code edit (e.g. using specific line-range links provided in the wiki).

---

## 🛠️ Maintenance Guide

When refactoring, adding new features, or changing configuration in this repository, keep the wiki in sync using these rules:

### 1. Document Structure & Length Rules
- **Keep files focused and concise**: Split topics into dedicated markdown files under `docs/` rather than creating monolithic files.
- **Maintain Clickable Links**:
  - Always link Java classes, interfaces, configuration files, and key methods using GitHub-style file URL links (`file:///...`).
  - Link specific line ranges when referencing implementation details (e.g., [`CommittedOffsetMovementCheck.java#L128-L141`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L128-L141)).

### 2. Update Checklist for Changes

| Change Type | Wiki Updates Required |
| :--- | :--- |
| **New Configuration Property** | 1. Update [Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md)<br>2. Update [Auto-Configuration](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md) if injected into constructor |
| **New Health Indicator / Condition** | 1. Update [Auto-Configuration](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md)<br>2. Update [Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md) sequence diagram |
| **Algorithm / Check Logic Changes** | 1. Update [Core Components](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md) (update flow diagram & branch descriptions)<br>2. Update line range links |
| **New Test Case or Strategy** | 1. Update [Testing Guide](file:///c:/workdir/spring-liveness-indicators/docs/testing-guide.md) scenario table |

### 3. Verification
- When updating markdown files, verify that relative paths and `file:///` URLs are intact and point to existing source files.
- Ensure all diagrams are valid Mermaid syntax.
