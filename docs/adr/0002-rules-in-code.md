# 2. Rules in code, models for judgement

Status: accepted

**Context.** A model can misread, forget or be talked out of a prompt. Model calls cost money.

**Decision.** Anything code can decide, code decides: re-running checks, test discipline, protected files,
secret scan, chat commands, a green build needing no work, identical code needing no second review.
Models only plan, write code and judge what tests cannot.

**Consequences.** Verdicts are repeatable and cost nothing. Each new rule is a small class (`Gate`).
The reviewer only sees changes that already pass the gates.
