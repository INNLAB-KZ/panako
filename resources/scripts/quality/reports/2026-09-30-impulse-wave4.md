# Impulse fix wave 4

Previous wave on 167.233.53.95 passed its 30-minute gate at 09:35:44 UTC:
16 completed tasks, 13 hourly; mean processing 313.849 s, max 374.634 s.
Hourly task-service upper bounds mean 331.124 s, max 404.650 s.
No alerts, errors, OOM or restarts; TCP retransmission ratio 0.000002443.
All six still running with zero restarts at 12:23 UTC.

Updated six panako-api replicas on 167.233.52.194. Prepared JAR by rsync delta
against existing baseline; one connection timeout retried before mutation.
SHA verified before build and inside all six running containers:
83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4.
Image local/ozen-panako:impulse-fix-20260929-83fdabf6.

Drain started 12:25:22 UTC; waited for active tasks, paused idle containers,
rechecked empty pending ownership before/after stopping. Recreated exactly six.
Environment, CPU/memory settings, restart policy preserved; all HTTP health
checks passed. Healthy 12:31:48 UTC / 17:31:48 Almaty. First observation all
healthy, zero restarts, no alerts. Fleet now 18/26 updated; remaining four on
167.233.61.228 and four on 167.233.105.74 not changed.

30-minute observation started 12:31:49 UTC, expected completion about 18:02
Almaty. Further expansion held pending real task results.
Private evidence: /home/bekzat/impulse-wave4-20260930/ on 167.233.52.194.
Persistent override: /home/bekzat/project/docker-compose.impulse-wave4-20260930.yml.
Scripts: ../impulse-wave4/.
