# Storage OOM: isolated two-LSB impulses

## Diagnosis

The recurring 249-second reference audio decoded correctly. Production and CDN
copies had identical bytes. Eight PCM16 samples of magnitude two in an otherwise
silent two-second tail produced dense tied spectral maxima. The production
processor generated 8,180 event points; its pair constraints imply 181,031,288
fingerprint triples, 181,016,192 with middle peaks in seconds 246–248.
Counting incoming × outgoing compatible neighbours was cross-checked against
explicit triple enumeration on the first 30 seconds.

The process exhausted 2, 4, and 6 GiB Java heaps. Increasing the heap did not
resolve the combinatorial allocation. Nine messages for the same reference
remained pending and were repeatedly reclaimed. Diagnostic audio and response
payloads are excluded from Git.

## Fix

- Extend the existing single-sample impulse suppression from one to two PCM16
  quantization steps. A frame must contain exactly one nonzero sample. Stronger
  samples, multiple nonzero samples, silence, and nonfinite input are unchanged.
  Only the cloned analysis buffer is modified; timestamps are preserved.
- Bound fingerprint generation at 2,000,000 per processor, for both pairs and
  triples. Exceeding the bound clears the partial list and throws an explicit
  task error. It does not truncate results or return partial recognition success.
- Hash layout, matching thresholds, database schema, and chunk boundaries are
  unchanged. The bound is a controlled failure for other pathological inputs,
  not a guarantee that every possible input will fit every memory limit.

## Validation

- Java 17: 72 targeted tests passed, one integration test skipped. Tests cover
  positive/negative impulses, exact threshold boundaries, preservation of other
  frames, pair/triple overflow, and success exactly at the fingerprint limit.
- The failing file completed extraction with 512 MiB heap: 15,095 fingerprints
  locally; 15,096 with production Linux decoder. The baseline decoders also
  differed by one fingerprint outside the problematic region.
- Production Linux runtime: extraction completed in 3.409 seconds; isolated
  store and recognition of a 15-second excerpt at its expected offset passed
  in 4.192 seconds, using the published candidate image and in-memory storage.
- Full 54-minute positive control: baseline and candidate produced exactly the
  same 183,102 ordered fingerprints, including hashes, frame times, frequency
  bins, and float magnitude bits. SHA-256:
  `9f06134161c993b4761275b531fcd26f34ea79d6d62446c6ac8c03674f46a57e`.
  Control runs used the same installed Linux runtime with the exact old and
  new processor classes overlaid separately. This is evidence for that control,
  not a claim of exhaustive catalog-wide parity.
- Comparing the baseline and Gradle-built fat JAR found only the processor
  class and build metadata in the manifest changed; all other entries match.

## Deployment

Only the storage service was updated on 2026-11-04 at 09:40:54 UTC, after a
cooperative drain. Existing memory settings (6 GiB heap, 8 GiB container),
environment, API key, CPU allocation, networking, and restart policy were
verified unchanged. Private health returned 200, protected route without key
returned 401, and the public API endpoint remained unavailable.

Image: `innlabkz/ozen-panako:storage-2lsb-20261104`

Pinned digest: `sha256:55534716aeb07b83de172804e851dccc2f25adafaf01412182fdab84e459437e`

JAR SHA-256: `4b50a8f2aab9059ceba9cf4f5e117fa9a23c6b3544c94837cbdc47475bcf40d1`

The general `latest` tag and the Stage-1/refine fleet were not redeployed.
Production queue recovery is observed through normal consumption/reclaim;
no manual Redis writes, ACKs, or deletions were used.

## Rollback

Protected deployment backups are in `/home/bekzat/storage-fix-20261104` on the
storage host. Restore `compose-before-private.json` to the original Compose
path after draining the candidate, then recreate only `store` using project
`panako-storage-recovery-20261014`, with `--no-deps --no-build --pull never`.
The old image is retained, but restoring it also restores this known OOM defect.
Do not reuse a drain helper until its JAR validation includes the candidate.
