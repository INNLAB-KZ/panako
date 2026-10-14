# MAIN unreachable incident

Observed around 12:17–12:23 UTC / 17:17–17:23 Almaty, October 1.
No production mutation performed during diagnosis; route expansion held.

Main 116.202.112.101 SSH timed out repeatedly from local workstation.
From 167.233.105.74, private 10.0.1.2 ports22/8123/9181 and public SSH
all timed out. From 167.233.61.250, private ClickHouse ping and replicas_status
also timed out. HAProxy main DOWN, L4TOUT, lastchg11843 seconds (~3h17m).
This establishes network/service unreachability, not physical power state.

Both retained replicas answer HTTP and /replicas_status 200. However both
system.replicas tables show is_readonly=1 and is_session_expired=1. Metadata
absolute_delay approximately11800 seconds. Existing data readable, but current
replication freshness and writes cannot be assumed. The /replicas_status check
alone does not exclude expired Keeper sessions; review failover health policy.

Pilot routed worker:94 completions since rollout, average251.823s,
max438.932s (all durations mixed). Two SQL/monitor failures at08:20:12 and
09:07:09 UTC, failed reading hash column, window timeout/cancellation.
Causality not fully established. Last30min:6 completions,0 monitor failures,
0 restarts. One sampled direct worker:0 completions/0 monitor failures last30m;
not evidence that it is healthy or that all other workers have stopped.
Proxy sessions Main117562, replicaA51314, replicaB51314 at initial snapshot.

User asked asynchronously whether network/firewall/reboot changes occurred.
Next: establish main console/power/network state through Hetzner; restore
access before Keeper migration or broader rerouting. No replica promotion,
new independent Keeper quorum, task requeue, or manual ACK performed.
