# 5. Worktree per task, named checks only

Status: accepted; containers are the next step

**Context.** Agents edit code and must prove it works, without access to anything else.

**Decision.** Each task gets its own git worktree on its own branch. Agent paths are confined to it.
The only commands are the checks named in the project file; there is no shell tool and no network tool.
Test runners and CI files are read-only.

**Consequences.** A small, auditable attack surface. Agents cannot install tools or explore with ad-hoc commands.
Checks still run code the agent wrote, as the botdesk OS user. Next: one container per check, `--network none`.

## Update after code review

A review found three gaps in the first version. Each now has a test in `WorkspaceTest`.

- `resolve(".")` checked the root's parent for links and always failed, so `list_files "."` and `search` never
  worked with a real model. The scripted model in the tests never asked for ".", which is why it slipped through.
- `search` read every file it listed without going through `resolve()`, so a symlink inside the repo could leak a
  file from outside it. Search now resolves each path and skips links.
- The model chooses the search regex. A pattern like `(.*a){12}x` backtracks for minutes. Search now wraps each line
  in a `CharSequence` that throws after a 3 second deadline. Small and dependency-free; a non-backtracking engine
  (RE2/J) would be the stronger fix if we ever accept a library.

