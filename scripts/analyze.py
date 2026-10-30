#!/usr/bin/env python3
"""Phân tích ma trận kết quả: bảng trung bình ± độ lệch chuẩn và biểu đồ.

Dùng:
  python scripts/analyze.py [thư mục kết quả=results] [thư mục ra=figures]

Đọc  : <kết quả>/summary.csv và <kết quả>/<tag>_scale.csv
Ghi  : <ra>/summary_table.csv, summary_table.md      (trung bình ± độ lệch chuẩn theo trace, chính sách, co giãn)
       <ra>/autoscale_effect.csv, autoscale_effect.md (bật - tắt co giãn, so cặp theo từng seed)
       <ra>/fig1_deadline_miss.png, fig2_vm_cost.png, fig3_p95_latency.png, fig4_vms_over_time_burst.png

Độ lệch chuẩn là độ lệch chuẩn mẫu (ddof=1) giữa các seed. Thanh lỗi trên biểu đồ là ± 1 độ lệch chuẩn đó.
"""
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

try:
    sys.stdout.reconfigure(encoding="utf-8")   # in được tiếng Việt trên console Windows
except Exception:
    pass

POLICIES = ["random", "rr", "binpack"]
POLICY_LABEL = {"random": "Random", "rr": "Round-robin", "binpack": "Bin-packing"}
KINDS = ["poisson", "burst"]
KIND_LABEL = {"poisson": "Trace Poisson", "burst": "Trace burst"}
COLOR = {"off": "#4C72B0", "on": "#DD8452"}                # xanh dương = tắt co giãn, cam = bật
MODE_LABEL = {"off": "Tắt co giãn", "on": "Bật co giãn"}
# Cửa sổ burst mặc định của gen_trace.py (--burst-start 120 --burst-len 40).
BURST_START, BURST_END = 120.0, 160.0

METRICS = [   # (cột trong summary.csv, nhãn)
    ("deadline_miss_rate", "Tỉ lệ trễ deadline"),
    ("mean_resp_s", "Latency trung bình (s)"),
    ("p95_resp_s", "Latency p95 (s)"),
    ("vm_cost", "Chi phí VM"),
    ("jain", "Jain"),
    ("cpu_edge", "CPU edge"),
    ("cpu_cloud", "CPU cloud"),
    ("avg_vms_edge", "Số VM edge TB"),
    ("avg_vms_cloud", "Số VM cloud TB"),
    ("scale_ups", "Số lần tăng VM"),
    ("scale_downs", "Số lần giảm VM"),
]


def load_summary(results: Path) -> pd.DataFrame:
    path = results / "summary.csv"
    if not path.exists():
        sys.exit(f"Không thấy {path}. Chạy scripts/run_all.sh trước.")
    df = pd.read_csv(path)
    df["kind"] = df["trace"].str.split("_").str[0]
    df["autoscale"] = df["autoscale"].astype(str)
    incomplete = df[df["finished"] != df["submitted"]]
    if len(incomplete):
        print(f"CẢNH BÁO: {len(incomplete)} lần chạy chưa xong hết tác vụ (finished != submitted).")
    counts = df.groupby(["kind", "policy", "autoscale"]).size()
    if counts.nunique() != 1:
        print("CẢNH BÁO: số lần chạy mỗi nhóm không đều:\n" + counts.to_string())
    return df


def aggregate(df: pd.DataFrame) -> pd.DataFrame:
    cols = [c for c, _ in METRICS]
    g = df.groupby(["kind", "policy", "autoscale"])[cols]
    mean, std = g.mean(), g.std(ddof=1)
    out = mean.add_suffix("_mean").join(std.add_suffix("_std"))
    out.insert(0, "n", g.size())
    out = out.reset_index()
    out["_k"] = out["kind"].map({k: i for i, k in enumerate(KINDS)})
    out["_p"] = out["policy"].map({p: i for i, p in enumerate(POLICIES)})
    out["_a"] = out["autoscale"].map({"off": 0, "on": 1})
    return out.sort_values(["_k", "_p", "_a"]).drop(columns=["_k", "_p", "_a"]).reset_index(drop=True)


def write_markdown_table(agg: pd.DataFrame, path: Path) -> None:
    shown = [("deadline_miss_rate", 3), ("mean_resp_s", 2), ("p95_resp_s", 2), ("vm_cost", 1), ("jain", 3),
             ("cpu_edge", 2), ("cpu_cloud", 2)]
    header = ["Trace", "Chính sách", "Co giãn", "n"] + [dict(METRICS)[c] for c, _ in shown]
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join(["---"] * len(header)) + "|"]
    for _, r in agg.iterrows():
        cells = [KIND_LABEL[r["kind"]], POLICY_LABEL[r["policy"]], MODE_LABEL[r["autoscale"]], str(int(r["n"]))]
        for c, d in shown:
            cells.append(f"{r[c + '_mean']:.{d}f} ± {r[c + '_std']:.{d}f}")
        lines.append("| " + " | ".join(cells) + " |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def autoscale_effect(df: pd.DataFrame) -> pd.DataFrame:
    """Bật - tắt co giãn, so cặp theo seed (cùng trace, cùng chính sách, cùng seed)."""
    cols = ["deadline_miss_rate", "p95_resp_s", "vm_cost"]
    wide = df.pivot_table(index=["kind", "policy", "seed"], columns="autoscale", values=cols).reset_index()
    rows = []
    for (kind, policy), g in wide.groupby(["kind", "policy"]):
        row = {"kind": kind, "policy": policy, "n": len(g)}
        d_miss = g[("deadline_miss_rate", "on")] - g[("deadline_miss_rate", "off")]
        d_p95 = g[("p95_resp_s", "on")] - g[("p95_resp_s", "off")]
        d_cost = (g[("vm_cost", "on")] - g[("vm_cost", "off")]) / g[("vm_cost", "off")] * 100
        row.update(d_miss_mean=d_miss.mean(), d_miss_std=d_miss.std(ddof=1), d_p95_mean=d_p95.mean(),
                   d_p95_std=d_p95.std(ddof=1), d_cost_pct_mean=d_cost.mean(), d_cost_pct_std=d_cost.std(ddof=1),
                   seeds_miss_worse=int((d_miss > 0).sum()))
        rows.append(row)
    out = pd.DataFrame(rows)
    out["_k"] = out["kind"].map({k: i for i, k in enumerate(KINDS)})
    out["_p"] = out["policy"].map({p: i for i, p in enumerate(POLICIES)})
    return out.sort_values(["_k", "_p"]).drop(columns=["_k", "_p"]).reset_index(drop=True)


def write_effect_markdown(eff: pd.DataFrame, path: Path) -> None:
    header = ["Trace", "Chính sách", "n", "Δ tỉ lệ trễ deadline", "Δ p95 (s)", "Δ chi phí VM (%)",
              "Số seed trễ nhiều hơn"]
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join(["---"] * len(header)) + "|"]
    for _, r in eff.iterrows():
        lines.append("| " + " | ".join([
            KIND_LABEL[r["kind"]], POLICY_LABEL[r["policy"]], str(int(r["n"])),
            f"{r['d_miss_mean']:+.3f} ± {r['d_miss_std']:.3f}", f"{r['d_p95_mean']:+.1f} ± {r['d_p95_std']:.1f}",
            f"{r['d_cost_pct_mean']:+.1f} ± {r['d_cost_pct_std']:.1f}", f"{int(r['seeds_miss_worse'])}/{int(r['n'])}",
        ]) + " |")
    lines.append("")
    lines.append("Δ = bật co giãn − tắt co giãn, tính cặp theo từng seed (cùng trace và chính sách), rồi lấy trung bình ± độ lệch chuẩn.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def grouped_bars(agg: pd.DataFrame, metric: str, ylabel: str, title: str, path: Path, fmt: str) -> None:
    """Hai ô (Poisson, burst); mỗi ô nhóm theo chính sách, hai cột tắt/bật co giãn, thanh lỗi = độ lệch chuẩn."""
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    width = 0.38
    for ax, kind in zip(axes, KINDS):
        sub = agg[agg["kind"] == kind]
        for j, mode in enumerate(["off", "on"]):
            vals, errs = [], []
            for p in POLICIES:
                r = sub[(sub["policy"] == p) & (sub["autoscale"] == mode)].iloc[0]
                vals.append(r[metric + "_mean"])
                errs.append(r[metric + "_std"])
            x = np.arange(len(POLICIES)) + (j - 0.5) * width
            bars = ax.bar(x, vals, width, yerr=errs, capsize=3, color=COLOR[mode], label=MODE_LABEL[mode],
                          error_kw={"elinewidth": 1})
            for b, v, e in zip(bars, vals, errs):      # nhãn nằm trên đầu thanh lỗi để không bị che
                ax.annotate(format(v, fmt), (b.get_x() + b.get_width() / 2, v + (0 if np.isnan(e) else e)),
                            ha="center", va="bottom", fontsize=7.5, xytext=(0, 3), textcoords="offset points")
        ax.set_xticks(np.arange(len(POLICIES)))
        ax.set_xticklabels([POLICY_LABEL[p] for p in POLICIES])
        ax.set_title(KIND_LABEL[kind])
        ax.set_ylabel(ylabel)
        ax.set_ylim(bottom=0)
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(axis="y", alpha=0.25)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=2, frameon=False)   # dưới hình, không đè lên cột
    n = int(agg["n"].iloc[0])
    fig.suptitle(f"{title} (trung bình ± độ lệch chuẩn, {n} seed)")
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    fig.savefig(path, dpi=150)
    plt.close(fig)


def vms_over_time(df: pd.DataFrame, results: Path, path: Path) -> None:
    """Số VM đang nhận việc theo thời gian trên trace burst, bật co giãn: trung bình các seed ± độ lệch chuẩn."""
    runs = df[(df["kind"] == "burst") & (df["autoscale"] == "on")]
    scale_files = [results / f"{Path(r['trace']).stem}_{r['policy']}_seed{int(r['seed'])}_as_scale.csv"
                   for _, r in runs.iterrows()]
    if not any(f.exists() for f in scale_files):
        # Ví dụ bản vừa clone: file chi tiết từng lần chạy không nằm trong git. Không ghi đè hình đã có bằng hình rỗng.
        print(f"CẢNH BÁO: không thấy file *_scale.csv trong {results}/ (chạy lại scripts/run_all.sh); bỏ qua {path.name}.")
        return
    fig, axes = plt.subplots(1, len(POLICIES), figsize=(12, 3.8), sharey=True)
    for ax, policy in zip(axes, POLICIES):
        for tier, color in [("CLOUD", "#C44E52"), ("EDGE", "#55A868")]:
            series = []
            for _, r in runs[runs["policy"] == policy].iterrows():
                tag = f"{Path(r['trace']).stem}_{policy}_seed{int(r['seed'])}_as"
                f = results / f"{tag}_scale.csv"
                if not f.exists():
                    continue
                s = pd.read_csv(f)
                s = s[s["tier"] == tier].set_index("time")["active"]
                series.append(s)
            if not series:
                continue
            wide = pd.concat(series, axis=1)                 # hàng = thời điểm quan sát, cột = seed
            mean, std = wide.mean(axis=1), wide.std(axis=1, ddof=1)
            ax.plot(mean.index, mean.values, color=color, lw=1.8, label=f"{tier.capitalize()}")
            ax.fill_between(mean.index, (mean - std).values, (mean + std).values, color=color, alpha=0.18, lw=0)
        ax.axvspan(BURST_START, BURST_END, color="grey", alpha=0.15, lw=0, label="Burst")
        ax.set_title(POLICY_LABEL[policy])
        ax.set_xlabel("Thời gian mô phỏng (s)")
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(alpha=0.25)
    axes[0].set_ylabel("Số VM đang nhận việc")
    axes[0].legend(frameon=False, loc="upper left")
    n = runs.groupby("policy").size().min() if len(runs) else 0
    fig.suptitle(f"Số VM theo thời gian, trace burst, bật co giãn (trung bình ± độ lệch chuẩn, {n} seed)")
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def main() -> None:
    results = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("results")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("figures")
    out.mkdir(parents=True, exist_ok=True)

    df = load_summary(results)
    agg = aggregate(df)
    agg.to_csv(out / "summary_table.csv", index=False, float_format="%.6g")
    write_markdown_table(agg, out / "summary_table.md")

    eff = autoscale_effect(df)
    eff.to_csv(out / "autoscale_effect.csv", index=False, float_format="%.6g")
    write_effect_markdown(eff, out / "autoscale_effect.md")

    grouped_bars(agg, "deadline_miss_rate", "Tỉ lệ tác vụ trễ deadline", "Deadline miss rate",
                 out / "fig1_deadline_miss.png", ".3f")
    grouped_bars(agg, "vm_cost", "Chi phí VM (giây-VM x giá)", "Chi phí VM", out / "fig2_vm_cost.png", ".1f")
    grouped_bars(agg, "p95_resp_s", "Latency p95 (s)", "Latency p95", out / "fig3_p95_latency.png", ".1f")
    vms_over_time(df, results, out / "fig4_vms_over_time_burst.png")

    print(f"Đã đọc {len(df)} lần chạy. Đã ghi vào {out}/:")
    for p in sorted(out.iterdir()):
        print("  ", p.name)
    print()
    print((out / "summary_table.md").read_text(encoding="utf-8"))
    print((out / "autoscale_effect.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
