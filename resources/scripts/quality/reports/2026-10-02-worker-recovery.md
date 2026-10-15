# Recovery of workers stuck after MAIN outage

At 2026-10-01 20:15 UTC / Oct2 01:15 Almaty, MAIN had been up3h08m;
ClickHouse and HAProxy healthy, but Redis lag2252 (previously592).
Pilot routed worker completed38 tasks after boot, no errors; all25 direct
workers completed0. Each other host showed hundreds of
'Waiting for cancelled windows to exit' warnings after MAIN boot.

SIGQUIT thread dump from project-panako-api-1 on167.233.61.250 showed
pool-175-thread-2 blocked in native socket read through Apache ChunkedInputStream,
ClickHouseLZ4InputStream, ResultSetImpl.next and processQueryQueue line285.
Window thread age42579s; Redis heartbeat remained alive. Cancellation did not
interrupt blocking read, preventing task release and reclaim.

Restarted one stuck container at20:17:12 UTC, verified HTTP health and new
Redis assignment. Then restarted only24 additional impulse-fix containers
with pre-boot StartedAt, no completions since boot and >=100 cancellation-wait
warnings. All25 restarted workers passed health checks. Pilot untouched.
No code/environment changes, no manual Redis ACK/requeue/claim. Existing old
pending entries retained; new assignments may temporarily coexist in PEL for
the same hostname consumer. Pending count is not execution concurrency.
Old heartbeat ceased; reclaim must be verified after configured idle threshold.
Private before-inspect evidence /home/bekzat/recovery-20261002/ on hosts.

Root fix still required: bounded JDBC reads/cancellation and stalled-task
recovery. Successful task completion and backlog drain after restarts remain
pending; health/new assignment alone do not establish full recovery.

Follow-up 2026-10-02 around03:50 UTC: all26 fixed workers had3–5 successful
monitor completions in the preceding30min, no `Redis monitor failed` or
`Waiting for cancelled windows` in sampled logs, Docker RestartCount0.
Per-task max751s (12m31s); this mixed-duration sample does not establish the
hourly-recording SLA. Redis lag1189/pending36 at03:47approx, down from
post-recovery2227/51; not all old pending entries have been individually audited.

Main sample around03:52 UTC: load87.22/86.07/82.93 (48logical CPUs),
10sec aggregate CPU busy98.4%, iowait0.0%; available RAM88GiB/125GiB.
ens23 TX2532.1Mbps/RX14.3Mbps; VLAN ens23.4000 TX2417.1Mbps/RX12.4Mbps
(subset of physical interface, do not sum). No new interface errors/drops in
sample. This supports investigating CPU contention before adding Main load;
a short sample does not establish the cause of every slow recording.
