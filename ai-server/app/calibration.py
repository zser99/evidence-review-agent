"""확신도 보정 검증: 확신도 구간별 실제 정답률과 '담당자 확인으로 넘길 기준' 추천."""
from __future__ import annotations


def reliability(samples: list[dict], edges: list[float]) -> list[dict]:
    """edges로 나눈 구간별 건수·평균 확신도·실제 정답률."""
    out = []
    for i in range(len(edges) - 1):
        lo, hi = edges[i], edges[i + 1]
        last = i == len(edges) - 2
        xs = [s for s in samples if lo <= s["confidence"] < hi or (last and s["confidence"] == hi)]
        n = len(xs)
        out.append({
            "range": f"{lo:.2f}–{hi:.2f}", "low": lo, "high": hi, "count": n,
            "mean_confidence": round(sum(s["confidence"] for s in xs) / n, 4) if n else None,
            "accuracy": round(sum(1 for s in xs if s["correct"]) / n, 4) if n else None,
        })
    return out


def ece(samples: list[dict], edges: list[float]) -> float | None:
    """Expected Calibration Error: 구간별 |정답률 − 평균 확신도|의 건수 가중 평균."""
    if not samples:
        return None
    total = len(samples)
    err = 0.0
    for b in reliability(samples, edges):
        if b["count"]:
            err += b["count"] / total * abs(b["accuracy"] - b["mean_confidence"])
    return round(err, 4)


def threshold_table(samples: list[dict], candidates: list[float] | None = None) -> list[dict]:
    """기준값별 자동 처리 비율과 자동 처리분의 정답률."""
    candidates = candidates or [0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.97, 0.99]
    total = len(samples)
    rows = []
    for t in candidates:
        auto = [s for s in samples if s["confidence"] >= t]
        rows.append({
            "threshold": t, "auto_count": len(auto), "auto_rate": round(len(auto) / total, 4) if total else None,
            "auto_accuracy": round(sum(1 for s in auto if s["correct"]) / len(auto), 4) if auto else None,
            "review_rate": round(1 - len(auto) / total, 4) if total else None,
        })
    return rows


def recommend_threshold(samples: list[dict], target_accuracy: float, min_samples: int = 5) -> dict:
    """자동 처리분의 정답률이 목표 이상이 되는 가장 낮은 기준값(=자동 처리 비율이 가장 큰 기준값)."""
    rows = threshold_table(samples, sorted({round(0.5 + 0.01 * i, 2) for i in range(0, 50)}))
    for r in rows:
        if r["auto_count"] >= min_samples and r["auto_accuracy"] is not None and r["auto_accuracy"] >= target_accuracy:
            return {"threshold": r["threshold"], "auto_rate": r["auto_rate"], "auto_accuracy": r["auto_accuracy"],
                    "target_accuracy": target_accuracy, "met": True}
    return {"threshold": None, "target_accuracy": target_accuracy, "met": False,
            "reason": "목표 정답률을 만족하는 기준값이 없습니다. 모든 건을 담당자 확인으로 보내거나 조건·예시를 보완하세요."}


def calibrate(samples: list[dict], target_accuracy: float, edges: list[float], min_samples: int) -> dict:
    groups: dict[str, list[dict]] = {}
    for s in samples:
        if s.get("group"):
            groups.setdefault(s["group"], []).append(s)
    return {
        "count": len(samples),
        "accuracy": round(sum(1 for s in samples if s["correct"]) / len(samples), 4) if samples else None,
        "bins": reliability(samples, edges),
        "ece": ece(samples, edges),
        "thresholds": threshold_table(samples),
        "recommended": recommend_threshold(samples, target_accuracy, min_samples),
        "by_group": {g: {"count": len(xs), "accuracy": round(sum(1 for s in xs if s["correct"]) / len(xs), 4),
                         "bins": reliability(xs, edges)} for g, xs in sorted(groups.items())},
    }
