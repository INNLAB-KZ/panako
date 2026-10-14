# Impulse fix: third deployment wave

## Previous group overnight evidence

167.233.61.250, six workers, since 2026-09-29 14:43:12 UTC:
504 log completions matched individually to Redis responses by processing time
and publication timestamp within two seconds. All status ok. 492 recordings
3300–3900 seconds: mean 267.251 seconds, max 491.867 seconds; three exceeded
480 seconds, none exceeded 600. Zero observed OOM, processing errors, ownership
loss or container restarts. Snapshot 2026-09-30 06:28:28 UTC. Initial 30-minute
gate remains insufficient-data; overnight evidence is separate.

## Main pre-deployment snapshot

ClickHouse CPU 709.38% (~7 logical cores), RAM 16.11 GiB / 60 GiB container;
clickhouse2 6.05%, 1006 MiB. Host available RAM 94821 MiB, load average
27.42/28.80/32.16, swap occupied 1888 MiB (not a swap activity measurement).
10-second ens23 measurement: RX 2.624 Mbps, TX 814.880 Mbps; zero added
RX/TX errors or drops. Short snapshot does not establish peak-load headroom.

## Deployment

Updated six panako-api service replicas on 167.233.53.95. Image
local/ozen-panako:impulse-fix-20260929-83fdabf6, JAR SHA256
83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4.
SSH upload timed out near completion, resumed with rsync; SHA matched before
image build. No mutation before image validation. Paused idle containers and
rechecked pending ownership before and after stopping. Preserved six replicas,
effective environment and CPU/memory limits; verified HTTP health and running
JAR SHA for every replacement. No unrelated containers changed.

Healthy at 2026-09-30 09:05:29 UTC / 14:05:29 Almaty. All six initially healthy,
zero restarts/OOM/errors; no completed tasks in first sample. 30-minute observer
started 09:05:29 UTC, expected finish around 14:35:30 Almaty. Further rollout
held pending real task evidence. Fleet now 12/26 updated.

Private server artifacts: /home/bekzat/impulse-wave3-20260930/.
Persistent override: /home/bekzat/project/docker-compose.impulse-wave3-20260930.yml.
Scripts archived in ../impulse-wave3/.
