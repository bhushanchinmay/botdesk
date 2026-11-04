# Extending botdesk

Every extension point is one interface. Pick the recipe for what you want to add.

| You want to add | Implement | Register it in |
|---|---|---|
| A model provider | nothing: any OpenAI-compatible API works | `providers` in `botdesk.json` |
| A kind of work | nothing: a JSON file | `playbooks/<kind>.json` |
| A project | nothing: run `./scripts/attach.sh` | `projects/<name>.json` |
| A quality rule | `quality.Gate` | `QualityGates.defaults()` or `.with(gate)` |
| An agent tool | `agent.tool.Tool` | `BuiltInTools.all()` or `ToolRegistry.with(tool)` |
| A pipeline step | `orchestrator.stage.Stage` | `Orchestrator.defaultStages(...)` or the `Orchestrator(services, stages)` constructor |
| A different coding agent | `agent.CodingEngine` | `Main` |
| A database | `store.Store` | `Main` |
| A notification channel | `orchestrator.Notifier` | `TaskService.setNotifier` |
| A setting | a record field in `Config` | its key in the matching `*_KEYS` set, or startup rejects it as unknown |

## A quality rule

Rules are plain code and must be pure: no I/O, no model calls.

```java
final class NoTodoGate implements Gate {
    public String name() { return "no-todo"; }
    public List<String> check(GateInput in) {
        return in.diff().lines().anyMatch(l -> l.startsWith("+") && l.contains("TODO"))
                ? List.of("Do not add TODO comments; finish the work or report it as a blocker.")
                : List.of();
    }
}
```

Word the message for the engineer model: it is sent back as feedback.

## An agent tool

A tool acts only through the `Workspace`, so it cannot widen the sandbox. Throw `SecurityException`
to refuse; the agent shows the model "REFUSED: ...". Keep results short: every byte is tokens.

## A pipeline step

A stage handles one or more states and must leave the task in a different state, or throw.
Exceptions become a FAILED task with a retry button, so you do not need try/catch for normal errors.
Each state can belong to only one stage; the Orchestrator refuses duplicates at startup.

## Rules of thumb

1. If code can decide it, code decides it. Models are for judgement, not for checks.
2. New behaviour comes with a test. `Fixtures.FakeLlm` scripts model replies, so tests are free and repeatable.
3. Every model call should be worth its tokens. See "Token use" in the README.
