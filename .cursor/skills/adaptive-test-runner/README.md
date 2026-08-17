# adaptive-test-runner

A Cursor skill that runs and verifies tests for whatever application it is
installed into, and gets cheaper to use over time.

It carries no knowledge of any particular project. What it knows about a
repository, it learns on first use and records in `project/`.

## Install

Copy the folder into the target repository:

```text
<repo>/.cursor/skills/adaptive-test-runner/
```

Nothing else is needed. On its first invocation the main model finds
`project/PROFILE.md` empty and builds the tier registry by inspecting the
repository.

## Port to another project

Copy the folder, then delete `project/`. Recreate it with the four empty files
(`PROFILE.md`, `rules.md`, `journal/log.md`, `journal/next-code.txt` containing
`TEST-001`) and remove from `INDEX.md` every row pointing into `project/`.

What travels: the protocol, and everything under `knowledge/` — rules about
verification itself and about tools. A repository using the same toolchain
starts with that experience rather than relearning it.

What stays: the tier registry, the frozen scripts, the journal, and every rule
naming a path, a package or a port. Those are true here and false elsewhere.

## Layout

```text
SKILL.md            protocol for the main model
WORKER.md           standing policy for the execution worker
JOURNAL-AGENT.md    rules for the journalist
INDEX.md            one row per rule — the only file read before executing

knowledge/          PORTABLE
  universal.md      how verification goes wrong, tool-independent
  stack/            one file per tool, created on demand

project/            NOT PORTABLE
  PROFILE.md        tier registry: what to run and what proves it
  rules.md          rules naming this repository
  scripts/          frozen tiers
  journal/log.md    append-only history
  journal/next-code.txt
```

## How it works

Three roles. The main model chooses what to prove and judges the evidence. A
cheap worker executes. A mid-sized journalist classifies failures and turns
repeated ones into rules.

The design target is convergence: **the instructions the main model must send
shrink from run to run and approach nothing.**

That happens because three things accumulate. The profile records how each tier
is run, so commands stop being dictated. A tier that has run identically twice
is frozen into a script, so a sequence stops being described. Repeated failures
become rules, so the same obstacle is not re-diagnosed.

The request settles at three lines — tier, claim, known state — and carries no
commands at all. The worker resolves the run path from the registry, which is
also why a frozen script cannot be quietly bypassed: there is nowhere else for
it to look.

Two consequences worth stating, because they contradict the obvious design:

**Commands converge; failures do not.** There is a finite number of ways to run
this application's tests and an open-ended number of ways the environment can
break. So the lasting value is the knowledge base, and the execution machinery
is scaffolding meant to shrink out of the way.

**Delegation is for uncertainty, not for execution — and it decays.** A worker
earns its cost while a tier's sequence is still being established, while its
output is a log somebody must read, or when something has broken and needs
diagnosing. Once a tier is frozen end to end its script is quiet and running it
is one call, so the main model runs it directly.

**A tier has two halves and they are trusted differently.** Setup prepares the
environment; it stabilises early, its failures are loud, and the worker may
repair it in place when the project moves under it — reporting the change,
which is then journalled even if the run passed. The measured run is written by
the main model and by nobody else, at every stage. That boundary is legible
from the file name: `setup-<tier>.ps1` is repairable, `tier-<tier>.ps1` is not.
A weak model editing what is measured, under budget pressure and wanting green,
is how a suite comes to pass while testing nothing.

## Two mistakes it is built to avoid

**A rule base that chokes the worker.** Rules are indexed, not preloaded.
Preventive ones — the few whose absence produces a wrong result rather than a
visible failure — are read before running. Curative ones are opened only when
their symptom appears. So the rule count can grow without bound, and nothing is
ever deleted to keep a file short.

**A frozen script nobody remembers.** The script is not an option the worker
may take; it is the tier's only run path. Its name is derived from the tier
name, its header states what it proves, an unfrozen tier carries a counter that
raises the question at the right moment, and a changed tier must be re-frozen
or unfrozen rather than left to drift.

**A frozen script nobody questions.** Freezing buys repeatability by giving up
adaptability, and the price is a blind spot: a script that has quietly become
wrong keeps producing the same answer, and a mechanical rerun cannot notice.
So a freeze is treated as a refutable hypothesis. Two identical failures unfreeze
it rather than earning a third attempt; every run checks the script's product —
artifact present, fresh, non-empty — rather than its text; and a change to the
tier's inputs unfreezes it without waiting for a failure.
