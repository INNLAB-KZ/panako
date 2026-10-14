#!/usr/bin/env python3
"""Compare RecognitionProbe outputs, retaining duplicate raw detections."""
import collections
import json
from pathlib import Path
import sys


def canonical(response):
    response = dict(response)
    response.pop("processing_time_ms", None)
    response["matches"] = sorted(response["matches"], key=lambda m: (
        str(m["identifier"]), m["query_start_seconds"], m["query_end_seconds"]))
    return response


def main():
    old, new = map(Path, sys.argv[1:3])
    read = lambda prefix, suffix: Path(str(prefix) + suffix).read_text()
    old_raw = collections.Counter(read(old, ".raw.tsv").splitlines())
    new_raw = collections.Counter(read(new, ".raw.tsv").splitlines())
    old_json = json.loads(read(old, ".json"))
    new_json = json.loads(read(new, ".json"))
    missing, added = old_raw - new_raw, new_raw - old_raw
    report = {
        "raw_equal": old_raw == new_raw,
        "response_equal": canonical(old_json) == canonical(new_json),
        "baseline_tracks": old_json["unique_tracks_count"],
        "candidate_tracks": new_json["unique_tracks_count"],
        "raw_missing": sum(missing.values()),
        "raw_added": sum(added.values()),
        "missing_examples": list(missing)[:5],
        "added_examples": list(added)[:5],
        "baseline_metrics": json.loads(read(old, ".metrics.json")),
        "candidate_metrics": json.loads(read(new, ".metrics.json")),
    }
    print(json.dumps(report, indent=2))
    return 0 if report["raw_equal"] and report["response_equal"] else 1


if __name__ == "__main__":
    sys.exit(main())
