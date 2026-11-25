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
cp botdesk.example.json botdesk.json                              # your settings; git-ignored
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

`botdesk.json` is your own copy of `botdesk.example.json` and is git-ignored, so your model picks and git identity stay local.

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

`botdesk.json` documents each setting with `//` comments. Config files are checked strictly at startup:
an unknown key (a typo like `reviewPassScor`), a wrong type (`"port": "8080"`) or an out-of-range number
(`"reviewPassScore": 80`) stops botdesk with a `Config error:` line that names the key. The same check covers
`projects/*.json` and `playbooks/*.json`. Run `./scripts/run-local.sh --check` to validate without starting.

## 7. Where to run it

| | Local (Mac) | VPS |
|---|---|---|
| Start | `./scripts/run-local.sh` (keys from Keychain) | `./deploy/deploy.sh user@host` |
| Auto-start | `./scripts/autostart-mac.sh on` | systemd service, included |
| Dashboard | http://localhost:8080 | `ssh -N -L 8080:localhost:8080 user@host` |

The dashboard only answers requests whose Host header is `localhost`, `127.0.0.1`, `[::1]` or the bind address.
This blocks DNS rebinding (a hostile site making its own domain point at 127.0.0.1). If you open it through another
name, such as a Tailscale hostname, add that name to `"server": { "allowedHosts": ["my-vps"] }`.
| Good for | Demos, small repos | Nightly routines, bigger builds |

botdesk itself needs about 100 MB. **Your project's build and tests are the real cost**: a Maven build wants 1 to 2 GB.
On a VPS, use at least 2 GB of RAM (Oracle Cloud Always Free Ampere A1 works).
VPS setup: `deploy/setup-vps.sh` installs Java 21 and git, creates a locked-down `botdesk` user, and a systemd service.
The service may only write inside `/opt/botdesk/data`, and `git worktree add` writes into each project's `.git` folder,
so **clone attached projects under `/opt/botdesk/data/repos/`** on the VPS (as the `botdesk` user).

## 8. Slack (optional)

Socket Mode, so no public URL is needed. Create an app, enable Socket Mode, an app token with `connections:write`
(`SLACK_APP_TOKEN`), bot scopes `chat:write`, `app_mentions:read`, `im:history`, `im:read`, subscribe to `app_mention`
and `message.im`, install, then set `"slack": { "enabled": true, "notifyChannel": "C0123...", "allowedUserIds": ["U0123..."] }`.
`allowedUserIds` is required: anyone else in the workspace could otherwise type `approve 12`. Messages from other
members are ignored and logged. Find your ID under your Slack profile, "Copy member ID".
DM or @mention the bot. Commands: `status`, `approve 12`, `reject 12 reason`, `reply 12 text`.
Anything else goes to the lead, which can create tasks for you.

## 9. Develop and test

```bash
./build.sh          # builds botdesk.jar with plain javac
./build.sh test     # 48 tests: real git repos, a scripted fake model; no network, no API cost
```

## 9a. Token use: code first, models last

Every model call costs money, so botdesk does in plain code everything that does not need judgement.

| Saver | How | Where |
|---|---|---|
| Commands never call a model | `approve 12`, `status`, `reply 12 ...` are parsed in code | `ChatService` |
| Gates before the reviewer | A change that fails a check or rule never reaches the reviewer | `quality/`, `BuildStage` |
| Green build, no model | `maintenance` runs the checks first; if all pass, no model is called at all | `BuildStage` (`checksFirst`) |
| Failures handed over | When checks already fail, their output goes into the engineer's first prompt | `BuildStage` |
| No second review of identical code | Same commit, same verdict: it goes back without asking the reviewer | `ReviewStage` |
| Old tool results trimmed | Every step re-sends the conversation; results older than the last 6 become a one-line stub | `ToolLoopAgent` |
| Output capped and cleaned | Files, search hits, diffs and check output are clipped; colour codes stripped | `Workspace`, stages |
| Files read on demand | The agent gets a file list, not the whole repo | `Workspace` |
| Budget guard | Checked before each call, per task and per month | `OpenAiCompatibleClient` |
| Cheap roles where possible | Each role picks its own model; use a small one for the lead if plans are simple | `botdesk.json` |

The task page shows tokens in and out, the number of model calls, and the cost.
Static instructions come first in every prompt, so providers that cache prompt prefixes can reuse them.

## 9b. Code layout

```
config/       botdesk.json and the setup check        domain/   Task, 11 states, records
orchestrator/ TaskService (state changes), Orchestrator (picks the next task)
  stage/      PlanStage, BuildStage, ReviewStage, ReleaseStage
agent/        Workspace (sandbox), ToolLoopAgent (the loop), CodingEngine (the interface)
  tool/       Tool, ToolRegistry, BuiltInTools
quality/      Gate, QualityGates, BuiltInGates
llm/          one client for any OpenAI-compatible provider
project/      projects, playbooks, Git, GitHub          store/    Store, JsonFileStore
web/ chat/ slack/   the ways you talk to the team       json/     a small JSON parser
```

To add a provider, playbook, gate, tool, stage or store, see [docs/EXTENDING.md](docs/EXTENDING.md).
Design decisions are recorded in [docs/adr/](docs/adr/).

## 10. Design decisions (and trade-offs)

| Decision | Why | Trade-off |
|---|---|---|
| Own agent loop (`ToolLoopAgent`) behind a `CodingEngine` interface, tools in a registry | Every step is explainable and logged; an external agent (OpenCode, aider) can plug in later | Less capable than mature coding agents on large refactors |
| Gates in code before the reviewer model, one small class per rule | A model can ignore a prompt, not code. Also saves reviewer tokens | Each new rule is a small class |
| One class per pipeline stage | Adding a step does not touch the others; each is tested through the full pipeline | A few more files than one big orchestrator |
| Git worktree per task | Parallel-safe, never touches `main`, easy cleanup | Needs a local clone of each project |
| Named checks only, no shell tool | Small, auditable attack surface | Agents cannot install tools or explore with ad-hoc commands |
| Approval bound to a commit SHA | What you reviewed is exactly what ships | Any later change needs approval again |
| Single orchestrator thread | No races between task steps; a task is changed by one thread at a time (see `Task`) | One step at a time; fine for one person |
| Graceful shutdown | Waits 30 s for an in-flight step, then interrupts it so it fails cleanly into FAILED | A step cut off mid-way must be retried |
| One JSON file as the store | Zero setup; atomic write via temp file + rename | Move to SQLite past a few thousand tasks; only `Store` changes |
| No framework, no libraries | About 100 MB RAM, every line readable | Hand-written JSON and HTTP routing |

## 11. Known limits and next steps

- **Sandboxing is process-level.** Checks run your project's own build and tests, which execute code the agent wrote.
  On a VPS the service runs as a locked-down user. Next step: run each check inside a Docker container with no network.
- **Search has a 3 second budget.** A model can pass any regex to `search`; a pathological one stops with a
  "too slow" message instead of hanging the orchestrator thread. Simple, but it is a time box, not a regex engine
  without backtracking.
- **One engineer per task.** Next: the lead splits large features into ordered sub-tasks.
- **Corrections loop.** Next: turn repeated review feedback into new playbook rules, with your approval.

## Credits

The dashboard uses [htmx](https://htmx.org) 2.0.11 (Zero-Clause BSD licence), bundled as `src/main/resources/static/htmx.min.js`.
