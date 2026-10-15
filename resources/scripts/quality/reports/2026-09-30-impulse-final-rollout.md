# Final Stage-1 impulse-fix rollout

Previous wave 167.233.52.194 extended observation at 14:22:46 UTC:
23 completions individually matched to Redis responses, all ok; each of six
workers completed 3–5 tasks. 22 hourly recordings: mean 314.9935 s, max
402.55 s, none over 600 s. Zero observed errors/OOM/restarts. Initial short
gate remained insufficient data; extended evidence supports expansion.

Updated remaining eight Stage-1 workers on two hosts with the same verified
JAR SHA 83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4.
Image local/ozen-panako:impulse-fix-20260929-83fdabf6.

- 167.233.61.228: four panako-api replicas, healthy 15:12:10 UTC / 20:12:10
  Almaty. HTTP/store container IDs unchanged. Private evidence
  /home/bekzat/impulse-wave5-20260930/; override
  /home/bekzat/project/docker-compose.impulse-wave5-20260930.yml.
- 167.233.105.74: four stage1 replicas in project stage1-main-20260928,
  healthy 15:17:49 UTC / 20:17:49 Almaty. Waited for last task to finish,
  confirmed no pending ownership before/after stopping. Private evidence
  /home/bekzat/impulse-wave6-20260930/; override
  /home/bekzat/stage1-convert-20260928/compose.impulse-wave6-20260930.yml.

Deployment verified image/JAR SHA, effective environment, CPU/memory settings,
restart policy, HTTP health and unchanged unrelated containers. First snapshots
all eight healthy with zero restarts and no alerts. All 26 managed Stage-1
workers now updated. Last eight production performance gates still pending.
Observers run 30 minutes, expected completion around 20:43 and 20:48 Almaty.
No automatic next architecture change configured. Keeper/replica changes
remain separate work after full-fleet validation.
