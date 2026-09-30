#!/bin/bash
# ============================================================
# AI Incident Triage Agent - Baseline 评估脚本（支持单故障 + 复合故障）
# 依次注入故障（ground truth），等待 AI 分析完成，从 Postgres 拉取结果，输出 CSV。
#
# 单故障：   error / latency / memory（一个告警 → 一个 Incident）
# 复合故障： error+latency / latency+memory / error+memory
#           （同一时刻注入两个故障 → 两个告警独立投递 → 两个 Incident，
#            评估"判别力 discrimination"：各自命中各自根因，不串扰）
#
# 用法：bash docs/eval/run_baseline.sh [output.csv] [all|single|composite]
# 输出：默认 docs/eval/baseline_results.csv
# ============================================================

cd /Users/yangtong/ai-incident-agent
OUT=${1:-docs/eval/baseline_results.csv}
MODE=${2:-all}
echo "time,fault,incident_id,alertname,status,confidence,prompt_tokens,completion_tokens,duration_ms,root_cause" > "$OUT"

# 故障 → 期望告警名
alertname_for() {
  case $1 in
    error)   echo "HighErrorRate" ;;
    latency) echo "HighLatency" ;;
    memory)  echo "HighMemoryUsage" ;;
  esac
}

# --- 告警隔离（alert settle）：等上一个 case 的告警全部 resolve ---
settle() {
  echo ">>> [settle] 等待告警清空..."
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
      echo ">>> [settle] 告警已清空（${settled}s）"
      break
    fi
    sleep 10; settled=$((settled+10))
  done
  sleep 30  # rate 窗口滑过：PromQL [1m] 内残留指标归零
}

# --- 跑一个 case：fault 支持 "error" / "error+latency" 复合语法 ---
run_case() {
  local fault=$1
  local wait_seconds=$2
  IFS='+' read -ra faults <<< "$fault"

  # 展开期望告警列表
  local alerts=()
  for f in "${faults[@]}"; do
    alerts+=("$(alertname_for "$f")")
  done

  settle

  # 记录本轮起点 incident id（只认新产生的）
  local start_id
  start_id=$(docker exec postgres psql -U agent -d incident_agent -t -c "SELECT COALESCE(MAX(id),0) FROM incidents;" | tr -d ' \n')

  echo ">>> [$fault] 注入故障: ${faults[*]}"
  for f in "${faults[@]}"; do
    curl -s -X POST "http://localhost:8080/api/admin/fail/$f" > /dev/null
  done

  # 压流量（error/latency 需要制造指标；memory 由内部线程自动分配）
  for f in "${faults[@]}"; do
    case $f in
      error)   (for i in $(seq 1 200); do curl -s -o /dev/null -X POST -H "Content-Type: application/json" -d '{"productId":1,"quantity":1}' http://localhost:8080/api/orders; sleep 0.3; done) & ;;
      latency) (for i in $(seq 1 60); do curl -s -o /dev/null http://localhost:8080/api/products; sleep 0.2; done) & ;;
    esac
  done

  # 轮询：等【每个】期望告警名的 COMPLETED incident 都出现（复合 = 两个都要）
  echo ">>> [$fault] 等待 AI 分析完成（期望: ${alerts[*]}，上限 ${wait_seconds}s）..."
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
      echo ">>> [$fault] 全部告警分析完成（${waited}s）"
      break
    fi
    sleep 10; waited=$((waited+10))
  done

  # 停故障（无论成败都清理，避免污染下一个 case）
  curl -s -X POST http://localhost:8080/api/admin/fail/stop > /dev/null

  # 每个期望 alertname 各取最新一条 COMPLETED 输出（每行一条 incident）
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
  sleep 5  # 给告警一点回落时间
}

# 单故障基线（error ~90s / latency ~90s / memory ~4-6min）
if [ "$MODE" != "composite" ]; then
  run_case error 180
  run_case latency 180
  run_case memory 420
fi

# 复合故障（判别力矩阵：两告警独立分析，各中各自根因）
if [ "$MODE" != "single" ]; then
  run_case error+latency 420
  run_case latency+memory 600
  run_case error+memory 600
fi

echo "=== ALL DONE, results in: $OUT ==="
cat "$OUT"
