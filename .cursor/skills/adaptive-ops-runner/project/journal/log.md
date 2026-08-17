# Failure journal

Raw, append-only history for this repository. Written only by the journalist.
The main model and the worker never read or write it — it grows without limit
and is the largest file in the skill.

Maintenance rules are in `JOURNAL-AGENT.md`, deliberately not here: instructions
kept inside the data file are lost the moment the data file is rewritten.

The next unused code lives in `next-code.txt`, also deliberately separate. A
counter stored inside its own log disappears together with the log, and the
codes already handed out stop resolving.

Entries are never deleted and never rewritten into something else. Curation
happens in the rule files; this is the record of what actually happened.

## Entries

(none yet)
