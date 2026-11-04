# 5. Worktree per task, named checks only

Status: accepted; containers are the next step

**Context.** Agents edit code and must prove it works, without access to anything else.

**Decision.** Each task gets its own git worktree on its own branch. Agent paths are confined to it.
The only commands are the checks named in the project file; there is no shell tool and no network tool.
Test runners and CI files are read-only.

**Consequences.** A small, auditable attack surface. Agents cannot install tools or explore with ad-hoc commands.
Checks still run code the agent wrote, as the botdesk OS user. Next: one container per check, `--network none`.
