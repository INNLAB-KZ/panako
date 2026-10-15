# Stage1 rollout: 167.233.61.250

## Scope and gate

The corrected pilot completed its 30-minute observation with five successful
recordings, mean processing time 351.798 seconds, no observed errors or restarts,
and maximum sampled Docker memory 3.980 GiB out of 4.5 GiB.
See [pilot results](2026-09-25-pilot-30m.md).

The remaining five replicas of Compose service `panako-api` were updated on this
host. The original `panako-api-memory` pilot was not recreated. No other host was
updated. This is a host batch after draining each replica, not a rolling update
that keeps all five old replicas serving throughout.

## Deployment

Drain began September 24 at 20:03:10 UTC. Each old replica's captured pending task
was ACKed before that replica was paused and stopped. Five newly admitted tasks
remained pending at the stop boundaries. Owner-checked XCLAIM made those stopped
consumers' messages eligible for retry; no manual ACK or new request publication
was performed.

All five replacements passed HTTP health checks at **20:08:38 UTC / 01:08:38
Asia/Almaty, September 25**. Audit confirmed:

- Six running containers, all on the tested candidate image.
- JAR SHA-256 `d556675b5076dbe09e49eeeb5da4a403725237d41021e4346322dad1f6bf66fd`.
- Two windows, one Redis worker per container.
- `JDK_JAVA_OPTIONS=-Xmx3584m -XX:+ExitOnOutOfMemoryError`.
- Container memory limit 4,831,838,208 bytes (4.5 GiB).
- Kafka disabled; Redis enabled; Docker restart policy `unless-stopped`.
- Original pilot container ID unchanged.
- All captured original tasks no longer pending.
- All five handoff messages still present in Redis and reclaimed, delivery count
  2. Three were owned by new local workers; two by another existing consumer.
- Initial checks showed zero restarts, OOMs, or worker errors, with one pending
  task per local container. These startup checks do not establish sustained
  performance or completion of the retried tasks.

## Observation and operations

Thirty-minute observation started at 20:08:38 UTC and covers all six containers.
Checkpoint: **20:38:38 UTC / 01:38:38 Asia/Almaty, September 25**.
The watcher records health, memory, CPU, restarts, pending tasks, errors, and
responses. It records alerts; it does not automatically roll back this batch.

Protected operational files are on the host at
`/home/bekzat/host-rollout-20260925/`: `deployment.json`, `audit.json`,
`watch.json`, `samples.jsonl`, scripts, and configuration backup.

The active Compose override is
`/home/bekzat/project/docker-compose.redis-pilot-20260924.yml`; it now covers
both services. Subsequent Compose operations must include this third override
and preserve `--scale panako-api=5`.

`python3 /home/bekzat/host-rollout-20260925/rollback.py` restores the five-worker
service to its prior image and retains the pilot configuration. It checks that
the current container IDs still match this deployment before changing anything.
Rollback can interrupt active recordings; Redis pending messages remain for retry.
The older pilot flag-only rollback predates this host rollout and must not be
used to overwrite the current override.
