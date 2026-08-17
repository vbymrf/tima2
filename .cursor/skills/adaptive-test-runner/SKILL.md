---
name: adaptive-test-runner
description: Runs, verifies and diagnoses tests and build gates for the application in this repository. Delegates long or noisy runs to a cheap execution worker, verifies every result against an authoritative artifact, and accumulates failure knowledge so that later runs need shorter instructions. Use automatically whenever tests, builds, verification, test counts, or evidence of correctness are requested.
---

# Adaptive test runner

You are the main model. You decide what must be proven, which tier proves it,
and whether the evidence is real. Execution may be delegated to a cheap worker;
judgement never is.

The skill is built around one measurable claim: **the instructions you must
send shrink from run to run.** If they do not, the skill is failing and you
should say so rather than keep feeding it.

## Roles

| Role | Model | Owns |
|---|---|---|
| Main (you) | the model leading the task | tier choice, evidence, the profile, freezing decisions |
| Worker | `composer-2.5-fast` | execution, rule application, bounded self-repair |
| Journalist | `claude-4.6-sonnet-medium-thinking` | error classification, counters, promotion into rules |

The journalist is deliberately stronger than the worker. Deciding whether an
error is a known type or a new one is the hardest operation here, and a weak
model doing it badly fills the rule base with duplicates until it is useless.

## What you read

Read `project/PROFILE.md`. That is the tier registry and your only routine
input.

Do not read `WORKER.md`, `JOURNAL-AGENT.md`, `INDEX.md`, `project/rules.md`,
`knowledge/`, or `project/journal/log.md`. The worker and the journalist read
those themselves; pulling them into your context is the cost this skill exists
to avoid. The single exception is a rule the journalist explicitly reports as
`preventive` — see *Absorption* below.

## First run in a repository

If `project/PROFILE.md` has no tier records, build it before anything else.
This is yours, not the worker's: a weak model cannot infer how an unfamiliar
application is meant to be tested.

Inspect the repository and write one record per tier. A tier is the smallest
unit that proves something on its own — a package group, a module's unit
tests, a compile gate, a packaging step. For each one establish:

- what claim it proves, in one line
- which stacks it uses (`go`, `gradle`, `node`, `docker`, …) — this drives
  which rule files the worker consults
- the exact run path
- the authoritative artifact it produces: JUnit XML, a JSON report, per-package
  output, a built file on disk. A tier with no artifact cannot be verified and
  must be marked as such
- prerequisites and prohibitions: services that must be up, state that must not
  be destroyed, databases that must never be touched

Prefer few broad tiers to many narrow ones. Tiers that are never selected are
maintenance debt.

Re-profile when a run fails with *target not found*, *no such package*, or a
missing script. That is not an error to journal — it means the profile is
stale. Fix the record and rerun. A profile maintained by hand instead of
self-healing defeats the whole design.

## Every run

1. Pick the smallest tier that proves the requested claim. Do not widen into
   packaging or distribution unless asked or unless the changed surface
   requires it.
2. Check current processes before any tier needing containers, an emulator, or
   a running server. Never start a duplicate stack.
3. Decide whether to delegate (below).
4. Send the request, or execute it yourself.
5. Verify against the artifact.
6. Update `runs_unfrozen` and consider freezing.
7. Dispatch the journalist if anything failed.

## When to delegate, and when not to

Delegate uncertainty, not execution. A worker earns its cost when the outcome
is not yet known: a tier whose sequence is still being established, a run whose
log somebody has to read, a failure that needs diagnosing. It earns nothing for
pressing a button you could press yourself.

| Tier state | Who runs it |
|---|---|
| Unfrozen — sequence still being established | worker |
| Setup frozen, measured part still varying | worker |
| Frozen end to end, quiet, expected to pass | you, directly |
| Frozen but failing | worker, to diagnose |
| Short and quiet, in any state | you, directly |

The second row is the common one and the easy one to get wrong. Freezing the
setup does not make a tier a button: preparation stabilises early, while which
cases are measured keeps changing. A worker is still earning its cost there.

A tier frozen end to end is one call, and its script prints a summary instead
of a log (see *Freezing*), so there is nothing left for a worker to absorb.
Handing "run this script" to a subagent costs a request, a cold start, and a
summary you must check against the artifact anyway.

**Delegation therefore decays along with the instructions.** That is intended,
not a loss. A tier that still needs a worker after many runs is a tier that
never converged — treat it as a signal, not as normal operation.

Start a long independent worker in the background and continue unrelated work.
Await it before any dependent step and before reporting final verification.

Run workers concurrently only when they share no build outputs, ports,
container state, or device state. Composite or multi-project builds share more
than they appear to; the profile records this under the tier's prerequisites.

## Request format

Send exactly this, plus the absolute path to `WORKER.md` with the instruction
to read it first:

```text
tier:  <tier name from PROFILE.md>
prove: <the claim this run must establish>
state: <what is already running or already known>
```

**Send no commands.** The worker resolves the run path from the tier record
itself. This is what makes forgetting a frozen script structurally impossible:
there is nowhere else for it to look. If you find yourself wanting to paste
commands, the profile is wrong — fix the profile instead.

Do not restate standing policy, the rule-consultation procedure, or the result
contract. They live in `WORKER.md`. Never send the journal or the rule files.

## Verification

The worker's summary is not evidence. Open the artifact named in the tier
record and recompute the totals yourself. Correct any disagreement in your
report rather than rerunning a deterministic test to obtain a nicer summary.

Where a fresh run was required, compare artifact timestamps against this run
and reject stale or cached results.

A skipped test is not a pass when that gate was required. Count skips
explicitly; a zero exit code with zero assertions executed is a failure of
verification, not a success.

Where no automated tier exists and the check was performed by hand, say so
plainly, name the commit it was performed against, and never present it in the
shape of a harness verdict.

## Freezing a tier into a script

Each unfrozen tier record carries `runs_unfrozen`. Increment it after every
successful run of that tier. At two or more, decide whether to freeze — the
decision is yours, and the counter exists only so the question is not
forgotten.

Freeze by writing `project/scripts/tier-<name>.ps1` (or `.sh`) containing the
exact sequence the worker reported under `evidence`, with this header:

```powershell
# tier:     <name>
# proves:   <the claim>
# artifact: <path>
# frozen:   <date>
# absorbed: <rule codes whose content is now inlined here, or none>
```

Then replace `run:` in the tier record with the script path, set `frozen:` and
clear `runs_unfrozen`. The script name is derived mechanically from the tier
name so that two scripts for one tier cannot exist.

Do not freeze a sequence that has not run identically twice, and do not freeze
one that succeeded only after a repair the worker had to improvise.

A frozen script must be **quiet**: verbose output goes to a log file, and what
it prints is a summary line and the artifact path. This is what lets you run it
yourself instead of paying a worker to absorb the noise. A script that dumps a
full build log has not finished being frozen.

### What freezing costs

Freezing trades adaptability for repeatability, and the price is a blind spot.
A script that has quietly become wrong — a target that no longer matches, a
variable no longer honoured, a suite that now skips — keeps producing the same
answer, and every layer above it rubber-stamps that answer. Nothing in a
mechanical rerun can notice.

So a freeze is a hypothesis: *this sequence is stable*. Two identical failures
refute it.

### Unfreezing

Return `run:` to a command list and reset `runs_unfrozen` when any of these
happens:

- the frozen script fails the same way twice — do not rerun it a third time,
  and do not patch it blind; the sequence itself is now in question
- the tier's inputs changed: a module added or moved, a build file edited, a
  test directory renamed. You usually know this from the task you are already
  doing, which is why the check belongs to you and not to the worker
- the script succeeds but the artifact contradicts it — empty, stale, or
  containing fewer cases than before

Unfreezing is cheap and reversible; a wrong frozen script is neither. When in
doubt, unfreeze and let the next two runs re-establish the sequence.

Never let a script that no longer matches its tier stay in place. It runs, it
succeeds, and it proves something other than what was claimed — which is worse
than having no script at all.

### Setup and the measured run

A tier has two parts, and they are yours to different degrees.

`setup:` prepares the environment. It stabilises early, its failures are loud,
and the worker is allowed to repair it in place when the project has moved
under it — a renamed directory, a relocated module, a changed port.

`run:` is the measured part, and **you write it, always** — while it is a
command list, and again when it becomes a script. The worker never authors it
and never edits it. This is the safety boundary of the whole design: a weak
model editing what is being measured, under budget pressure and wanting green,
is how a suite comes to pass while testing nothing.

Freeze the two separately. Setup usually freezes first and stays frozen for a
long time; the measured part may never freeze at all if the interesting cases
keep moving, and that is a legitimate steady state rather than a failure to
converge.

### When the worker reports a patch

A `patch:` in the result contract means the worker repaired the setup script.
The run after a repair is a candidate, not a verdict. Read the before and after
lines and do one of two things:

- Accept: the repair matches a real change in the project. Keep it, and update
  the tier record if anything else in it referred to the old shape.
- Revert: it worked around a problem rather than tracking a change — the
  environment was broken, not moved. Restore the script and treat the run as
  `BLOCKED`.

Either way the patch was journalled, so a repeated repair in the same place
becomes a rule rather than a habit. Watch for that specifically: the same path
repaired twice is not bad luck, it is a signal that the tier addresses
something unstable and should address it differently.

## Absorption

When the journalist reports a promoted rule as `preventive`, read that one
entry and consider inlining it into the tier's script — an environment variable
that must be set, a service that must be up, a flag that forces a fresh run.
A preventive rule that lives in a script cannot be forgotten, whereas one that
lives in a rule file must be read and obeyed every time.

After inlining, add its code to the script's `absorbed:` header and tell the
journalist to mark it absorbed. The rule is not deleted; it moves into
executable form and its history stays in the journal.

Curative rules — those that only matter once something has broken — stay in the
rule files. Do not inline them.

## Journal dispatch

Journal maintenance is service work and must not consume your context. Never
read or edit `project/journal/log.md`, `project/rules.md`, `knowledge/`, or
`INDEX.md` yourself.

After the worker returns, launch the journalist in the background when any of
these hold:

- `status` is FAIL or BLOCKED
- a failure appears under `failures`, even though the run ended PASS
- the run succeeded after a failure seen earlier in this task
- `patch` is not `none` — a setup repair is journalled even when the run passed
  and even when you accepted the repair

Launch it with `model="claude-4.6-sonnet-medium-thinking"` and
`subagent_type="generalPurpose"`. Send only: the absolute path to this skill
folder, the worker's result contract verbatim, the event type, today's date,
the `journal_code` when this is a resolution, and an importance flag when you
judge the failure severe enough to promote on first sight.

Event types are `occurrence`, `resolution`, and `occurrence-and-resolution`.
Keep the returned code in the task context so a later fix can be linked to it.

Never run two journalists against this skill at once; queue the second. The
worker never writes the journal and never promotes a rule.

## Convergence check

Every few runs, compare what you are sending now with what you sent at the
start. Commands should have disappeared into scripts; prerequisites should have
disappeared into either scripts or the profile. What remains should be a tier
name and a claim.

If instructions are not shrinking, the cause is almost always one of three:
the profile is incomplete, so you compensate by hand; tiers are too narrow, so
each run needs explaining; or preventive rules are accumulating in the rule
files instead of being absorbed. Say which one it is and fix that, rather than
writing longer requests.

## Invariants

- Judgement is never delegated. Tier choice, evidence, and the freezing
  decision stay with you.
- Never log or summarize access or refresh tokens, one-time codes, QR payloads,
  private keys, credentials, platform key material, or presigned URLs.
- Reject a result produced in violation of the tier's stated prohibitions —
  evidence from wiped volumes, reset device state, or a temporary toolchain is
  not evidence.
- Environment repair beyond the worker's bounded budget is a separate concern.
  Report it as a required user action; do not let the worker improvise it.
