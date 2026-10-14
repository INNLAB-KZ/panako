# Recognition comparison

`RecognitionProbe` calls the monitor pipeline directly without starting Redis, Kafka,
or HTTP. It writes raw detections, the final monitor response, elapsed time, and a
sampled heap peak. Compile once against the candidate JAR, then run the same probe
in separate JVMs with the baseline and candidate JARs:

```sh
javac --release 11 -cp candidate.jar -d classes RecognitionProbe.java
java -Xmx3584m -cp classes:baseline.jar be.panako.http.RecognitionProbe recording.m4a baseline
java -Xmx3584m -cp classes:candidate.jar be.panako.http.RecognitionProbe recording.m4a candidate
python3 compare.py baseline candidate
```

Use the same audio bytes, config.properties, environment, JVM, CPU/memory limits,
and database for both runs. The probe imports Key environment overrides like the
production server. It does not consume messages or publish results. The existing
ClickHouse storage constructor executes `CREATE TABLE IF NOT EXISTS`; use an existing
test database or ensure the configured production tables already exist.

Raw output retains duplicate detections and all numeric recognition fields, excluding
temporary query paths. Results are sorted before aggregation to remove thread completion
order from the comparison. Response comparison ignores only elapsed time and match-list
order. Metadata is included, so edits to titles/URLs can also cause a difference.

A live database can change between runs. Exact equality is useful evidence for the
sample, not proof for every recording. Investigate differences and repeat against a
fixed database snapshot when needed. Sampled heap includes uncollected garbage and
can miss brief spikes; use it as a diagnostic, not a guaranteed memory bound. Inspect
logs for failed windows/database queries even when an older build returns success.
Keep production configuration, credentials, audio, and result artifacts outside Git.

## Recorded checks

- [2026-09-24: production baseline vs candidate](reports/2026-09-24.md)
- [2026-09-24: Redis pilot deployment and first check](reports/2026-09-24-pilot.md)
- [2026-09-25: 30-minute result and OOM recovery](reports/2026-09-25-pilot-30m.md)
- [2026-09-25: six-worker host rollout](reports/2026-09-25-host-rollout.md)
- [2026-09-25: unattended rollout and performance gates](reports/2026-09-25-overnight.md)
- [2026-09-25: input parsing and download integrity pilot](reports/2026-09-25-input-pilot.md)
- [2026-09-25: input-handling fleet rollout](reports/2026-09-25-input-rollout.md)

## Watcher error detection

`monitor_errors.py` distinguishes Java OOM errors from JVM option names printed
at startup. Run its regression tests from the repository root:

```sh
python3 -m unittest discover -s resources/scripts/quality -p 'test_*.py'
```
