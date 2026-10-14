# Stage-1 root cause: isolated quantization impulses in silence

Offline audit, network disabled, exact production baseline JAR, all 119 windows
of the recurring OOM recording. Input duration 3598.743 seconds. Every extracted
WAV is the expected 45 seconds / 1,440,078 bytes (including WAV header).

Window offset 1740 seconds (29:00–29:45) creates 6,454,009 fingerprints. All other
118 windows have ordinary counts; observed maximum among them 2,439. The failure
begins during fingerprint generation, before any database access.

Spectral audit: four analysis frames (2921, 2945, 2969, 2993 within that window)
each contribute 251 event points across bins 10–510, magnitude approximately
6.891328e-9. Times: 23.368, 23.560, 23.752, 23.944 seconds inside the window.
PCM inspection confirms second 23 contains 15,996 zeros and exactly four +1
samples. Seconds 22, 24 and 25 are entirely zero. Each impulse is isolated in a
3072-sample surrounding interval. These almost flat, extremely weak spectra
produce many equal local maxima and combinatorial fingerprint triples.

## Narrow fix

Before FFT, suppress a frame ONLY when it has exactly one nonzero sample and
its absolute value is no greater than one signed PCM16 quantization step.
Frames with two nonzero samples, stronger isolated transients, NaN or infinity
remain untouched. Processing/history/frame indexing still advances normally.
The cloned analysis buffer is modified, never the decoder's reusable buffer.

Threshold 1/32767 matches the actual deployed TarsosDSP decoder constant
3.051851e-5, verified from its bytecode. No general loudness threshold, peak-count
cap, hash truncation or changed matching thresholds. This deliberately removes
fingerprints of isolated one-LSB clicks; it is not a claim that every input is
bit-identical or that every possible OOM input is now handled.

Original query order/storage restored; cancellation checks and SQL failure
propagation retained. Compact/sorted experiments archived, excluded from this
candidate. Existing unrelated store error changes remain in local source but
are excluded from this artifact, as before.

23 targeted tests passed: 4 quantization-impulse, 6 JDBC/failure/cancellation,
1 hit conversion, 6 window failure, 6 dispatcher. They include positive/negative
one-LSB impulses, stronger transient and multi-sample preservation, silence,
nonfinite samples and processor-level prevention of false peaks/triples.

Candidate SHA-256:
83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4.
Built from baseline 5d50209b, replacing only OlafEventPointProcessor.class and
OlafStorageClickHouse.class. Every other JAR entry is byte-identical.

## Validation pipeline (pending)

Private root: /home/bekzat/impulse-fix-20260929 on 167.233.105.74.
1. Extract all 119 windows with baseline and candidate, no network. Compare
   ordered SHA-256 signatures including hash, times, frequency bins and magnitude
   bits. Require exact equality for the other 118 windows. For the affected
   window, require candidate equality to baseline after excluding fingerprints
   that use the four identified noise frames. This diagnostic exclusion is only
   in the comparison tool; production logic does not hardcode offsets/times.
2. Only after that passes, run complete offending recording recognition with
   2 windows, 3584 MiB heap, 4.5 GiB container and 2 CPUs, reading MAIN directly.
3. Only after complete success, run fresh baseline and candidate recognition on
   the positive 54-minute recording and compare canonical raw results/full JSON.

No production deployment, Redis mutation, or Keeper migration performed.

## Fingerprint gate passed

All 119 windows compared. Exact ordered full-field digest match for 118 normal
windows. Only offset 1740 changed: 6,454,009 fingerprints became 1,401. Candidate
digest/count exactly equals baseline after removing triples involving the four
identified noise frames. No unexpected differences. Evidence saved in
../impulse-fix/fingerprint-comparison.json.

Complete offending-recording recognition started 11:48:24 UTC / 16:48:24 Almaty.
Recognition outcome and positive-recording full-result parity still pending.

## Full recognition and positive parity passed

Problem recording (3598.743 seconds): 261.277 seconds / 4:21 recognition time,
14 raw matches, one unique track, status ok. Sampled peak heap 2,865,606,656
bytes (~2.67 GiB). Exit 0, no Java OOM or Docker OOMKilled. Runner finished
11:52:55 UTC. Probe deliberately writes processing_time_ms=0 in normalized
response JSON; runtime comes from candidate.metrics.json, not that field.

Positive 54-minute control recording, fresh current-DB comparison:
- Baseline: 544.476 seconds (9:04), 94 raw detections.
- Candidate: 536.635 seconds (8:57), 94 raw detections.
- Canonical raw detections exactly equal; normalized full response JSON equal.
- Comparison passed at 12:11:09 UTC / 17:11:09 Almaty.

Same 2 CPU / 2 windows / 3584 MiB heap / 4.5 GiB container limits. Sequential
live-DB runs do not establish a general speedup. Positive parity is evidence
for this recording, not the entire catalog. No production deployment yet.


## One production worker deployed

2026-09-29 13:30:23 UTC / 18:30:23 Almaty: deployed image
`local/ozen-panako:impulse-fix-20260929-83fdabf6` on 167.233.61.250,
service `panako-api-memory`, container `project-panako-api-memory-1`.
New container ID begins `07a9011f1add`. Running JAR SHA-256 verified:
`83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4`.

Waited for the previous task to finish; paused and rechecked Redis ownership,
then confirmed no pending task after stopping the old container. Compose diff
changed only the image; runtime environment and memory limit unchanged.
Unrelated container IDs unchanged. HTTP health passed. Previous container had
2 accumulated restarts; new container has 0.

30-minute observation started 13:30:25 UTC / 18:30:25 Almaty, expected finish
about 19:00:25 Almaty. First 65 seconds: healthy, zero errors/restarts/OOM,
no completed tasks yet. Production performance gate remains pending.
Observer holds expansion on failure; no automatic fleet rollout or automatic
rollback to the known-vulnerable previous image. Server-side private evidence:
`/home/bekzat/impulse-canary-20260929/` (deployment.json, watch.json,
samples.jsonl, network.jsonl, eventual gate-result.json).


## Production canary results and second wave

The initial 30-minute canary gate ended without tasks (insufficient data).
Later two production responses were correlated with container completion logs:
- recording 01a0ed7f-b04c-7e57-b1ea-d6be6b77943e: duration 3671.7 s,
  processing 256688 ms, status ok, 0 unique tracks, completed 14:21:44 UTC.
- recording 01a0ed7f-d7e1-7723-a88b-013690471f68: duration 3703 s,
  processing 405472 ms, status ok, 12 unique tracks, completed 14:28:59 UTC.
Mean processing 331.080 seconds (5:31), zero restarts/OOM observed. These
post-observation results do not retrospectively pass the original formal gate.

User authorized continuation. Updated the other five replicas of Compose
service panako-api on 167.233.61.250; all six workers on this host now use
83fdabf6 image (6 of 26 managed Stage-1 workers overall).

Preflight initially aborted before mutation because image-provided Java/PATH
variables were absent from Compose. Fixed comparison to merge image defaults
with Compose environment; effective environment equality then passed.
Paused each idle worker, rechecked pending tasks before and after stopping;
recreated exactly five service replicas. Verified image SHA, effective env,
Memory/MemorySwap/NanoCpus/CpuQuota/CpuPeriod/CpusetCpus, restart policy and
HTTP health. Initial canary container ID remained unchanged.

Healthy at 14:43:12 UTC / 19:43:12 Almaty. Five-container 30-minute observation
started immediately; expected finish about 20:13:13 Almaty. First samples show
all healthy, zero errors/restarts/OOM, no tasks yet. Further expansion requires
processing evidence. Private remote artifacts:
/home/bekzat/impulse-wave2-20260929/. Persistent Compose override:
/home/bekzat/project/docker-compose.impulse-wave2-20260929.yml.
