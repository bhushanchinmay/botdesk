# 1. Own agent loop behind an interface

Status: accepted

**Context.** The engineer needs a tool-calling loop. Mature agents (OpenCode, aider) exist.

**Decision.** Write a small loop (`ToolLoopAgent`) behind `CodingEngine`. Tools are a registry, not a switch.

**Consequences.** Every step is visible on the task timeline and explainable. It is weaker than mature
agents on large refactors. Swapping in another agent means implementing `CodingEngine`; the pipeline does not change.
