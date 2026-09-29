#!/usr/bin/env python3
"""
Grounding Checker — verifies every [metric: ...] / [log: "..." ] citation in an
incident's analysis_detail actually exists in the retrieved evidence
(related_metrics / related_logs).

Why this exists (interview story):
  * v1/v2/v3: Loki LogQL single-quote bug -> related_logs always contained
    "Loki search error: 400 ..." -> every [log: ...] citation was fabricated.
  * Metric citations were always grounded (Prometheus chain was healthy).
  * After the fix (v4), log citations are grounded only if the quoted text is a
    verbatim substring of the actually retrieved log lines.
  Format compliance != grounded. This checker measures the difference.

Usage:
  python3 docs/eval/check_grounding.py --v2 --v3 --v4
  python3 docs/eval/check_grounding.py --ids 22,24,26
"""
import argparse
import json
import re
import subprocess


def fetch_incident(iid):
    out = subprocess.run(
        ["docker", "exec", "postgres", "psql", "-U", "agent", "-d", "incident_agent",
         "-t", "-A", "-c",
         "SELECT row_to_json(t) FROM (SELECT id, alertname, confidence, related_metrics, "
         "related_logs, matched_runbooks, description, labels_json, context_snapshot, "
         "analysis_detail FROM incidents WHERE id=%d) t;" % iid],
        capture_output=True, text=True).stdout.strip()
    if not out:
        return None
    try:
        return json.loads(out)
    except Exception:
        return None


def grounding_context(inc):
    """LLM actually saw the FULL context; DB stores a truncated summary.
    Prefer the full context_snapshot when present, else fall back to merged fields."""
    if inc["context_snapshot"] and inc["context_snapshot"].startswith("{"):
        try:
            snap = json.loads(inc["context_snapshot"])
            return snap.get("metrics", ""), snap.get("logs", ""), snap.get("runbooks", "")
        except Exception:
            pass
    return inc["related_metrics"], inc["related_logs"], inc["matched_runbooks"]


METRIC_RE = re.compile(r'\[metric:\s*([^\]]+)\]')
LOG_RE = re.compile(r'\[log:\s*"([^"]*)"\]')


def digits(s):
    return re.findall(r'\d+\.?\d*', s)


def check_metric(cite, metrics):
    """Metric citation is grounded if its numeric values appear in retrieved metrics."""
    nums = digits(cite)
    if not nums:
        return False
    return all(n in metrics for n in nums)


def check_log(cite, logs):
    """Log citation is grounded if it appears verbatim in retrieved logs."""
    return cite in logs


def analyze(iid):
    inc = fetch_incident(iid)
    if inc is None:
        return None
    metrics, logs, _ = grounding_context(inc)
    inc["detail"] = inc["analysis_detail"]
    mc = METRIC_RE.findall(inc["detail"])
    lc = LOG_RE.findall(inc["detail"])
    return {
        **inc,
        "has_snapshot": bool(inc["context_snapshot"]),
        "metric_cites": len(mc),
        "metric_grounded": sum(1 for c in mc if check_metric(c, metrics)),
        "log_cites": len(lc),
        "log_grounded": sum(1 for c in lc if check_log(c, logs)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", default="", help="comma-separated incident ids")
    ap.add_argument("--v2", action="store_true")
    ap.add_argument("--v3", action="store_true")
    ap.add_argument("--v4", action="store_true")
    args = ap.parse_args()

    groups = {"v2": [22, 24, 26], "v3": [28, 30, 32], "v4": [35, 36, 38]}
    ids = []
    for key, ver in (("v2", "v2"), ("v3", "v3"), ("v4", "v4")):
        if getattr(args, key):
            ids += [(i, ver) for i in groups[key]]
    if args.ids:
        ids += [(int(x.strip()), "custom") for x in args.ids.split(",") if x.strip()]
    if not ids:
        ap.error("provide --v2/--v3/--v4 or --ids")

    print(f"{'id':<4} {'ver':<6} {'alertname':<15} {'conf':<5} {'snap':<5} {'mCite':<6} {'mGrd':<6} {'lCite':<6} {'lGrd':<6} analysis_detail (first 50 chars)")
    per_ver = {}
    for iid, ver in ids:
        r = analyze(iid)
        if r is None:
            print(f"{iid:<4} NOT FOUND")
            continue
        agg = per_ver.setdefault(ver, {"m": [0, 0], "l": [0, 0]})
        agg["m"][0] += r["metric_cites"]; agg["m"][1] += r["metric_grounded"]
        agg["l"][0] += r["log_cites"];   agg["l"][1] += r["log_grounded"]
        print(f"{r['id']:<4} {ver:<6} {r['alertname'][:14]:<15} {r['confidence']:<5} "
              f"{'Y' if r['has_snapshot'] else '-':<5} "
              f"{r['metric_cites']:<6} {r['metric_grounded']:<6} {r['log_cites']:<6} {r['log_grounded']:<6} "
              f"{r['detail'][:50]}")

    print("\n=== Grounding rate by version ===")
    for ver, agg in sorted(per_ver.items()):
        m_rate = (agg["m"][1] / agg["m"][0] * 100) if agg["m"][0] else "n/a"
        l_rate = (agg["l"][1] / agg["l"][0] * 100) if agg["l"][0] else "n/a"
        print(f"  {ver}: metric grounding {agg['m'][1]}/{agg['m'][0]} ({m_rate}%) | "
              f"log grounding {agg['l'][1]}/{agg['l'][0]} ({l_rate}%)")
    print("\nNOTE: metric grounding for pre-snapshot records is limited by truncated DB storage;")
    print("      full verification requires context_snapshot (records from the next run onward).")


if __name__ == "__main__":
    main()
