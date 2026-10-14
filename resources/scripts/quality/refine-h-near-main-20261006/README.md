# Near-Main H pilot and unused replica retirement

Archived operational scripts. Local execution used
`build/refine-h-near-main-20261006/` with private specs under `private/h/`.
Private files contain credentials and are not included here.

Replica backup:167.233.105.218:/home/bekzat/ch-retire-20261006/.
H2 pilot:167.233.105.218:/home/bekzat/refine-h2-move-20261006/.

`routes.py` audits safe endpoint summaries only. `db-audit.py` runs from Main
using existing source credentials in memory; does not print credentials.
`stop-replica.py` validates identity/config/dependency health, backs up via the
previous preflight, persists replicas=0/restart=no, then stops only candidate.
Never rerun it blindly: it expects the original running container and config SHA.
No ClickHouse data directories or replica registration were removed.

`start.py`, `check.py`, `observe.py` use a private pilot spec. `watch-tasks.py`
imports existing `redis_state.py` and reads pending/refine response streams.
It correlates recording IDs and exact processing times with own completion logs;
this is operational evidence, not a catalogue-wide quality evaluation.

Before using `disable.py h`, independently validate the two-hourly-task
performance gate as described in the report. Its built-in two-task check alone
has no audio-duration requirement.

Rollback order if needed: stop the H2 pilot and persist its disabled state before
restarting the retired ClickHouse replica, since it shares the same host memory.
Restore only the appropriate Compose service changes after checking for later
edits; never overwrite unrelated subsequent changes with an old whole-file backup.
Main source remains available until an accepted pilot retires it.
