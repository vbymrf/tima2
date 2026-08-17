# Frozen tiers

Two kinds of script, and the name says which:

| Name | Role | Who may edit |
|---|---|---|
| `setup-<tier>.<ps1\|sh>` | prepares the environment | main model, and the worker may repair it after a failure |
| `tier-<tier>.<ps1\|sh>` | the measured run | **main model only, always** |

The permission is legible from the file name on purpose. A worker deciding
"is this change addressing or semantics" would get it wrong under pressure; a
worker checking which file it holds cannot.

Names are derived mechanically from the tier name, so two scripts for one role
of one tier cannot exist.

Setup is repaired in place rather than through a candidate copy, because a
setup script has side effects — it starts services and builds fixtures — and
running a copy lands those side effects just the same. A copy would buy
ceremony, not safety. What buys safety is that every repair is reported under
`patch:` and journalled even when the run passed.

Each carries this header, so that a script found on its own still explains
itself:

```powershell
# tier:     <name>
# proves:   <the claim>
# artifact: <path>
# frozen:   <date>
# absorbed: <rule codes now inlined here, or none>
```

`absorbed` is the trail from a rule to the line that replaced it. Without it,
a prerequisite inlined here looks like an arbitrary line somebody may delete.

## Quiet

A script sends verbose output to a log file and prints a summary line and the
artifact path. This is not cosmetic: a quiet script can be run by the main
model directly, and a noisy one needs a worker to absorb it. Noise here costs a
subagent on every future run.

## Falsifiable

Freezing is a hypothesis that the sequence is stable, and it has a cost: a
script that has quietly gone wrong keeps producing the same answer, and a
mechanical rerun cannot notice. So the hypothesis must be refutable, and the
refutation must be cheap.

- Two identical failures of a frozen script refute it. It is unfrozen, not
  retried a third time and not patched blind.
- The worker checks the script's product on every run — artifact present, fresh,
  non-empty — and returns `BLOCKED` with an unfreeze request if any of that
  fails. It never audits the script's text; that would cost more than the
  script saves.
- When the tier's inputs change — a module moved, a build file edited, a
  directory renamed — the main model unfreezes without waiting for a failure.

Unfreezing is cheap and reversible. A wrong frozen script is neither: it runs,
it succeeds, and it proves something other than what was claimed.
