# Runbook: Service Down (unreachable)

## Alert
- **Alertname**: DemoAppDown
- **Severity**: critical
- **Condition**: service unreachable (up == 0) for more than 30 seconds

## Common Causes

### 1. Application Crash (OOM, fatal error)
- **Symptoms**: Container restarting, OutOfMemoryError or fatal error in last logs before crash
- **How to verify**: Check container restart count (`docker ps`); check last logs before crash; check memory usage trend
- **Remediation**:
  1. Check if container is restarting: `docker ps -a | grep demo-app`
  2. Check crash logs: `docker logs --tail 200 demo-app`
  3. If OOM: increase memory limit or fix memory leak
  4. If fatal error: identify the error and hotfix

### 2. Health Check Failing
- **Symptoms**: Container running but health endpoint returns non-200, dependency check failing
- **How to verify**: Call health endpoint directly: `curl http://demo-app:8080/actuator/health`; check which component is DOWN
- **Remediation**:
  1. Identify which health indicator is failing (DB, disk, external service)
  2. Fix the underlying dependency issue
  3. If health check is too strict, adjust thresholds
  4. Consider liveness vs readiness probe separation

### 3. Port Conflict / Binding Issue
- **Symptoms**: Application fails to start with "Port already in use" or "Address already in use"
- **How to verify**: Check startup logs for port binding errors; check if another process is using the port
- **Remediation**:
  1. Identify the conflicting process: `lsof -i :8080` or `netstat -tlnp | grep 8080`
  2. Stop the conflicting process or change the application port
  3. In Docker: check if another container is using the port mapping

### 4. Database Unreachable
- **Symptoms**: Application fails to start because it can't connect to database, "Connection refused" errors
- **How to verify**: Check database container status; test connectivity: `telnet postgres 5432`; check DB credentials
- **Remediation**:
  1. Verify database container is running: `docker ps | grep postgres`
  2. Check database logs for errors
  3. Verify connection string, username, password
  4. Restart database if needed

### 5. Resource Exhaustion (CPU / Memory / Disk)
- **Symptoms**: Host unresponsive, OOM killer terminating processes, disk full
- **How to verify**: Check host resource usage: `top`, `free -h`, `df -h`; check dmesg for OOM killer
- **Remediation**:
  1. Free up resources (stop unnecessary containers, clear disk space)
  2. Increase resource limits / upgrade host
  3. Identify resource-hungry processes and optimize
  4. Set resource limits on containers to prevent one service from starving others

## Diagnostic Commands
```bash
# Check container status
docker ps -a | grep demo-app

# Check container logs
docker logs --tail 200 demo-app

# Check health endpoint
curl http://demo-app:8080/actuator/health

# Check host resources
docker stats --no-stream
```

## Escalation
- If service is down for > 2 minutes: page on-call immediately
- If database is down: escalate to DBA / infrastructure team
- If host is unresponsive: escalate to infrastructure / DevOps team
