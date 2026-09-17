# -*- coding: utf-8 -*-
"""토픽 분류기 학습 — 사람 라벨(0908_return) + LLM 라벨(0904 PoC) → logreg-gemma512-vN.joblib.

입력  {--labels}/라벨시트*_리뷰_*.xlsx 시트 labels (balance/bug/ui/ops/bm/noise, 1 또는 빈칸)
      --labels 는 여러 폴더를 받는다. 회차가 늘면 폴더만 덧붙인다.
      {--llm} train_llm_labels.json    (9/7 Qwen 라벨 300건. 없으면 --no-llm)
출력  ai/models/logreg-gemma512-{--version}.joblib  {토픽: {model, threshold}, _meta: {...}}
실행  python train_topic_clf.py --version v3

방법
  - 같은 리뷰를 여러 명이 라벨한 행(일치도 측정용 공통분)은 다수결로 한 벌만 남긴다.
  - 임베딩은 classify_reviews.embed 와 같다(Classification 프롬프트, 768→앞 512 절단·재정규화).
  - 토픽마다 로지스틱 회귀(class_weight=balanced). 문턱값은 5겹 교차검증 예측에서 고른다(--threshold-mode).
      prevalence(기본) 예측 건수가 정답 건수에 가장 가까운 값. 화면이 보여주는 것은 리뷰 한 건의 정답 여부가
                       아니라 토픽 언급률이라, 비중이 맞는 쪽을 기본으로 둔다. 같으면 F1 이 높은 쪽.
                       보정은 --calibrate-on 폴더(기본: --labels 의 첫 폴더)의 행으로만 한다. 특정 토픽을
                       노리고 고른 표본으로 보정하면 그 토픽을 과다하게 붙인다.
      f1               F1 이 가장 높은 값. 리뷰 단위 판정을 쓸 때.
    9/16 실측(09-15 리뷰 2만건): 화면 언급률 평균 오차 v1 10.6%p → v2 3.1%p → v3 3.0%p.
    문턱 기준을 F1 최대로 두면 7.3%p 로 나빠진다.
  - LLM 라벨은 학습에만 보태고 검증에는 쓰지 않는다(정답이 아니므로). 사람 라벨만으로 점수를 낸다.

주의: 문턱값과 F1 은 같은 교차검증 예측에서 고른 값이라 낙관 쪽으로 약간 치우친다.
      처음 보는 게임에서는 조금 낮게 나올 수 있다.
"""
import argparse
import json
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold

sys.path.insert(0, str(Path(__file__).parent))
from classify_reviews import MAX_CHARS, TOPICS, embed, load_embedder  # noqa: E402

SEED = 42
THRESHOLD_GRID = np.arange(0.2, 0.801, 0.025)


def load_gold(label_dirs):
    """라벨시트 여러 장 → 리뷰당 한 행(중복은 다수결). 반환: DataFrame(id, lang, review_text, 토픽 5열)"""
    frames = []
    for d0 in label_dirs:
        found = sorted(Path(d0).glob("라벨시트*_리뷰_*.xlsx"))
        if not found:
            sys.exit(f"라벨시트가 없습니다: {d0}")
        for f in found:
            d = pd.read_excel(f, sheet_name="labels")
            d["labeler"] = f"{Path(d0).name}:{f.stem[-1]}"
            frames.append(d)
        print(f"  {Path(d0).name}: 시트 {len(found)}장")
    a = pd.concat(frames, ignore_index=True)
    for t in TOPICS:
        a[t] = a[t].fillna(0).astype(int)
    a["source"] = a.labeler.str.split(":").str[0]
    agg = {t: (lambda s: int(s.mean() >= 0.5)) for t in TOPICS}
    agg.update({"lang": "first", "game": "first", "review_text": "first", "source": "first"})
    g = a.groupby("id").agg(agg).reset_index()
    print(f"사람 라벨 {len(a)}행 → 리뷰 {len(g)}건 (중복은 다수결), "
          f"언어 {g.lang.value_counts().to_dict()}, 양성 { {t: int(g[t].sum()) for t in TOPICS} }")
    return g


def load_llm(path):
    rows = json.load(open(path, encoding="utf-8"))
    X = [r["text"][:MAX_CHARS] for r in rows]
    Y = np.array([[t in r["labels"] for t in TOPICS] for r in rows]).astype(int)
    print(f"LLM 라벨 {len(rows)}건 보탬, 양성 { {t: int(Y[:, i].sum()) for i, t in enumerate(TOPICS)} }")
    return X, Y


def cv_probs(E, Y, extra=None, C=2.0):
    """5겹 교차검증 예측 확률. 검증 폴드는 사람 라벨만, 학습 폴드에만 LLM 라벨을 보탠다."""
    oof = np.zeros(Y.shape, dtype=float)
    for i, _t in enumerate(TOPICS):
        for tr, va in StratifiedKFold(5, shuffle=True, random_state=SEED).split(E, Y[:, i]):
            X, y = E[tr], Y[tr, i]
            if extra is not None:
                X, y = np.vstack([X, extra[0]]), np.concatenate([y, extra[1][:, i]])
            m = LogisticRegression(C=C, max_iter=3000, class_weight="balanced").fit(X, y)
            oof[va, i] = m.predict_proba(E[va])[:, 1]
    return oof


def f1(pred, truth):
    tp = int((pred & truth).sum())
    return 2 * tp / max(1, int(pred.sum()) + int(truth.sum()))


def pick_threshold(prob, truth, mode):
    """교차검증 확률에서 문턱값 하나를 고른다."""
    if mode == "f1":
        return float(max(THRESHOLD_GRID, key=lambda x: f1(prob >= x, truth)))
    n = int(truth.sum())
    # 예측 건수가 정답 건수에 가장 가까운 값, 같으면 F1 이 높은 쪽
    return float(min(THRESHOLD_GRID, key=lambda x: (abs(int((prob >= x).sum()) - n), -f1(prob >= x, truth))))


def main():
    here = Path(__file__).parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", nargs="+",
                    default=[str(here.parent.parent.parent / "0908_return"),
                             str(here.parent.parent.parent / "0916_return")])
    ap.add_argument("--llm", default=str(here.parent.parent.parent / "0904/poc/results/train_llm_labels.json"))
    ap.add_argument("--no-llm", action="store_true", help="사람 라벨만으로 학습")
    ap.add_argument("--version", default="v3")
    ap.add_argument("-C", type=float, default=2.0, help="로지스틱 회귀 정규화 세기")
    ap.add_argument("--threshold-mode", choices=["prevalence", "f1"], default="prevalence")
    ap.add_argument("--calibrate-on", default=None,
                    help="문턱값 보정에 쓸 라벨 폴더 이름. 기본은 --labels 의 첫 폴더(무작위 표본)")
    a = ap.parse_args()

    g = load_gold(a.labels)
    Y = g[TOPICS].values.astype(int)
    model = load_embedder()
    E = embed(model, g.review_text.fillna("").str[:MAX_CHARS].tolist())
    extra = None if a.no_llm else load_llm(a.llm)
    if extra is not None:
        extra = (embed(model, extra[0]), extra[1])

    oof = cv_probs(E, Y, extra, a.C)
    # 보정용 부분집합. 여기 비중이 실제 비중을 대신하므로 무작위로 뽑은 회차여야 한다.
    calib_name = a.calibrate_on or Path(a.labels[0]).name
    cal = (g.source == calib_name).values
    if not cal.any():
        sys.exit(f"보정용 라벨이 없습니다: {calib_name} (있는 값: {sorted(set(g.source))})")
    print(f"문턱값 보정 표본: {calib_name} {int(cal.sum())}건 / 학습 {len(g)}건")

    clf, report = {}, []
    for i, t in enumerate(TOPICS):
        truth = Y[:, i].astype(bool)
        th = pick_threshold(oof[cal, i], truth[cal], a.threshold_mode)
        pred = oof[:, i] >= th
        tp = int((pred & truth).sum())
        report.append({"topic": t, "gold": int(truth.sum()), "pred": int(pred.sum()),
                       "precision": round(tp / max(1, int(pred.sum())), 3),
                       "recall": round(tp / max(1, int(truth.sum())), 3),
                       "f1": round(f1(pred, truth), 3), "threshold": round(th, 3)})
        X, y = E, Y[:, i]
        if extra is not None:
            X, y = np.vstack([E, extra[0]]), np.concatenate([y, extra[1][:, i]])
        clf[t] = {"model": LogisticRegression(C=a.C, max_iter=3000, class_weight="balanced").fit(X, y),
                  "threshold": th}

    allp = np.stack([oof[:, i] >= clf[t]["threshold"] for i, t in enumerate(TOPICS)], 1)
    ally = Y.astype(bool)
    micro = {"all": round(f1(allp, ally), 3)}
    for lang in sorted(g.lang.unique()):
        m = (g.lang == lang).values
        micro[lang] = round(f1(allp[m], ally[m]), 3)
    print(pd.DataFrame(report).to_string(index=False))
    print("micro-F1", micro, "| 리뷰 단위 완전일치", round(float((allp == ally).all(axis=1).mean()), 3))

    clf["_meta"] = {"version": a.version,
                    "trained_on": ", ".join(Path(d).name for d in a.labels) + ("" if a.no_llm else " + 0904 LLM 300"),
                    "gold_reviews": len(g), "C": a.C, "seed": SEED, "threshold_mode": a.threshold_mode,
                    "calibrated_on": f"{calib_name} {int(cal.sum())}건",
                    "micro_f1_cv": micro, "per_topic": report}
    out = here.parent / "models" / f"logreg-gemma512-{a.version}.joblib"
    out.parent.mkdir(exist_ok=True)
    joblib.dump(clf, out)
    print("wrote", out, out.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
