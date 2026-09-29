# Runbook: High Error Rate (5xx errors)

## Alert
- **Alertname**: HighErrorRate
- **Severity**: critical
- **Condition**: error rate > 10% for more than 1 minute

## Common Causes

### 1. Database Connection Pool Exhaustion
- **Symptoms**: HikariCP connection pool active count near max, "Connection is not available" errors in logs
- **How to verify**: Check `hikaricp_connections_active / hikaricp_connections_max` metric; search logs for "ConnectionTimeoutException" or "pool exhausted"
- **Remediation**:
  1. Increase maximum pool size temporarily (if DB can handle more connections)
  2. Check for slow queries holding connections too long
  3. Add connection timeout and leak detection
  4. Scale database or optimize query performance

### 2. Downstream Service Failure
- **Symptoms**: Errors in calling external APIs, timeout exceptions, circuit breaker open
- **How to verify**: Check downstream service health; search logs for "ReadTimeoutException", "ConnectException", or "CircuitBreaker"
- **Remediation**:
  1. Verify downstream service is up and responding
  2. Check circuit breaker state - if open, wait for half-open state
  3. Add fallback / graceful degradation
  4. Review timeout settings and retry policy

### 3. Recent Deployment Introduced Bug
- **Symptoms**: Errors started right after a deployment; new exception types in logs
- **How to verify**: Check deployment timeline; compare error rates before/after deploy; search logs for new exception stack traces
- **Remediation**:
  1. Rollback to previous version if possible
  2. Identify the specific commit / change that introduced the bug
  3. Hotfix and redeploy with proper testing

### 4. Memory Leak / GC Overhead
- **Symptoms**: JVM heap usage high, GC pause times increasing, OutOfMemoryError in logs
- **How to verify**: Check `jvm_memory_used_bytes{area="heap"}`; check GC duration metrics; search logs for "OutOfMemoryError" or "GC overhead limit exceeded"
- **Remediation**:
  1. Restart the service to clear memory (temporary fix)
  2. Take heap dump for analysis: `jmap -dump:format=b,file=heap.hprof <pid>`
  3. Analyze heap dump with Eclipse MAT or JProfiler
  4. Fix the memory leak root cause

## Diagnostic Commands
```bash
# Check error rate
curl 'http://prometheus:9090/api/v1/query?query=rate(http_requests_total{status=~"5.."}[5m])'

# Check connection pool
curl 'http://prometheus:9090/api/v1/query?query=hikaricp_connections_active'

# Search error logs in Loki
curl -G 'http://loki:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service="demo-app"} |= "ERROR"' \
  --data-urlencode 'limit=50'
```

## Escalation
- If error rate > 50% for > 5 minutes: page on-call engineer immediately
- If database is down: escalate to DBA team
- If downstream service is a critical dependency: escalate to that service's team
