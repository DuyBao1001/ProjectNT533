#!/usr/bin/env python3
"""Sinh trace tác vụ nhân tạo: Poisson hoặc Poisson + burst.

Đầu ra CSV: id,arrival_s,length_mi,deadline_s,data_mb
  - arrival_s : thời điểm tác vụ đến (giây mô phỏng)
  - length_mi : độ dài tác vụ (triệu lệnh)
  - deadline_s: thời hạn tương đối so với lúc đến (giây)
  - data_mb   : dữ liệu cần truyền tới tầng xử lý (MB); file cũ không có cột này vẫn đọc được (coi là 0)

Ví dụ:
  python3 scripts/gen_trace.py --kind poisson --rate 5 --duration 300 --seed 1 --out traces/poisson_s1.csv
  python3 scripts/gen_trace.py --kind burst   --rate 5 --duration 300 --seed 1 --out traces/burst_s1.csv
"""
import argparse
import csv
import random


def arrivals(kind, rate, duration, burst_start, burst_len, burst_mult, rng):
    """Poisson không đồng nhất bằng phương pháp thinning."""
    def lam(t):
        if kind == "burst" and burst_start <= t < burst_start + burst_len:
            return rate * burst_mult
        return rate

    lam_max = rate * (burst_mult if kind == "burst" else 1)
    t = 0.0
    out = []
    while True:
        t += rng.expovariate(lam_max)
        if t >= duration:
            break
        if rng.random() <= lam(t) / lam_max:
            out.append(t)
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--kind", choices=["poisson", "burst"], default="poisson")
    p.add_argument("--rate", type=float, default=5.0, help="tác vụ/giây (nền)")
    p.add_argument("--duration", type=float, default=300.0)
    p.add_argument("--burst-start", type=float, default=120.0)
    p.add_argument("--burst-len", type=float, default=40.0)
    p.add_argument("--burst-mult", type=float, default=4.0)
    p.add_argument("--mean-mi", type=float, default=2000.0, help="độ dài trung bình (MI)")
    p.add_argument("--mean-data-mb", type=float, default=4.0,
                   help="kích thước dữ liệu đầu vào trung bình mỗi tác vụ (MB)")
    p.add_argument("--deadline-factor", type=float, default=6.0,
                   help="deadline = factor * (độ dài / 1000 MIPS) + 1s")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--out", required=True)
    a = p.parse_args()

    rng = random.Random(a.seed)
    # RNG riêng cho kích thước dữ liệu: thêm cột data_mb không làm đổi arrival/length/deadline của cùng seed.
    data_rng = random.Random(a.seed + 1_000_003)
    ts = arrivals(a.kind, a.rate, a.duration, a.burst_start, a.burst_len, a.burst_mult, rng)
    with open(a.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "arrival_s", "length_mi", "deadline_s", "data_mb"])
        for i, t in enumerate(ts):
            length = max(100.0, rng.expovariate(1.0 / a.mean_mi))
            deadline = a.deadline_factor * (length / 1000.0) + 1.0
            data_mb = max(0.1, data_rng.expovariate(1.0 / a.mean_data_mb))
            w.writerow([i, f"{t:.4f}", f"{length:.0f}", f"{deadline:.2f}", f"{data_mb:.2f}"])
    print(f"{a.out}: {len(ts)} tác vụ ({a.kind}, seed={a.seed})")


if __name__ == "__main__":
    main()
