#!/bin/bash
# ============================================================
# AI Incident Diagnosis Agent - baseline evaluation script (single + composite faults)
# Injects each fault (ground truth), waits for the AI analysis to complete,
# pulls results from Postgres, and writes a CSV.
#
# Single faults:   error / latency / memory (one alert -> one Incident)
# Composite faults: error+latency / latency+memory / error+memory
#           (two faults injected at the same time -> two alerts delivered
#            independently -> two Incidents; evaluates discrimination:
#            each must hit its own root cause without cross-talk)
#
# Usage: bash docs/eval/run_baseline.sh [output.csv] [all|single|composite]
# Output: default docs/eval/baseline_results.csv
# ============================================================

cd /Users/yangtong/ai-incident-agent
OUT=${1:-docs/eval/baseline_results.csv}
MODE=${2:-all}
echo "time,fault,incident_id,alertname,status,confidence,prompt_tokens,completion_tokens,duration_ms,root_cause" > "$OUT"

# fault -> expected alert name
alertname_for() {
  case $1 in
    error)      echo "HighErrorRate" ;;
    latency)    echo "HighLatency" ;;
    memory)     echo "HighMemoryUsage" ;;
    downstream) echo "HighErrorRate" ;;  # 502 counts into 5xx -> same alert name, different root cause class
  esac
}

# --- alert settle: wait until the previous case's alerts are fully resolved ---
settle() {
  echo ">>> [settle] waiting for alerts to clear..."
  local settled=0
  while [ $settled -lt 150 ]; do
    local firing
    firing=$(curl -s "http://localhost:9090/api/v1/alerts" | python3 -c "
import json,sys
try:
    data=json.load(sys.stdin)
    print(sum(1 for a in data['data']['alerts'] if a['state']=='firing'))
except Exception:
    print('-1')
" 2>/dev/null)
    if [ "$firing" = "0" ]; then
      echo ">>> [settle] alerts cleared (${settled}s)"
      break
    fi
    sleep 10; settled=$((settled+10))
  done
  sleep 30  # let the rate window slide: residual metrics within PromQL [1m] go to zero
}

# --- run one case: fault supports composite syntax like "error+latency" ---
run_case() {
  local fault=$1
  local wait_seconds=$2
  IFS='+' read -ra faults <<< "$fault"

  # expand the expected alert list
  local alerts=()
  for f in "${faults[@]}"; do
    alerts+=("$(alertname_for "$f")")
  done

  settle

  # record the current max incident id (only count newly created incidents)
  local start_id
  start_id=$(docker exec postgres psql -U agent -d incident_agent -t -c "SELECT COALESCE(MAX(id),0) FROM incidents;" | tr -d ' \n')

  echo ">>> [$fault] injecting fault: ${faults[*]}"
  for f in "${faults[@]}"; do
    curl -s -X POST "http://localhost:8080/api/admin/fail/$f" > /dev/null
  done

  # generate traffic (error/latency need metrics; memory is allocated by the internal thread)
  for f in "${faults[@]}"; do
    case $f in
      error)      (for i in $(seq 1 200); do curl -s -o /dev/null -X POST -H "Content-Type: application/json" -d '{"productId":1,"quantity":1}' http://localhost:8080/api/orders; sleep 0.3; done) & ;;
      latency)    (for i in $(seq 1 60); do curl -s -o /dev/null http://localhost:8080/api/products; sleep 0.2; done) & ;;
      downstream) (for i in $(seq 1 100); do curl -s -o /dev/null -X POST -H "Content-Type: application/json" -d '{"productId":1,"quantity":1}' http://localhost:8080/api/orders; sleep 0.5; done) & ;;
    esac
  done

  # poll: wait until a COMPLETED incident exists for EVERY expected alertname
  # (composite = both must appear)
  echo ">>> [$fault] waiting for AI analysis (expected: ${alerts[*]}, cap ${wait_seconds}s)..."
  local waited=0
  while [ $waited -lt $wait_seconds ]; do
    local missing=""
    for a in "${alerts[@]}"; do
      local cnt
      cnt=$(docker exec postgres psql -U agent -d incident_agent -t -c \
        "SELECT count(*) FROM incidents WHERE id > $start_id AND alertname='$a' AND status='COMPLETED';" 2>/dev/null | tr -d ' \n')
      [ "$cnt" = "0" ] && missing="$missing $a"
    done
    if [ -z "$missing" ]; then
      echo ">>> [$fault] all alerts analyzed (${waited}s)"
      break
    fi
    sleep 10; waited=$((waited+10))
  done

  # stop the fault (always clean up, even on failure, to avoid polluting the next case)
  curl -s -X POST http://localhost:8080/api/admin/fail/stop > /dev/null

  # for each expected alertname, take the latest COMPLETED output (one incident per row)
  local rows_found=0
  for a in "${alerts[@]}"; do
    local row
    row=$(docker exec postgres psql -U agent -d incident_agent -t -c \
      "SELECT id||'|'||alertname||'|'||status||'|'||coalesce(confidence,0)||'|'||coalesce(prompt_tokens,0)||'|'||coalesce(completion_tokens,0)||'|'||coalesce(analysis_duration_ms,0)||'|'||replace(coalesce(root_cause_hypothesis,''),'|',' ') FROM incidents WHERE id > $start_id AND alertname='$a' AND status='COMPLETED' ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d ' \t')
    if [ -n "$row" ]; then
      echo "$(date +%H:%M:%S),$fault,$row" >> "$OUT"
      echo ">>> [$fault/$a] DONE → $row"
      rows_found=$((rows_found+1))
    else
      echo ">>> [$fault/$a] TIMEOUT"
    fi
  done
  [ $rows_found -eq 0 ] && echo "$(date +%H:%M:%S),$fault,TIMEOUT,,,,,," >> "$OUT"
  sleep 5  # give alerts a little time to settle back
}

# --- healthy baseline: no fault injected; synthetic alert; agent must NOT invent a root cause ---
run_healthy() {
  local wait_seconds=$1
  settle
  local start_id
  start_id=$(docker exec postgres psql -U agent -d incident_agent -t -c "SELECT COALESCE(MAX(id),0) FROM incidents;" | tr -d ' \n')
  echo ">>> [healthy-baseline] sending synthetic alert (no fault injected)"
  curl -s -X POST "http://localhost:8081/api/alert/test?alertname=HealthyCheck&severity=warning&service=demo-app" > /dev/null
  local waited=0
  while [ $waited -lt $wait_seconds ]; do
    local cnt
    cnt=$(docker exec postgres psql -U agent -d incident_agent -t -c \
      "SELECT count(*) FROM incidents WHERE id > $start_id AND alertname='HealthyCheck' AND status='COMPLETED';" 2>/dev/null | tr -d ' \n')
    [ "$cnt" != "0" ] && break
    sleep 10; waited=$((waited+10))
  done
  local row
  row=$(docker exec postgres psql -U agent -d incident_agent -t -c \
    "SELECT id||'|'||alertname||'|'||status||'|'||coalesce(confidence,0)||'|'||coalesce(prompt_tokens,0)||'|'||coalesce(completion_tokens,0)||'|'||coalesce(analysis_duration_ms,0)||'|'||replace(coalesce(root_cause_hypothesis,''),'|',' ') FROM incidents WHERE id > $start_id AND alertname='HealthyCheck' AND status='COMPLETED' ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d ' \t')
  if [ -n "$row" ]; then
    echo "$(date +%H:%M:%S),healthy-baseline,$row" >> "$OUT"
    echo ">>> [healthy-baseline] DONE → $row"
  else
    echo "$(date +%H:%M:%S),healthy-baseline,TIMEOUT,,,,,," >> "$OUT"
    echo ">>> [healthy-baseline] TIMEOUT"
  fi
}

# --- staggered temporal case: fault1 runs to completion (leaves logs in Loki),
#     then fault2 runs immediately so its analysis window contains the stale logs ---
run_staggered() {
  local first=$1 second=$2 wait=$3
  echo ">>> [staggered $first → $second] phase 1: $first"
  run_case "$first" "$wait"
  echo ">>> [staggered $first → $second] phase 2: $second (stale logs from $first still in Loki window)"
  run_case "$second" "$wait"
}

# control + temporal cases (full suite only)
if [ "$MODE" = "all" ]; then
  # healthy-baseline runs FIRST on a clean environment: a "no fault" control must
  # not see leftover evidence from earlier cases (Prometheus rate()[5m] window and
  # the Loki correlation window would still hold the previous fault's traces).
  run_healthy 180
  run_staggered error latency 420
  run_staggered downstream error 420
fi

# single-fault baselines (error ~90s / latency ~90s / memory ~4-6min / downstream ~2-3min)
if [ "$MODE" != "composite" ]; then
  run_case error 180
  run_case latency 180
  run_case memory 420
  run_case downstream 240
fi

# composite faults (discrimination matrix: two alerts analyzed independently, each hits its own root cause)
if [ "$MODE" != "single" ]; then
  run_case error+latency 420
  run_case latency+memory 600
  run_case error+memory 600
  run_case downstream+latency 420
  run_case downstream+memory 600
fi

echo "=== ALL DONE, results in: $OUT ==="
cat "$OUT"
