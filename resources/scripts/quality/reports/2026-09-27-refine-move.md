# Refine relocation pilot — September 27, 2026

The user authorized moving refine/store computation away from MAIN. Inspection
corrected the earlier name-based inventory: MAIN runs **ten refine workers and
four store workers**. Services named store-e through store-h actually have
PANAKO_WORKER_MODE=refine and KAFKA_MODE=MONITOR. Store-a through store-d use
KAFKA_MODE=STORE. All currently enable both Redis and Kafka consumers.

## First candidate

Source: project-panako-store-e-1 on 116.202.112.101, a refine worker using
MAIN port 8123. Target: 46.224.159.198 (private 10.0.0.22), currently hosting
a ClickHouse replica. It has 12 logical CPUs, approximately 23 GiB RAM,
20 GiB available at preflight, load average about 2.1, and 336 GiB free disk.
Existing dedicated refine hosts were more CPU-loaded; no services are removed
from the target during this pilot.

The target retains the exact f9b74f6 image and source environment except for
replacing loopback ClickHouse addresses with MAIN 10.0.1.2:8123. Heap remains
3 GiB, container memory 4 GiB, and parallel windows two. Redis and Kafka remain
enabled as on the source. This is a relocation pilot, not a worker code upgrade.
Preflight confirms authenticated ClickHouse access, Redis PING, Kafka TCP
reachability, and an available HTTP port 8085. Kafka TCP reachability alone
does not prove consumer group health.

Artifacts and Compose project files on target:
/home/bekzat/refine-move-20260927/. Private env/inspect files must not be
published. The target image is transferred from the existing MAIN image.

The source stays active while the target is validated for 30 minutes and at
least two completed Redis tasks. Review errors, Redis pending state, target
CPU/RAM, and existing replica health before draining the source. No source
stop or fleet-wide relocation is automatically scheduled by this pilot.
MAIN CPU relief is not achieved until the matching source worker is stopped.

## Pilot started — 16:23 Almaty

Target container `refine-move-20260927-refine-from-main-1` passed HTTP health
at 11:23:30 UTC / 16:23:30 Almaty. Docker image ID matches the original source
exactly. The existing target ClickHouse container ID remains unchanged.
The watcher is running; expect the first 30-minute result around 16:54 Almaty.
Source project-panako-store-e-1 remains active on MAIN. This is a temporary
additional consumer pending validation, not a completed migration or CPU relief.

## Pilot observation result

The 30-minute pilot gate passed at 11:53:31 UTC: eight Redis task completions,
no monitored processing errors, restarts, or OOM. This gate checks worker
operation and completions; it does not establish task-result equivalence,
Kafka group health, or the absence of impact on the co-located replica.

At 18:17 UTC the target still ran with zero restarts and OOMKilled=false.
The source on MAIN also remained running. Draining the source is still pending;
the temporary extra consumer has not yet reduced MAIN's compute load.

## Late failure and revised pilot — 23:27 Almaty

Before stopping the source, investigation found its Redis worker waiting for
an ffmpeg process for over 51 hours. Three ffmpeg child processes had existed
for more than two days. It had no completed Redis tasks in the preceding
30 minutes. A SIGQUIT thread dump was used for diagnosis.

The target still completed Redis tasks, but repeated HTTP health probes timed
out. Historical logs showed multiple Java heap OutOfMemoryErrors after the
initial successful observation, including death of Kafka and HTTP-Dispatcher
threads. Docker RestartCount=0 and OOMKilled=false did not establish JVM health.
The original pilot's 30-minute PASS is superseded by this later failure.

The unhealthy target was stopped and recreated with the already deployed
5d50209b JAR (SHA-256 recorded in the input-fix rollout), built on its existing
f9b74f6 base image. Settings: Redis-only consumption, one Redis execution
worker, two windows, heap 3584 MiB, ExitOnOutOfMemoryError, container memory
4608 MiB, and a distinct hostname. The new JAR includes shared fresh/reclaim
admission, heartbeat/owner-checked ACK, hit-memory improvements, and window
failure propagation. Production refine behavior remains subject to this pilot.

Startup passed at 18:27:10 UTC / 23:27:10 Almaty September 27. A two-hour
watcher runs from /home/bekzat/refine-fix-20260927/ on 46.224.159.198, with
expected completion around 01:28 Almaty September 28. This stage consumes
Redis refine tasks; other existing workers continue handling Kafka.

The proposed source Compose edit was rejected by preflight comparisons and
was not applied. The original source remains running with unchanged live
Compose settings. No Redis messages were manually acknowledged or deleted.
Source removal and MAIN resource relief remain pending successful validation.

During startup verification, Java could not resolve the custom hostname and
used its fallback consumer name. An explicit local hosts entry was added and
the pilot recreated. Redis now reports the unique consumer
refine-main-move-20260927-1-1. Initial observation files were archived locally;
the fresh two-hour window starts at 18:28:38 UTC / 23:28:38 Almaty, ending
approximately 01:30 Almaty September 28. No pending messages were manually ACKed.

## Validated replacement and source stopped — September 28

The revised two-hour gate passed at 20:28:52 UTC September 27: 33 completed
Redis refine tasks, no monitored processing errors, OOM, restarts, or health
failures. At 05:46 UTC September 28, the target still returned HTTP 200/ok,
had zero restarts, and completed 16 tasks in the preceding hour with no window,
monitor, heartbeat, ownership-loss, or Java OOM errors. Docker memory was
3.806 GiB of 4.5 GiB; CPU snapshot was about 4.5 logical CPUs.

The previously authorized source removal completed at 05:47:32 UTC /
10:47:32 Almaty September 28. project-panako-store-e-1 on MAIN is stopped,
restart policy no. Its existing Compose override now declares deploy.replicas=0.
The shared YAML anchor was moved to an extension to avoid changing services
that inherit it. Effective Compose configuration was checked to preserve every
other service. All other running container IDs and states remained unchanged.

Backups, proposed configuration, source inspection, and migration.json are at
/home/bekzat/refine-move-20260927/ on MAIN. No Redis messages were manually
acknowledged or deleted. The source was already stuck, so CPU savings should
not be inferred from its former role or historical consumption. MAIN now has
nine active refine containers and four store containers. Further relocations
remain separate stages; stage1 routing remains twelve on MAIN and ten on replicas.
