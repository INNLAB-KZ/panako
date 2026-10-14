# Storage queue retention and reconciliation — orchestrator project

## Task

Inspect the producer of `panako-store-requests` in Redis `10.0.0.2`. Make storage delivery durable and reconcile catalog items that never received a successful ingestion result. This repository contains the consumer; implement producer changes in the separate orchestrator repository.

## Observed evidence, 2026-10-14 UTC

- Storage consumers had stopped making progress after Java heap OOM while their containers stayed running.
- Ingestion has been recovered with a bounded, serial consumer on ClickHouse Main.
- Request stream retained approximately 100,000 entries. Before recovery, the consumer's last delivered ID was `1786454182507-0`, while the first retained request read after recovery was `1786804617563-0`. The retained range had moved ahead of the stopped consumer.
- Determine whether producer `XADD MAXLEN`, `XTRIM`, or another retention job caused this. Do not assume every historical request is still in Redis.
- An earlier read-only snapshot contained 100,004 unread messages, 28,925 distinct complete payloads and 28,834 distinct `input_isrc` values. These are message/catalog-key counts, not a verified count of tracks missing from ClickHouse.
- Redis `XINFO GROUPS lag` may be null after stream deletions. It must not be interpreted as zero backlog.
- Store responses use `ok`, `already_exists`, `already_indexed_match`, or `error`. Existing errors use `recording_id`; successes commonly use `request_id`. Correlate both with the Redis message key.

## Required changes

1. Locate every producer and trimming path for `panako-store-requests` and `panako-store-results`.
2. Keep unprocessed requests and pending entries safe. A fixed length cap must not silently evict work. Implement acknowledged retention with a safety margin, based on all required consumer groups, and a durable task/outbox record.
3. Keep one outstanding logical ingestion per canonical payload/catalog identity. Preserve genuinely different audio versions, paths and ISRC aliases; do not deduplicate by ISRC alone without checking semantics.
4. Retry transient failures with bounded backoff, using durable attempt state and an observable dead-letter state. Never equate message publication with successful indexing.
5. Reconcile durable ingestion requests and catalog state against verified successful responses and ClickHouse metadata. Restore only confirmed missing work. Start with a dry-run report; do not blindly requeue the entire catalog.
6. Handle out-of-order/repeated results idempotently. An older failure must not overwrite a newer verified success.
7. Report unread requests, pending age, distinct outstanding jobs, processing rate, failures and oldest outstanding task. If Redis lag is unknown, report unknown or calculate a read-only fallback; never reset the delivery cursor to manufacture a metric.
8. Add tests for trimming under consumer downtime, retries after success, duplicate delivery, producer restart, and request/result ID field differences.

## Boundaries

- No broad Redis deletion, stream recreation, cursor reset, database deletion or credential changes as part of this prompt.
- First inspect and produce a concrete patch and reconciliation preview.
- Keep URL credentials, Redis credentials and private payloads out of logs and Git.
- `panako-store-patch` is a separate maintenance queue. Its ClickHouse DELETE timeout requires separate investigation; do not mix it into ingestion throughput measurements.
