# cloudsim-edge — đặt tác vụ và co giãn VM trên hệ Cloud-Edge hai tầng (CloudSim Plus)

Đồ án NT533, đề tài 12: Autoscaling & Task Placement trong môi trường Cloud/Edge. Dự án là một simulator viết trên
[CloudSim Plus](https://cloudsimplus.org) (Java) cho hai tầng edge và cloud. Nó nạp một trace tác vụ (Poisson hoặc có đột biến tải),
dùng một trong ba thuật toán đặt tác vụ (Random, Round-robin with Fairness, Bin-packing) và một bộ co giãn VM theo ngưỡng,
rồi xuất các chỉ số (tỉ lệ trễ deadline, latency, chi phí, Jain, CPU/RAM) ra CSV. Python dùng để sinh trace và vẽ biểu đồ.

## 1. Yêu cầu
- JDK 17 trở lên (đã chạy với JDK 25), Maven 3.9+.
- Python 3. Sinh trace chỉ cần thư viện chuẩn; `scripts/analyze.py` cần `pandas`, `numpy`, `matplotlib`.
- Phiên bản CloudSim Plus đặt trong `pom.xml` (thuộc tính `cloudsimplus.version`, hiện 8.5.7).

## 2. Cách chạy (Windows PowerShell)

Chạy từ thư mục gốc của dự án.

**Một thí nghiệm:**
```powershell
python scripts/gen_trace.py --kind burst --seed 1 --out traces/burst_s1.csv
mvn -q compile exec:java "-Dexec.args=traces/burst_s1.csv binpack 1 results on"
```
Đối số của chương trình: `<trace.csv> <random|rr|binpack> <seed> <thư mục kết quả> [on|off]`.
Đối số cuối bật hoặc tắt co giãn (mặc định `off`). Trong PowerShell phải đặt dấu nháy bao cả `-Dexec.args=...`.
Mỗi lần chạy in một dòng tóm tắt, ví dụ `[burst_s1_binpack_seed1_as] finished=2137/2137 miss=0.111 ... vm_cost=24.6 ...`.

**Cả ma trận (2 trace x 3 chính sách x co giãn tắt/bật x N seed), khoảng 7 phút cho N = 5:**
```powershell
Remove-Item results/summary.csv -ErrorAction SilentlyContinue      # kết quả được nối thêm vào cuối, xóa file cũ trước
foreach ($seed in 1..5) { foreach ($kind in "poisson","burst") {
  python scripts/gen_trace.py --kind $kind --seed $seed --out "traces/${kind}_s${seed}.csv"
  foreach ($pol in "random","rr","binpack") { foreach ($mode in "off","on") {
    mvn -q compile exec:java "-Dexec.args=traces/${kind}_s${seed}.csv $pol $seed results $mode"
  } } } }
```
Có Git Bash hoặc WSL thì dùng `bash scripts/run_all.sh 5 results` (cùng việc).

**Phân tích và vẽ biểu đồ:**
```powershell
python scripts/analyze.py results figures
```

Nếu chạy báo `Unresolved compilation problem`, chạy `mvn -q clean compile` rồi chạy lại (VS Code đôi khi biên dịch dở file `.class`).
Cùng một trace, chính sách và seed luôn cho đúng cùng một kết quả.

## 3. Mô hình

Luồng dữ liệu:
```
trace.csv ──► TraceFeeder ──► broker ──► PlacementPolicy chọn VM
                                              ▲
              Autoscaler ◄── ScalingController ──► VmFleet (thêm / bớt / tính tiền VM)
                                              │
                       UtilizationRecorder ◄──┘ (lấy mẫu CPU/RAM)
                                              ▼
                                        ResultWriter ──► summary.csv, *_tasks.csv
```

**Hai tầng** (hằng số trong `Main.java`; đây là giá trị tạm, sẽ hiệu chỉnh theo số đo từ cụm k3s thật):

| | Edge | Cloud |
|---|---|---|
| Host | 2 host x 4 PE x 1000 MIPS | 4 host x 8 PE x 2000 MIPS |
| RAM / băng thông host | 8 GB / 1000 Mbps | 32 GB / 10 000 Mbps |
| Số VM ban đầu | 4 | 8 |
| Giá VM | 0.002 / giây | 0.010 / giây |
| Độ trễ mạng | 5 ms | 80 ms |
| Băng thông đường truyền tới tầng | 100 Mbps | 40 Mbps |

- **VM:** 2 PE, 1024 MB RAM, 100 Mbps, lập lịch space-shared (mỗi VM chạy được 2 tác vụ cùng lúc, tác vụ còn lại xếp hàng).
- **Tác vụ (cloudlet):** 1 PE, độ dài `length_mi` triệu lệnh, dùng 256 MB RAM và 10 Mbps trên VM.
- **Thời gian phản hồi** = `finish − arrival + data_mb*8/băng thông tầng + độ trễ tầng`. Tác vụ trễ deadline nếu phản hồi lớn hơn `deadline_s`.
- **Chi phí:** `vm_cost` = tổng giây-VM x giá/giây của tầng, tính từ lúc yêu cầu VM (kể cả thời gian khởi động) tới lúc hủy hoặc tới lúc tác vụ cuối hoàn thành.
  `total_cost` chỉ tính thời gian chạy của tác vụ.

### Trace (`scripts/gen_trace.py`)
Mỗi dòng: `id, arrival_s, length_mi, deadline_s, data_mb`. File cũ không có cột `data_mb` vẫn đọc được (coi là 0).

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `--rate` | 5 tác vụ/giây | tốc độ đến nền (quá trình Poisson) |
| `--duration` | 300 s | thời lượng trace |
| `--kind burst`: `--burst-start`, `--burst-len`, `--burst-mult` | 120 s, 40 s, x4 | trong 120–160 s tốc độ đến tăng gấp 4 (20 tác vụ/giây) |
| `--mean-mi` | 2000 MI | độ dài tác vụ phân phối mũ (tối thiểu 100) |
| `--deadline-factor` | 6 | `deadline = 6 x (length_mi / 1000) + 1` giây |
| `--mean-data-mb` | 4 MB | dữ liệu mỗi tác vụ, phân phối mũ (tối thiểu 0.1) |
| `--seed` | 1 | quyết định toàn bộ trace |

## 4. Các thuật toán

Mọi thuật toán đặt tác vụ cài giao diện `PlacementPolicy.select(cloudlet, candidates)`, với `candidates` là các VM đang hoạt động
(đã khởi động xong, chưa bị chọn thu hồi). Bộ co giãn cài giao diện riêng `Autoscaler.decide(state)`.

**Random.** Chọn ngẫu nhiên đều một VM trong `candidates` (theo seed).

**Round-robin with Fairness.** Duyệt vòng tròn các VM, chọn VM có (MI đã gán / MIPS) nhỏ nhất; hòa thì lấy VM đến trước theo thứ tự vòng.
Nhờ chia cho MIPS, VM cloud (nhanh gấp đôi) nhận lượng việc gấp đôi VM edge, nên hiệu suất sử dụng các VM xấp xỉ bằng nhau.
VM mới do co giãn vào cuộc với mức tải bằng mức thấp nhất đang có, để không bị dồn hết tác vụ.

**Bin-packing (best-fit theo deadline).** Với mỗi tác vụ, ước lượng thời gian hoàn thành trên VM v là
`backlog(v) / (MIPS x số PE) + độ dài / MIPS`, trong đó `backlog(v)` là số MI đã gán cho v mà chưa xong (cộng lúc gán, trừ khi tác vụ hoàn thành).
Chọn VM **bận nhất** trong số các VM dự kiến xong trong `0.4 x deadline` (nhồi vào ít VM nhất có thể, để các VM còn lại rảnh cho việc thu hồi);
nếu không VM nào thỏa thì chọn VM xong sớm nhất. Hệ số 0.4 chừa dự phòng cho thời gian truyền dữ liệu (chính sách không biết) và sai số ước lượng
(thử trên một seed với 1.0, 0.8, 0.6, 0.4; xếp sát 100% deadline làm miss tăng mạnh).

**Co giãn theo ngưỡng tĩnh** (`AutoscaleConfig.defaults`). Mỗi 10 giây, cho từng tầng, lấy CPU trung bình theo thời gian của các VM đang hoạt động trong chu kỳ vừa qua:
- lớn hơn 0.70: thêm 4 VM; nhỏ hơn 0.30: bớt 4 VM (chọn VM ít việc nhất);
- chờ ít nhất 10 giây giữa hai lần co giãn của một tầng; không co giãn khi còn VM đang khởi động;
- VM mới mất 10 giây khởi động mới nhận việc; VM bị bớt chuyển sang trạng thái draining (không nhận việc mới) và bị hủy khi hết việc;
- giới hạn: edge 2–4 VM (host chỉ đủ chỗ cho 4 VM), cloud 6–16 VM.

Bộ tham số (cooldown 10 s, bước 4, cloud tối thiểu 6) được chọn ở Bước H bằng cách so 4 cấu hình trên 3 chính sách x 2 trace x 3 seed, theo một quy tắc đặt trước
(chi phí thấp nhất trong số cấu hình có miss trên burst không vượt miss lúc tắt quá 0.05); bảng so sánh nằm trong `CLAUDE.md`.

### Độ phức tạp

`n` là số VM đang hoạt động (tối đa 20 trong cấu hình này), `m` là số tác vụ đã gán mà chưa xong.

| Thuật toán | Thời gian mỗi tác vụ | Bộ nhớ thêm | Ghi chú |
|---|---|---|---|
| Random | O(1) | O(1) | |
| Round-robin with Fairness | O(n) | O(n) | duyệt n VM để tìm tải nhỏ nhất |
| Bin-packing | O(n) | O(n + m) | duyệt n VM; mỗi tác vụ chưa xong giữ một listener để trừ backlog |
| Bộ co giãn ngưỡng tĩnh | O(n) mỗi chu kỳ quan sát | O(n) | đo CPU các VM; quyết định là O(1); chọn VM thu hồi O(n) cho mỗi VM |

Với T tác vụ, tổng chi phí tính toán của Round-robin và Bin-packing là O(T x n).

## 5. Chỉ số
- **Deadline miss rate:** tỉ lệ tác vụ có `response > deadline` (tác vụ chưa xong tính là trễ).
- **Latency:** mean và p95 của `response`.
- **Chi phí:** `vm_cost` (xem trên).
- **Jain's Fairness Index** (cột `jain`): `(tổng x)^2 / (n x tổng x^2)` với `x` là *hiệu suất sử dụng* của từng VM
  = `MI đã gán / (MIPS x số PE x số giây VM đủ điều kiện nhận việc mới)`. Chia cho MIPS để VM edge và cloud so sánh được; chia cho thời gian
  đủ điều kiện (từ lúc khởi động xong tới lúc bị chọn thu hồi) để VM do bộ co giãn tạo ra sống ngắn không bị coi là bị chia ít việc.
  1.0 là mọi VM được dùng đều như nhau. Round-robin gần 1.0; Bin-packing cố ý gom tải nên Jain thấp (đây là đánh đổi, không phải lỗi).
- **CPU/RAM:** trung bình theo thời gian của từng tầng (`cpu_edge`, `ram_edge`, `cpu_cloud`, `ram_cloud`); chuỗi theo thời gian trong `_util.csv` (lấy mẫu mỗi 5 giây mô phỏng).

## 6. Kết quả
Mỗi lần chạy ghi vào thư mục kết quả:
- `summary.csv` — một dòng mỗi lần chạy (các chỉ số ở trên, số VM trung bình mỗi tầng, số lần tăng/giảm VM).
- `<trace>_<chính sách>_seed<N>[_as]_tasks.csv` — từng tác vụ (tầng, thời điểm xong, response, trễ deadline, thời gian truyền). Hậu tố `_as` nghĩa là bật co giãn.
- `..._util.csv` — CPU/RAM từng VM và từng tầng theo thời gian.
- `..._scale.csv` — số VM (active, pending, draining), CPU trung bình và quyết định co giãn mỗi chu kỳ.

`analyze.py` ghi vào `figures/`: `summary_table.md` (trung bình ± độ lệch chuẩn giữa seed), `autoscale_effect.md` (bật − tắt co giãn, so cặp theo seed)
và 4 biểu đồ (deadline miss rate, chi phí VM, latency p95, số VM theo thời gian trên trace burst).

**Kết quả chính** (trung bình 5 seed, tắt -> bật co giãn; chi tiết và nhận xét đầy đủ trong `CLAUDE.md` và `figures/`):

| Trace | Chính sách | Tỉ lệ trễ deadline | Latency p95 (s) | Chi phí VM |
|---|---|---|---|---|
| Poisson | Random | 0.025 -> 0.037 | 5.6 -> 6.5 | 26.9 -> 21.2 |
| Poisson | Round-robin | 0.015 -> 0.018 | 4.8 -> 4.7 | 26.7 -> 19.9 |
| Poisson | Bin-packing | 0.008 -> 0.010 | 10.4 -> 10.5 | 27.1 -> 21.1 |
| Burst | Random | 0.169 -> 0.189 | 25.2 -> 27.2 | 27.0 -> 25.5 |
| Burst | Round-robin | 0.096 -> 0.152 | 8.5 -> 15.2 | 26.8 -> 25.0 |
| Burst | Bin-packing | 0.071 -> 0.120 | 12.6 -> 16.2 | 27.3 -> 25.2 |

- Về deadline, thứ hạng Bin-packing <= Round-robin < Random đúng ở 5/5 seed trong cả 4 tổ hợp; Round-robin có latency tốt nhất, Bin-packing cao nhất trên Poisson.
- Co giãn giảm 21–25% chi phí trên Poisson với miss gần như không đổi, nhưng trên burst chỉ giảm 6–8% và làm miss tăng 0.02–0.06: VM mới cần ít nhất 20 giây
  (cửa sổ quan sát 10 s + khởi động 10 s) mới nhận việc, trong khi burst chỉ kéo dài 40 giây.

## 7. Hạn chế đã biết
1. **Chính sách chưa ưu tiên edge và chưa dùng `data_mb`.** Chính sách chỉ biết tầng qua danh sách VM; chưa có quy tắc "ưu tiên edge, tràn sang cloud".
   Bin-packing điền VM edge trước chỉ vì khi hòa nó lấy VM có id nhỏ nhất (edge), không phải quy tắc được thiết kế.
2. **Mô hình băng thông đơn giản.** Thời gian truyền chỉ được cộng vào thời gian phản hồi và vào kiểm tra deadline; nó không làm trễ lúc tác vụ bắt đầu chạy,
   không chiếm tài nguyên VM, và chưa có chi phí truyền dữ liệu.
3. **Cột RAM chưa mang thông tin riêng.** Mỗi tác vụ dùng 1 PE và 256 MB, VM có 2 PE và 1024 MB, nên mức dùng RAM luôn bằng một nửa mức dùng CPU.
4. **Tham số mô hình là giá trị tạm** (giá, độ trễ, băng thông, thời gian khởi động VM, MIPS), chưa hiệu chỉnh theo số đo từ cụm k3s thật.
5. **Bộ co giãn phản ứng chậm với burst ngắn** (xem mục 6); chỉ so 4 cấu hình tham số trên 3 seed, chưa quét tham số rộng hơn. Hệ số 0.4 của Bin-packing chọn trên một seed.
6. **Số seed nhỏ.** n = 5 nên độ lệch chuẩn chỉ là ước lượng thô; "x/5 seed" là đếm các seed cùng chiều, chưa phải kiểm định thống kê.

## 8. Cấu trúc mã (`src/main/java/dcs/`)
- `Main` — hằng số mô hình, dựng datacenter, nối các thành phần.
- `PlacementPolicy` — giao diện chung và ba thuật toán đặt tác vụ. RL sau này cắm vào cùng giao diện.
- `Autoscaler`, `AutoscaleConfig`, `ScalingController` — co giãn VM theo chu kỳ (ngưỡng tĩnh là cài đặt đầu tiên).
- `VmFleet` — vòng đời VM (pending, active, draining, destroyed) và chi phí theo thời gian VM chạy.
- `TraceFeeder` — đưa tác vụ vào broker đúng lúc đến (cần để chính sách thấy được VM thay đổi theo thời gian).
- `UtilizationRecorder`, `ResultWriter` — ghi kết quả.

Các script: `scripts/gen_trace.py` (sinh trace), `scripts/run_all.sh` (ma trận thí nghiệm), `scripts/analyze.py` (bảng và biểu đồ).
Ghi chú thiết kế, bài học khi dùng CloudSim Plus và lịch sử từng bước: xem `CLAUDE.md`.
