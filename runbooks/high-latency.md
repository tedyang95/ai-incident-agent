# Runbook: High Latency (p99 > 1s)

## Alert
- **Alertname**: HighLatency
- **Severity**: warning
- **Condition**: p99 request latency > 1 second for more than 1 minute

## Common Causes

### 1. Database Slow Queries
- **Symptoms**: Database query time increasing, connection pool active count rising, slow query logs
- **How to verify**: Check database query latency metrics; enable slow query logging; look for queries with high execution time in logs
- **Remediation**:
  1. Identify slow queries using `EXPLAIN ANALYZE`
  2. Add missing indexes
  3. Optimize query structure (avoid N+1 queries, use joins appropriately)
  4. Consider query caching for frequently accessed data

### 2. Downstream Service Latency
- **Symptoms**: Timeout exceptions when calling downstream services, circuit breaker half-open
- **How to verify**: Check downstream service response time metrics; search logs for "ReadTimeout" or slow response warnings
- **Remediation**:
  1. Check downstream service health and performance
  2. Increase timeout if appropriate (temporary)
  3. Add caching for downstream responses
  4. Consider async processing for non-critical downstream calls

### 3. Garbage Collection Pauses
- **Symptoms**: Latency spikes correlate with GC pauses, heap usage near max, long GC duration
- **How to verify**: Check `jvm_gc_pause_seconds` metric; check heap usage trend; enable GC logging
- **Remediation**:
  1. Increase heap size if under-provisioned
  2. Switch to G1GC or ZGC for lower pause times
  3. Tune GC parameters (MaxGCPauseMillis, InitiatingHeapOccupancyPercent)
  4. Investigate memory leaks causing frequent GC

### 4. Thread Pool Exhaustion
- **Symptoms**: Request queue growing, thread pool active count near max, "Task rejected" errors
- **How to verify**: Check `tomcat_threads_current` or `jetty_threads` metrics; check queue size
- **Remediation**:
  1. Increase thread pool size (if resources allow)
  2. Identify requests holding threads too long (slow I/O, blocking calls)
  3. Use async processing for long-running tasks
  4. Add request timeout to prevent thread hogging

### 5. Network Issues
- **Symptoms**: Latency across all services, connection timeouts, DNS resolution delays
- **How to verify**: Check network latency between services; check DNS resolution time; check for packet loss
- **Remediation**:
  1. Verify network connectivity between services
  2. Check DNS configuration and caching
  3. Contact infrastructure / network team
  4. Consider service mesh (Istio/Linkerd) for better network observability

## Diagnostic Commands
```bash
# Check p99 latency
curl 'http://prometheus:9090/api/v1/query?query=histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))'

# Check GC pauses
curl 'http://prometheus:9090/api/v1/query?query=jvm_gc_pause_seconds_sum'

# Check thread pool
curl 'http://prometheus:9090/api/v1/query?query=tomcat_threads_current'
```

## Escalation
- If p99 latency > 5s for > 5 minutes: escalate to on-call
- If latency affects checkout / payment flow: escalate immediately (business impact)
- If database is the bottleneck: escalate to DBA
