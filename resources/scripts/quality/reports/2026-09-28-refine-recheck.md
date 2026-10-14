# Isolated refine replay — September 28, 2026

The user approved rechecking recording 01a082a6-75c4-7410-9aec-8d506b4bf872.
Original Redis request 1789099980937-0 contains one segment, 0–3795 seconds,
and filename=null. Its key/value fields were copied intact into a private
artifact; the audio URL is not included in this report.

Production refine responses are consumed by an orchestrator group. Its
persistence/overwrite implementation was not available in the inspected
workspace. To avoid changing the recording's stored results, the replay uses
an isolated Redis server on 127.0.0.1:16389 on 46.224.159.198, with a private
password and no persistence. The temporary worker has Kafka disabled and
uses the corrected 5d50209b image with MAIN for read queries. No task or result
was added to production Redis streams.

Exactly one isolated request was submitted: 1790593067885-0, at approximately
10:57:47 UTC / 15:57:47 Almaty. The temporary worker has two CPU equivalents,
3.5 GiB heap, and a 4.5 GiB memory limit. Timing is diagnostic and must not be
compared directly with production SLA measurements at different CPU limits.

Artifacts: /home/bekzat/refine-recheck-20260928/ on 46.224.159.198.
The detached observer saves the complete private response and a safe summary
to status.json, then stops both temporary containers. Timeout is 30 minutes.
The response does not propagate to the production orchestrator. Initial status
is processing, with isolated Redis route and Kafka disabled verified from
the actual container configuration. No recognition outcome is claimed yet.

The first temporary Redis startup failed because its unprivileged process
could not read the owner-only config. No request had been sent at that point.
It was recreated using the file owner's UID/GID, retaining private permissions;
submission occurred only after authenticated PING succeeded.

## Result

The isolated replay completed at 11:02:44 UTC / 16:02:44 Almaty. Exactly one
response: status=ok, duration 3795.3 s, unique_tracks_count=0. Recognition time
was 273561 ms (4:34); observation elapsed time including download/startup and
polling was 297 s (4:57). No monitored window/monitor/ownership/OOM errors.
Both temporary containers are confirmed stopped.

The zero-track count agrees with the two prior responses. This is consistency
of reported detections, not proof that the recording contains no recognizable
music. Production results remain untouched, and the cause of the earlier
duplicate response/ownership incident remains unresolved.
