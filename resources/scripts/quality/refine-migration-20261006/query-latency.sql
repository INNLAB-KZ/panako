SELECT toString(address) AS client, count() AS queries, round(avg(query_duration_ms)) AS avg_ms, max(query_duration_ms) AS max_ms, round(avg(result_bytes)/1048576,1) AS result_mib
FROM system.query_log WHERE event_date=today() AND event_time > now()-INTERVAL 15 MINUTE AND type=2 AND query_kind='Select'
GROUP BY address ORDER BY queries DESC LIMIT 20
SETTINGS max_threads=2, max_memory_usage=536870912 FORMAT JSONEachRow
