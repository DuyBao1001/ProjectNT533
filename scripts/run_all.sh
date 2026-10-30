#!/usr/bin/env bash
# Chạy ma trận: trace (poisson, burst) x chính sách (random, rr, binpack) x co giãn (off, on) x seed 1..N
# Dùng: bash scripts/run_all.sh [N=5] [thư mục kết quả=results]
# Đổi cột của summary.csv thì xóa file này trước khi chạy lại (kết quả được nối thêm vào cuối).
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-5}
OUT=${2:-results}
PY=$(command -v python3 || command -v python)
mvn -q compile
for seed in $(seq 1 "$N"); do
  for kind in poisson burst; do
    "$PY" -I scripts/gen_trace.py --kind "$kind" --seed "$seed" --out "traces/${kind}_s${seed}.csv" >/dev/null
    for pol in random rr binpack; do
      for mode in off on; do
        mvn -q exec:java -Dexec.args="traces/${kind}_s${seed}.csv ${pol} ${seed} ${OUT} ${mode}"
      done
    done
  done
done
echo "Xong. Xem ${OUT}/summary.csv"
