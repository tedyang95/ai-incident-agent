#!/bin/bash
# ============================================================
# AI Incident Triage Agent - Baseline 评估脚本
# 依次注入 error / latency / memory 三种故障（ground truth），
# 等待 AI 分析完成，从 Postgres 拉取结果，输出 CSV 汇总。
#
# 用法：bash docs/eval/run_baseline.sh
# 输出：docs/eval/baseline_results.csv
# ============================================================

cd /Users/yangtong/ai-incident-agent
OUT=${1:-docs/eval/baseline_results.csv}
echo "time,fault,incident_id,alertname,status,confidence,prompt_tokens,completion_tokens,duration_ms,root_cause" > "$OUT"

run_case() {
  local fault=$1
  local wait_seconds=$2

  # --- 告警隔离（alert settle）：等上一个 case 的告警全部 resolve，避免脏数据污染 ---
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
  # --- settle 结束 ---

  # 记录本轮起点 incident id（只认新产生的）
  local start_id
  start_id=$(docker exec postgres psql -U agent -d incident_agent -t -c "SELECT COALESCE(MAX(id),0) FROM incidents;" | tr -d ' \n')

  echo ">>> [$fault] 注入故障..."
  curl -s -X POST "http://localhost:8080/api/admin/fail/$fault" > /dev/null

  # 压流量（error/latency 需要制造指标；memory 由内部线程自动分配，无需流量）
  case $fault in
    error)   (for i in $(seq 1 200); do curl -s -o /dev/null -X POST -H "Content-Type: application/json" -d '{"productId":1,"quantity":1}' http://localhost:8080/api/orders; sleep 0.3; done) & ;;
    latency) (for i in $(seq 1 60); do curl -s -o /dev/null http://localhost:8080/api/products; sleep 0.2; done) & ;;
  esac

  # 轮询：等待新 incident 出现且 COMPLETED（或超时）
  echo ">>> [$fault] 等待 AI 分析完成（上限 ${wait_seconds}s）..."
  local waited=0 row=""
  while [ $waited -lt $wait_seconds ]; do
    row=$(docker exec postgres psql -U agent -d incident_agent -t -c \
      "SELECT id||'|'||alertname||'|'||status||'|'||coalesce(confidence,0)||'|'||coalesce(prompt_tokens,0)||'|'||coalesce(completion_tokens,0)||'|'||coalesce(analysis_duration_ms,0)||'|'||replace(coalesce(root_cause_hypothesis,''),'|',' ') FROM incidents WHERE id > $start_id AND status='COMPLETED' ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d ' \t')
    if [ -n "$row" ]; then break; fi
    sleep 10; waited=$((waited+10))
  done

  # 停故障（无论成败都清理，避免污染下一个 case）
  curl -s -X POST http://localhost:8080/api/admin/fail/stop > /dev/null

  if [ -n "$row" ]; then
    echo "$(date +%H:%M:%S),$fault,$row" >> "$OUT"
    echo ">>> [$fault] DONE → $row"
  else
    echo ">>> [$fault] TIMEOUT（无 COMPLETED incident）"
    echo "$(date +%H:%M:%S),$fault,TIMEOUT,,,,,," >> "$OUT"
  fi
  sleep 5  # 给告警一点回落时间，避免误判下一个 case
}

# 三种故障依次跑（error ~90s / latency ~90s / memory ~4-6min）
run_case error 180
run_case latency 180
run_case memory 420

echo "=== ALL DONE, results in: $OUT ==="
cat "$OUT"
