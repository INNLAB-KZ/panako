# Relocate Main refine-f — 2026-10-04

Source project-panako-refine-f-1 (distinct from previously moved store-f).
Source ID9e0444303e9e1130404ed604041da3179f5884e294048b2dbd23085bf30eaa3b.
Last-hour source logs:17 completions,2 Java heap OOMs. No mounts.
Destination167.233.105.218:12 CPUs,~17GiB available at preflight,load2.82,
existing relocated G and ClickHouse only.

New service refine-f2-move-20261004-refine-from-main-1 started17:38:42UTC,
health passed17:38:44UTC. Unique hostname refine-main-f2-20261004 with hosts
entry; host network,port8088,4CPU quota,4608MiB RAM,3584MiB heap,
ExitOnOutOfMemoryError,two windows,one Redis worker,Kafka disabled.
Image local/ozen-panako:refine-jdbc-20261004-60a75e63 built from validated refine
runtime base with60a75e63 JAR. This includes the already-tested quantization
impulse and SQL failure/cancellation fixes plus bounded JDBC waits.

Source matching env retained exactly, including MONITOR_GAP_MIN_SECONDS (differs
from the existing destination G template). Only runtime/transport env changed:
JAVA_TOOL_OPTIONS,JAVA_OPTS,KAFKA_ENABLED,CLICKHOUSE_HOST,OLAF_CLICKHOUSE_URL.
ClickHouse URL authority127.0.0.1:8124 replaced with10.0.1.2:8123; source query
parameters/database retained. No new matching thresholds/truncation introduced.

Deployment verified env,JAR SHA,CPU/RAM limits,HTTP health and unrelated container
IDs. Private destination evidence /home/bekzat/refine-f2-move-20261004/;
source disable preparation /home/bekzat/refine-f2-disable-20261004/ on Main.
First task213962ms (3m34s), no processing/OOM/ownership/socket errors.
Source shutdown requires two completed tasks and fresh healthy/error-free check.
Thirty-minute observer actually started on destination. Source remains running
until that two-task startup gate passes; this gate is not a long-term SLA test.

Destination10sec sample with new pilot:CPU31.6%,iowait0%,14GiB available;
private enp7s0 RX140.3Mbps/TX0.4Mbps,public eth0 RX51.5Mbps/TX0.1Mbps,
no new errors/drops on either interface. Short sample only.

## Source disabled

At17:47:29UTC replacement passed two-task startup gate:213962ms and236590ms,
HTTP healthy,zero sampled processing/socket/ownership/OOM errors,RestartCount0.
Source stopped17:48:50UTC. Its existing Main Compose override now declares
panako-refine-f restart=no and deploy.replicas=0. Merged configuration verified
only those changes (plus empty Compose deploy normalization objects); all other
running container IDs/states unchanged. Runtime restart policy no verified.
Private backup/status /home/bekzat/refine-f2-disable-20261004/ on Main.
No manual Redis message changes. In-flight legacy source work can require normal
retry; this does not resolve the previously deferred duplicate-response issue.
Main now has six remaining active refine-role containers (refine-a..e,store-h).
Destination now hosts relocated G and new refine-f2, alongside its ClickHouse.
Thirty-minute observation continues; no further automatic migrations scheduled.

Post-stop verification: source exited/restart=no.10sec Main CPU sample41.7%,
iowait0%; preceding overlapping shutdown sample ens23 TX661.4Mbps,RX2.1Mbps,
no new interface errors/drops,available RAM100GiB. Workload varies; this is not
controlled proof of a specific CPU improvement caused solely by migration.

Important separate backlog, checked around17:49UTC: Stage-1 lag0,pending0;
refine group panako-worker-refine on panako.monitor.refine.request lag293852,
pending29. These are messages, not an audited count of unique recordings.
No Redis mutation or duplicate diagnosis performed.
Read-only last-hour audit of18 other refine workers:157.180.26.0 six workers47
completions;62.238.0.200 six46;167.233.62.118 six77. All had completions,zero
sampled monitor failures/Java OOM/cancellation-wait warnings,RestartCount0.
Large refine backlog remains; cannot declare end-to-end processing caught up.
Further work: quantify refine arrival/completion rates and capacity while
continuing staged relocation of six remaining Main refine containers.
