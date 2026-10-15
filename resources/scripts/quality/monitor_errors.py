"""Error signatures for operational monitoring; JVM option names are not errors."""
import re

_OOM = re.compile(r"(?<!\w)(?:java\.lang\.)?OutOfMemoryError\b")


def count_java_oom(log_text):
    return len(_OOM.findall(log_text))
