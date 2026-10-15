# Draft: correlated native crashes on VPS 167.233.53.95

Subject: Please investigate host/hypervisor events affecting 167.233.53.95 at 2026-10-13 14:12 UTC

Hello,

Please investigate the physical host and hypervisor serving VPS **167.233.53.95**.
Three processes crashed within approximately 31 seconds on 13 October 2026:

| UTC | Process | Fault |
| --- | --- | --- |
| 14:12:15 | Java, Docker `project-panako-api-7` | SIGSEGV in C2-compiled `java.util.TimSort.sort`; invalid address 0 |
| 14:12:25 | ffmpeg PID 3722709 | Segmentation fault in `ld-linux-x86-64.so.2`; kernel reports likely virtual CPU 12 |
| 14:12:45 | Java, Docker `project-panako-api-6` | SIGSEGV in `G1ParScanThreadState::trim_queue_to_threshold`; invalid address `0x00000006b29c7908` |

The corresponding local time is **19:12:15–19:12:45 Asia/Almaty (UTC+5)**.

Guest CPU identification: AMD EPYC-Genoa, 16 virtual CPUs, KVM hypervisor.
Both Java processes use Temurin 17.0.20+8, a 3584 MiB maximum heap and a 4608 MiB container memory limit.
The fatal-error reports show memory headroom; neither report establishes an out-of-memory cause.
The ffmpeg crash occurred in a separate native process, outside the JVM.

Guest kernel evidence:

```text
Oct 13 14:12:25 snapshot-olaf-worker-1 kernel: ffmpeg[3722709]: segfault at fff12f9e1740 ip 00007ff89aab0340 sp 00007ffe15bc5930 error 6 in ld-linux-x86-64.so.2[11340,7ff89aaa1000+2a000] likely on CPU 12 (core 12, socket 0)
```

A search of the available guest kernel journal over the previous seven days found this ffmpeg fault and no matching OOM, MCE, EDAC, watchdog or corruption report. This cannot exclude a physical host or hypervisor problem: physical hardware telemetry may not be visible inside the guest.

Please check for:

1. Host memory/ECC, machine-check and CPU errors around the timestamps.
2. Live migration, maintenance, hypervisor restart or virtualization faults.
3. Other affected guests on the same host.
4. Whether migration to another physical host is appropriate.

We have retained the original containers and private JVM crash reports. Full reports contain runtime environment details and credentials; please request a sanitized report if needed.

**This is a draft. It has not been sent. Hardware failure is suspected, not confirmed.**
