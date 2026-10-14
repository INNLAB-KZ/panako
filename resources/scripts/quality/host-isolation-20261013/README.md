# 2026-10-13: Stage-1 host containment

## Evidence and decision

Two JVM SIGSEGV crashes on `167.233.53.95` at 14:12:15 and 14:12:45 UTC bracket an independent ffmpeg dynamic-loader segmentation fault at 14:12:25 UTC. The JVM reports show heap/container memory headroom. This justifies isolating the affected host; it does **not** prove hardware failure or a JVM defect.

Temurin 17.0.20.1+1 was investigated. The [upstream changes from 17.0.20+8](https://github.com/openjdk/jdk17u/compare/jdk-17.0.20%2B8...jdk-17.0.20.1%2B1) contain HTTP, TLS and XML resource-resolution changes, with no HotSpot change. It was not deployed as a speculative crash fix. The deployed shaded LZ4 factory's `unsafeInstance()` delegates to `safeInstance()`; generic upstream LZ4 assumptions do not establish corruption in this deployed dependency.

## Capacity gate

Initial placement across five hosts was rejected before any service change. Available RAM at a moment in time is insufficient evidence of capacity. Counting existing container limits and reserving at least 2 GiB for the OS permits only:

- One additional Stage-1 on `46.224.159.198`: 22.91 GiB RAM, existing three refine containers, 18 GiB combined container limits after addition.
- One additional Stage-1 on `157.180.26.0`: 30.60 GiB RAM, existing five refine containers, 27 GiB combined limits after addition.

Initial contained fleet: main Redis **22 Stage-1 + 28 refine**, isolated Redis **1 Stage-1 + 1 refine**; total **52**. Queue growth justified a second step: exchange four refine slots for Stage-1, restoring main Redis to **26 Stage-1 + 24 refine**. Isolated Redis stays **1 + 1**. Total capacity remains 52, four slots below the original fleet until host remediation or replacement capacity. Final live state belongs in the dated incident report.

`prioritize-stage1.py` cooperatively drains three refine on `46.224.159.198` and only `refine-d-move-20261006-refine-from-main-1` on `91.99.52.184`, waits for their pending entries to reach zero, stops them, persists replicas=0, and starts the same number of Stage-1 containers in `panako-stage1-priority-20261013`. Recognition code and memory limits remain identical. Both new destinations use the two explicit JDBC buffer options below. Other refine containers, including replica-routed services, retain their IDs and configuration.

## Deployment gates

1. Preserve the original Docker image by content ID and source configuration in private storage.
2. Verify identical JAR, Java executable, libjvm, ffmpeg, dynamic loader and libc hashes in replacements. Preserve source environment, command, entrypoint, heap, windows, memory/swap limits and restart policy. No published ports.
3. For each source worker, wait until its Redis pending count is zero, freeze it, allow an outstanding blocking read to expire, and check again. Resume immediately if a task was assigned. Only an idle, frozen container is intentionally killed, with restart disabled. Retain its filesystem and original settings.
4. Start one replacement. Check health, existing container IDs and configuration equality. Observe real task completion before proceeding.
5. Drain remaining source workers, start the second replacement after offline recovery tests finish, then persist source Compose replicas=0.
6. Verify private ClickHouse routing, both queues, workload timing, and resource headroom. Keep the recognition algorithm unchanged.

Destination-specific exception: `157.180.26.0` needs JDBC `socket_rcvbuf=0&socket_sndbuf=0`, already present on its existing refine workers. The source Stage-1 URL has neither option. The deployed JAR leaves the driver's fixed socket-buffer defaults intact; the uncommitted local source already contains a change that is **not** in the deployed JAR. Preserve the deployed JAR and add only these two transport URL options for the new destination. `target-prepare 1 autotune` records this explicit difference; `target-start` verifies every other environment value.

The initial offline retry on this destination used source transport defaults and started with a three-CPU diagnostic quota. It completed in 1123039 ms, which fails the ten-minute target. Docker `update --cpus=0` did not clear the existing quota in this environment; the effective limit was verified and explicitly raised to 16 CPUs. The next diagnostic JVM was given `ActiveProcessorCount=16` to match production CPU discovery. These diagnostics are not a controlled, single-variable CPU benchmark. `compare-recovery.py` repeats identical audio with the destination settings and verifies normalized recognition equality plus the ten-minute limit before the second production replacement starts.

Containment, observation and offline recovery scripts do not directly change Redis streams or consumer-group state. `publish-recoveries.py` is the separate exception: after explicit approval for the exact pair and immutable manifest SHA, it publishes the two verified successful recovery results atomically. It checks original requests, latest errors, absent pending ownership and concurrent stream changes. It does not change requests, ACKs or group cursors. Publication receipts and subsequent payload/consumer-group checks are stored privately.

## Rollback

All remote artifacts live in `/home/bekzat/host-isolation-20261013` (0700); configuration/results are private. Never print `source-private.json`, `compose-private.json`, full JVM reports or diagnostic environment files.

If a replacement fails verification:

1. Use `isolate.py target-retire CONTAINER_NAME` on its target host. This waits for its task to finish before stopping it. Do not interrupt an active task or start its original counterpart while replacement ownership is unresolved.
2. Restore that original source container's `unless-stopped` policy and start it. The original image, environment and filesystem were retained. Restoring service on the suspect host is an emergency tradeoff, not proof it is healthy.
3. If full isolation was finalized, restore the exact backed-up Compose override from `source-compose-override-before.json` before future Compose operations. If only part of the fleet is restored, set the intended replica count explicitly.
4. Keep retired replacement services at replicas=0 in their Compose files to prevent accidental resurrection. Verify total counts and old/new consumer pending entries.

Do not recreate the source service with its pre-isolation six-replica Compose chain while replacements remain active.

To reverse the temporary refine-to-Stage-1 exchange, first drain each priority Stage-1 to pending=0 using the same idle freeze/recheck procedure as the source retirement. The existing `target-retire` command only accepts the evacuation project; it must not be assumed to support the priority project. Stop only idle replacements, disable restart, and set the priority Compose replicas to zero. Restore each exact saved refine override from `priority/before-private.json`, restore its restart policy, and start its retained container. Verify the in-memory drain flag is reset by the new JVM and the original consumer can receive tasks. Restore only the number of refine slots actually freed; never run both roles beyond the memory reservation gate.

`HETZNER_SUPPORT_DRAFT.md` is a prepared support message; it has not been sent.
