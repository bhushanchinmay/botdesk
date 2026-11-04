# 3. One JSON file behind a Store interface

Status: accepted, revisit at multi-user

**Context.** One person, a few thousand tasks at most, and the wish for zero setup.

**Decision.** `JsonFileStore`: in memory, saved after every change via a temp file and an atomic rename.
Callers depend only on the `Store` interface. One orchestrator thread runs all task steps.

**Consequences.** Crash-safe and simple. Each change rewrites the whole file, and only one process may own it.
For several users or workers: a Postgres `Store` that hands out tasks with `SELECT ... FOR UPDATE SKIP LOCKED`.
