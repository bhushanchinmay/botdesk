# botdesk

**An AI software team you attach to any git repo.** A lead plans, an engineer writes code in its own
sandboxed branch, quality gates re-run your build and tests, a reviewer scores the change, and a human
(you) approves before anything ships as a draft pull request.

It works with any LLM provider that speaks the OpenAI API format (OpenRouter, Experiential Labs,
Command Code, and most others). Plain Java 21, **zero external libraries**, about 100 MB of RAM.

## 1. The big picture

Think of a restaurant kitchen. Nobody serves a dish the head chef has not tasted.

| Kitchen | botdesk | Model role |
|---|---|---|
| Head chef writes the ticket | **Lead**: restates the request, writes a plan and "done means" | `lead` (strongest model) |
| Line cook at their own station | **Engineer**: edits code with tools, in its own git worktree | `engineer` |
| Thermometer and scales | **Quality gates**: re-run build and tests, enforce rules in plain code | none (code) |
| Head chef tastes the dish | **Reviewer**: scores 1-10, sends back anything under 8 | `reviewer` (different model family) |
| Owner signs off | **You**: approve the plan, approve the final commit | |
| Waiter serves the plate | **Release**: pushes the exact approved commit, opens a draft PR | none (code) |

```
 You ── dashboard / chat / Slack ──► TaskService ──► Store (data/store.json)
                                         ▲
 Orchestrator (every 5 s) ───────────────┘
   lead ─► plan ─► [you approve] ─► engineer (ToolLoopAgent in a git worktree)
   ─► gates (re-run checks, rules in code) ─► reviewer (score) ─► [you approve] ─► release (push + draft PR)
                                         │
 OpenAiCompatibleClient ──► OpenRouter / Experiential / Command Code / ...
```

## 2. What the team can do (playbooks)

| Kind | What it does | Rules enforced in code |
|---|---|---|
| `feature` | Build a new capability | Must change code and a test |
| `bugfix` | Fix a reported bug, failing test first | Must change code and a test |
| `bughunt` | Find unreported bugs, prove each with a test, fix | A test must change if code changes; "found nothing" is allowed |
| `maintenance` | Keep the build healthy: fix broken checks, warnings, small upgrades | A health report with no change is allowed |
| `prototype` | Turn an idea into a small working project | Must change code and a test |
| `investigate` | Support: answer a question about the code, triage an issue | Read-only: any file change fails |

Each playbook is one JSON file in `playbooks/`. Add your own the same way.

## 3. Safety rules (all enforced in code, not prompts)

1. **Plan before code.** Nothing is written until you approve the lead's plan.
2. **Sandboxed files.** Agents can only touch files inside their task's worktree: no `../`, no symlink escapes, no `.git`.
3. **No free-form shell.** The only commands an agent can run are the project's named checks (for example `build`, `test`).
4. **Protected paths.** Test runners and CI config are read-only, so an agent cannot "fix" a test by weakening it.
5. **Trust nothing self-reported.** botdesk re-runs every check itself after the agent finishes.
6. **Never weaken tests.** Deleting a test file fails the gates. Bug fixes and features must add or change a test.
7. **Secret scan.** Added lines that look like API keys or private keys fail the gates.
8. **Max 2 repair cycles.** Then a human decides. No endless loops.
9. **Approval belongs to a commit.** If the branch moves after you approve, release refuses to ship it.
10. **Never touches `main`.** Work happens on `botdesk/task-N`. Release opens a **draft** PR; a human merges.
11. **Budget guard.** Calls stop before the monthly or per-task budget is crossed.

## 4. Try the demo in 5 minutes

```bash
./scripts/demo-setup.sh                                          # creates demo/shop-cart (a repo with a real bug) and attaches it
security add-generic-password -U -s botdesk -a OPENROUTER_API_KEY -w   # store your key in the macOS Keychain
./scripts/run-local.sh                                           # http://localhost:8080
```

Then on the dashboard:

1. **+ New task**. Project `shop-cart`, kind `bugfix`, title: *SAVE10 takes 19% off instead of 10%*.
   Details: *With 2 pens (150c each) and a book (1200c), SAVE10 gives 1215 instead of 1350.*
2. Read the lead's plan in **Needs you** and approve it.
3. Watch the task page: the engineer's tool calls appear live on the timeline.
4. When the reviewer passes it, read the diff and approve. The fix lands on branch `botdesk/task-1`.

## 5. Attach your own project

```bash
./scripts/attach.sh ~/code/my-service my-service owner/my-service
```

It detects Maven, Gradle, npm, Python or `build.sh`/`test.sh`, and writes `projects/my-service.json`:

```json
{
  "name": "my-service",
  "repo": "/Users/you/code/my-service",
  "baseBranch": "main",
  "checks": { "build": "mvn -q -B -DskipTests compile", "test": "mvn -q -B test" },
  "context": ["README.md"],
  "testPaths": ["src/test/"],
  "protectedPaths": ["pom.xml", ".github/"],
  "githubRepo": "owner/my-service",
  "remote": "origin"
}
```

**Review this file.** `checks` are the only commands agents may run. Restart botdesk to load it.

**Draft pull requests:** set `githubRepo`, then store a GitHub fine-grained token with access to that repo
only (permissions: Contents read/write, Pull requests read/write) as `GITHUB_TOKEN`. Pushing uses your normal
git credentials for that remote. Without a token, release keeps the branch locally for you to merge.

## 6. Models (`botdesk.json`)

```json
"roles": {
  "lead":     { "provider": "openrouter", "model": "anthropic/claude-opus-5.5" },
  "engineer": { "provider": "openrouter", "model": "anthropic/claude-sonnet-5.5" },
  "reviewer": { "provider": "openrouter", "model": "openai/gpt-5.5" }
}
```

- **Check every model ID** on your provider's model list. The engineer model **must support tool calling**.
- Use a **different model family** for the reviewer, so it does not share the engineer's blind spots.
- OpenRouter reports real cost per call. For other providers set `inputUsdPerM` / `outputUsdPerM` so the budget guard works.
- Commits use the `git` name and email in `botdesk.json`.
- Other settings: `maxAgentSteps` (30), `reviewPassScore` (8), `maxRepairCycles` (2), `checkTimeoutSeconds` (600).
- **Routines** run a task on a schedule, for example nightly `maintenance`. Minimum interval: 1 hour.

## 7. Where to run it

| | Local (Mac) | VPS |
|---|---|---|
| Start | `./scripts/run-local.sh` (keys from Keychain) | `./deploy/deploy.sh user@host` |
| Auto-start | `./scripts/autostart-mac.sh on` | systemd service, included |
| Dashboard | http://localhost:8080 | `ssh -N -L 8080:localhost:8080 user@host` |
| Good for | Demos, small repos | Nightly routines, bigger builds |

botdesk itself needs about 100 MB. **Your project's build and tests are the real cost**: a Maven build wants 1 to 2 GB.
On a VPS, use at least 2 GB of RAM (Oracle Cloud Always Free Ampere A1 works).
VPS setup: `deploy/setup-vps.sh` installs Java 21 and git, creates a locked-down `botdesk` user, and a systemd service.
The service may only write inside `/opt/botdesk/data`, and `git worktree add` writes into each project's `.git` folder,
so **clone attached projects under `/opt/botdesk/data/repos/`** on the VPS (as the `botdesk` user).

## 8. Slack (optional)

Socket Mode, so no public URL is needed. Create an app, enable Socket Mode, an app token with `connections:write`
(`SLACK_APP_TOKEN`), bot scopes `chat:write`, `app_mentions:read`, `im:history`, `im:read`, subscribe to `app_mention`
and `message.im`, install, then set `"slack": { "enabled": true, "notifyChannel": "C0123..." }`.
DM or @mention the bot. Commands: `status`, `approve 12`, `reject 12 reason`, `reply 12 text`.
Anything else goes to the lead, which can create tasks for you.

## 9. Develop and test

```bash
./build.sh          # builds botdesk.jar with plain javac
./build.sh test     # 28 tests: real git repos, a scripted fake model; no network, no API cost
```

## 10. Design decisions (and trade-offs)

| Decision | Why | Trade-off |
|---|---|---|
| Own agent loop (`ToolLoopAgent`, about 150 lines) behind a `CodingEngine` interface | Every step is explainable and logged; an external agent (OpenCode, aider) can plug in later | Less capable than mature coding agents on large refactors |
| Gates in code before the reviewer model | A model can ignore a prompt, not code. Also saves reviewer tokens | Each new rule is a small code change |
| Git worktree per task | Parallel-safe, never touches `main`, easy cleanup | Needs a local clone of each project |
| Named checks only, no shell tool | Small, auditable attack surface | Agents cannot install tools or explore with ad-hoc commands |
| Approval bound to a commit SHA | What you reviewed is exactly what ships | Any later change needs approval again |
| Single orchestrator thread | No races between task steps | One step at a time; fine for one person |
| One JSON file as the store | Zero setup; atomic write via temp file + rename | Move to SQLite past a few thousand tasks; only `Store` changes |
| No framework, no libraries | About 100 MB RAM, every line readable | Hand-written JSON and HTTP routing |

## 11. Known limits and next steps

- **Sandboxing is process-level.** Checks run your project's own build and tests, which execute code the agent wrote.
  On a VPS the service runs as a locked-down user. Next step: run each check inside a Docker container with no network.
- **One engineer per task.** Next: the lead splits large features into ordered sub-tasks.
- **Corrections loop.** Next: turn repeated review feedback into new playbook rules, with your approval.
