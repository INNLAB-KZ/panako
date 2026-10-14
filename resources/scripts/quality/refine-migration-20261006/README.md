# Main refine relocation, 2026-10-06

Operational scripts archived from `build/refine-migration-20261006/`.
Private specs, environment/configuration snapshots and images are intentionally
excluded. Scripts use paths relative to their working copy; local orchestrators
were run from the build directory containing the private deployment specs.

- `start.py DEST_ROOT`: start one destination and validate artifact/config/health.
- `check.py DEST_ROOT`: read-only workload/resource gate; saves gate JSON.
- `observe.py DEST_ROOT`: 30-minute read-only health/resource observer.
- `disable.py KEY`: local source disable orchestrator; requires fresh passed gate.
- `disable-remote.py BACKUP_ROOT`: Main-side Compose and source-stop transaction.
- `finish.py`: sequentially disable c/d/e/h when their destination gates pass;
  stops on health failure or timeout. Run only once during this operation.
- `snapshot.py`: collect observer summaries from all six destinations.
- `queue-check.py`: bounded read-only queue and Stage-1 response statistics;
  imports existing deployed `redis_state` helper on Stage-1 host 167.233.61.250.
- `host-check.py`: 10-second CPU/NIC sample plus Docker usage snapshot.

These scripts require existing authenticated SSH/Docker access. They do not
provision hosts or perform a general-purpose redeployment. Source disable is
not an idempotent stop command: it expects the exact original running container.
Backup restore must consider later edits to the shared Main Compose override;
do not overwrite that override wholesale with an earlier backup.

Source stop allows 30 seconds for shutdown. Interrupted work remains subject to
normal reclaim/retry; no manual queue operations are included.

Performance hold: `finish.py` was stopped after five accepted source retirements.
Do not resume it blindly: H exceeded the full-hour target and its Main source
must remain running. `hold-h.py` drains/stops only the additional H pilot and
persists replicas=0/restart=no for that pilot. It imports deployed `redis_state`.
The script's fallback after 10 minutes is Docker stop with 30-second grace;
any pending task then relies on normal application reclaim.
