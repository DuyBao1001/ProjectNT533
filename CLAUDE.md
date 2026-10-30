# Đồ án NT533 – Đề tài 12: Autoscaling & Task Placement (Cloud/Edge)

## Mục tiêu
Xây simulator Cloud-Edge hai tầng trên **CloudSim Plus (Java)**, cài các thuật toán đặt tác vụ và co giãn cơ bản,
rồi so sánh chúng trên trace Poisson và burst. Đây là đồ án môn học, nhóm 3 người, thời hạn 30/11/2026.

## PHẠM VI: BẢN TỐI THIỂU (quan trọng)
Đồ án đã được cắt gọn có chủ đích vì thời gian ngắn. **Đừng làm thêm những gì ngoài danh sách dưới đây**, kể cả khi thấy "nên có".
Nếu thấy cần làm thêm, hỏi người dùng trước.
- **KHÔNG làm RL** (Gymnasium, PPO, DQN, policy server). Chỉ giữ giao diện `PlacementPolicy` sạch.
- **KHÔNG** framework cấu hình: hằng số mô hình để trong mã (`Main`, `AutoscaleConfig.defaults`); khi có số đo từ cụm k3s thì sửa thẳng ở đó.
  (Bước F cũ "file cấu hình JSON/properties" đã BỎ.)
- **KHÔNG** thêm kiểu trace khác ngoài Poisson và burst; **KHÔNG** phân tích độ nhạy nhiều mức tải.
- **KHÔNG** mở rộng thêm mô hình băng thông, chi phí truyền dữ liệu, hay RAM độc lập. Phần băng thông (`data_mb`) và chuỗi CPU/RAM
  (`_util.csv`) đã có sẵn: giữ nguyên, không bỏ, không làm sâu thêm.
- Ưu tiên: chạy đúng, đơn giản, lặp lại được, và so sánh công bằng giữa 3 thuật toán, hơn là nhiều tính năng.

## Quyết định đã chốt
- Chỉ dùng **CloudSim Plus** (bỏ CloudSim, iFogSim, EdgeCloudSim). Java cho simulator, Python cho trace và phân tích.
- Hai tầng edge/cloud; hai loại trace Poisson và burst.
- Baseline: Random, Round-robin with Fairness, Bin-packing + ngưỡng co giãn tĩnh.
- Metric: CPU/RAM utilization, makespan/latency (mean, p95), Jain's Fairness Index, deadline miss rate, chi phí.
- Thí nghiệm cuối: 2 trace x 3 thuật toán x co giãn tắt/bật x 5 seed.
- Cụm k3s thật (mục 1, 2) do người khác trong nhóm làm; không thuộc phần việc của agent này.

## Cấu trúc
- `src/main/java/dcs/Main.java` – hằng số mô hình (`TierSpec`, VM, cloudlet), dựng 2 datacenter, nối các thành phần, chạy.
- `PlacementPolicy.java` – giao diện `select(cloudlet, candidates)` + Random, FairRoundRobin, BinPacking.
- `VmFleet.java` – vòng đời VM (pending, active, draining, destroyed), thêm/bớt VM giữa lúc chạy, chi phí theo thời gian VM chạy.
- `Autoscaler.java` (giao diện `decide(ScaleState) -> {+n,0,-n}` + `StaticThreshold`), `AutoscaleConfig.java` (mọi tham số co giãn),
  `ScalingController.java` (quan sát định kỳ, ghi `<tag>_scale.csv`).
- `TraceFeeder.java` – đưa tác vụ vào broker đúng lúc đến. `UtilizationRecorder.java` – lấy mẫu CPU/RAM, ghi `<tag>_util.csv`.
- `ResultWriter.java` – ghi `<tag>_tasks.csv` và nối một dòng vào `summary.csv`.
- `src/main/resources/logback.xml` – log mức WARN (mức INFO in ~10 000 dòng mỗi lần chạy); không dùng Janino.
- `scripts/gen_trace.py` – sinh trace CSV `id,arrival_s,length_mi,deadline_s,data_mb` (trace 4 cột cũ vẫn đọc được, data = 0).
- `scripts/run_all.sh [N] [thư mục]` – ma trận trace x chính sách x co giãn (off/on) x seed.
- `scripts/analyze.py [kết quả] [ra]` – đọc `results/summary.csv` và `_scale.csv`, ghi bảng (trung bình ± độ lệch chuẩn, tác động co giãn so cặp) và 4 biểu đồ vào `figures/`.
- `traces/` – `burst_s1.csv`, `poisson_s1.csv`. `results/` – `summary.csv`, và mỗi lần chạy có `_tasks.csv`, `_util.csv`, `_scale.csv`.
- `.gitignore` – bỏ `target/`, các file chi tiết từng lần chạy trong `results/` (giữ `summary.csv`), `results_*/`, file IDE/OS/Python và `.claude/settings.local.json`;
  trace và `figures/` được theo dõi. Thư mục chưa phải repo git (chưa `git init`). Muốn đưa file bị bỏ qua vào git: `git add -f <file>`.

## Môi trường (Windows, PowerShell)
- JDK 25 (Temurin), Maven 3.10.0, CloudSim Plus 8.5.7, `maven.compiler.release=17`. `logback-classic` phải cùng phiên bản với
  `logback-core` mà CloudSim Plus kéo theo (1.5.18); lệch phiên bản gây `NoClassDefFoundError: JaninoEventEvaluator`.
- Chạy: `mvn -q compile exec:java "-Dexec.args=traces/burst_s1.csv binpack 1 results on"`
  (đối số: trace, chính sách {random|rr|binpack}, seed, thư mục kết quả, [on|off] bật co giãn, mặc định off;
  trong PowerShell đặt dấu nháy bao cả `-Dexec.args=...`). Bật co giãn thì tên file có hậu tố `_as`. Mỗi lần chạy in một dòng tóm tắt.
- Đổi cột của `summary.csv` thì xóa file này rồi chạy lại (kết quả được nối thêm vào cuối, header cũ sẽ lệch).
- Nếu chạy báo `Unresolved compilation problem`: VS Code đã biên dịch dở file `.class`, Maven tưởng là mới nên bỏ qua; chạy `mvn -q clean compile`.
- Mã nguồn CloudSim Plus để đối chiếu API: `mvn dependency:sources` rồi giải nén `~/.m2/.../cloudsimplus-8.5.7-sources.jar`.
- `scripts/run_all.sh` là bash: trên Windows cần Git Bash hoặc WSL; nếu không có thì viết thêm bản PowerShell/Python tương đương (không bắt buộc sửa logic).

## Bài học khi làm việc với CloudSim Plus (đã gặp thật)
- `Vm`/`Cloudlet` có hashCode phụ thuộc id (id gán lúc submit vào broker): map khóa bằng chúng phải là `IdentityHashMap`,
  nếu không tra không thấy và `datacenterMapper` trả `Datacenter.NULL` (không VM nào được tạo, mô phỏng dừng ở giây 0).
- `broker.setVmMapper` được gọi cho MỌI cloudlet ngay lúc broker bắt đầu; `setSubmissionDelay` chỉ trì hoãn lúc gửi tới datacenter. Muốn
  chính sách thấy VM thay đổi theo thời gian thì phải đưa cloudlet vào broker đúng lúc đến (`TraceFeeder`). Cách nạp cũ còn làm mọi tác vụ trễ 0.1 s.
- `UtilizationModelFull` cho RAM/BW làm mỗi cloudlet chiếm 100% RAM/BW của VM: cloudlet sau bị trì hoãn và không bao giờ được kích hoạt lại.
- `vm.getRam().getPercentUtilization()` không dùng được (100% lúc VM mới tạo, giữ RAM cũ sau khi cloudlet xong); RAM tính từ số cloudlet đang chạy.
- Cảnh báo `Postponing execution of Cloudlet 0 because it couldn't be mapped to any VM` (chỉ trace Poisson, 1 lần) là bình thường: tác vụ đầu đến
  lúc 0.03 s, trước khi VM được tạo (~0.1 s), nên chờ 0.07 s. Không ảnh hưởng kết quả đáng kể.
- Entity tự lặp sự kiện (như `ScalingController`) chỉ được lặp khi còn sự kiện khác, nếu không mô phỏng không bao giờ kết thúc.

## Trạng thái (30/10/2026): xong toàn bộ các bước A–E, G1, G2, H, I, J
| Bước | Nội dung | Kết quả chính |
|---|---|---|
| A | Sửa lỗi mô phỏng dừng ở giây 0 | Hai lỗi: map theo hashCode của Vm; cloudlet chiếm 100% RAM/BW. Nay `finished = submitted`, mô phỏng kết thúc ~300 s |
| B | So sánh 3 chính sách | Cả hai tầng đều nhận việc; miss tăng khi burst; rr tốt nhất, random tệ nhất (xem hạn chế) |
| C | Băng thông mạng | Cột `data_mb`, `netMbps` mỗi tầng (edge 100, cloud 40, giá trị tạm), thời gian truyền = `data_mb*8/netMbps + latency`, cộng vào response và deadline |
| D | CPU/RAM theo thời gian | `_util.csv` mỗi 5 s, cột `cpu_edge, ram_edge, cpu_cloud, ram_cloud` trong summary |
| E | Autoscaler theo chu kỳ | Ngưỡng tĩnh qua giao diện `Autoscaler`; `vm_cost` theo thời gian VM chạy; `_scale.csv`; bật/tắt bằng đối số thứ 5 |
| G1 | Bin-packing khác Round-robin thật | Backlog thật + best-fit theo deadline (mô tả dưới); gom tải thật, miss không tệ hơn rr; `random`/`rr` giữ nguyên số liệu |
| G2 | Định nghĩa lại Jain | Jain trên hiệu suất sử dụng từng VM (định nghĩa dưới); chỉ cột `jain` đổi, mọi cột khác giữ nguyên |
| H | Chỉnh nhẹ tham số co giãn | Thử 4 cấu hình x 3 chính sách x 2 trace x 3 seed; chọn D (cooldown 10 s, bước 4, cloud tối thiểu 6) theo quy tắc đặt trước; bảng dưới |
| I | Ma trận cuối + phân tích | 60 lần chạy (5 seed), `scripts/analyze.py` -> `figures/` (2 bảng, 4 biểu đồ); nhận xét ở dưới |
| J | Tài liệu | `README.md` đầy đủ (cách chạy PowerShell, mô hình, thuật toán, độ phức tạp, tham số trace, hạn chế) |

**Bước H – các cấu hình đã thử** (trung bình 3 chính sách x seed 1-3; miss, Δ = hiệu so với tắt co giãn; chi phí = `vm_cost`, % tiết kiệm so với tắt). Các thông số còn lại giữ nguyên
(chu kỳ 10 s, ngưỡng 0.70/0.30, khởi động VM 10 s, edge 2..4, cloud tối đa 16). Quy tắc chọn đặt TRƯỚC khi chạy: lấy cấu hình chi phí thấp nhất trong số các cấu hình có miss trung bình
trên burst không vượt miss lúc tắt quá 0.05 (tuyệt đối); nếu không cấu hình nào đạt thì lấy cấu hình có miss thấp nhất. Chỉ D thỏa (0.163 <= 0.123 + 0.05).

| cấu hình | cooldown | bước | cloud min | burst: miss (Δ) | burst: chi phí | Poisson: miss (Δ) | Poisson: chi phí |
|---|---|---|---|---|---|---|---|
| tắt co giãn | – | – | – | 0.123 | 26.9 | 0.014 | 27.0 |
| A (mặc định cũ) | 20 s | 2 | 4 | 0.267 (+0.144) | 22.1 (-18%) | 0.030 (+0.016) | 17.0 (-37%) |
| B (phản ứng nhanh) | 10 s | 4 | 4 | 0.239 (+0.116) | 22.3 (-17%) | 0.031 (+0.017) | 15.6 (-42%) |
| C (giữ nền cao) | 20 s | 2 | 6 | 0.193 (+0.070) | 24.2 (-10%) | 0.021 (+0.007) | 20.8 (-23%) |
| **D (chọn)** | 10 s | 4 | 6 | **0.163 (+0.040)** | 25.3 (-6%) | **0.021 (+0.007)** | 20.8 (-23%) |

D nhất quán: trên burst, Δmiss so với tắt co giãn ở cả 9 cặp (chính sách, seed) nằm trong +0.007..+0.057 (random khoảng +0.02, rr và binpack khoảng +0.05); nhiễu giữa các seed của chính mức tắt co giãn là
±0.03. Đánh đổi cần nói rõ trong báo cáo: co giãn luôn tốn thêm miss; D chọn giữ miss thấp và chỉ tiết kiệm 6% chi phí trên burst (23% trên Poisson); A/B tiết kiệm nhiều hơn nhưng miss gấp đôi.
Phản ứng nhanh (B so với A) giúp được ở cả hai mặt (miss thấp hơn, chi phí gần bằng); giữ nền cloud cao (C so với A) là yếu tố giảm miss mạnh nhất. Chỉ so 4 cấu hình trên 3 seed, không phải quét tham số;
chưa thử đổi ngưỡng 0.70/0.30, chu kỳ quan sát hay thời gian khởi động VM (số sau sẽ do cụm k3s đo).

**Jain (sau G2):** `x` của mỗi VM = `MI đã gán / (MIPS x số PE x số giây VM đủ điều kiện nhận việc mới)`, lấy mọi VM từng khởi động xong. Mẫu số tính từ lúc VM khởi động
xong tới lúc bị chọn thu hồi (không tính thời gian khởi động và thời gian draining, vì lúc đó VM chỉ làm nốt việc cũ). Tính cả thời gian draining thì Jain của `rr`
bật co giãn chỉ đạt 0.78 trong khi chỉ các VM sống suốt đã đạt 0.998: phần chênh đó do quyết định thu hồi chứ không do chính sách bất công. Kết quả seed 1
(tắt co giãn): `rr` 1.000 (cân bằng theo MI/MIPS đúng thiết kế), `random` 0.88-0.89 (khớp lý thuyết: 4 VM edge nhận cùng số tác vụ nhưng chỉ có nửa công suất), binpack 0.44-0.69
(gom tải nên thấp, là đánh đổi chứ không phải lỗi). Bật co giãn (tham số sau Bước H): `rr` 0.90 (burst) / 0.96 (Poisson), `random` 0.81 / 0.88, binpack 0.54 / 0.34 (trước G2 rr chỉ 0.48-0.51).

**Bin-packing (sau G1):** với mỗi tác vụ, ước lượng thời gian hoàn thành trên VM v = `backlog(v)/(MIPS x PE) + độ dài/MIPS`, trong đó `backlog(v)` là MI đã gán
cho v mà chưa xong (cộng lúc gán, trừ khi cloudlet hoàn thành qua `addOnFinishListener`). Chọn VM **bận nhất** trong số các VM dự kiến xong trong `HEADROOM x deadline`
(`HEADROOM = 0.4`); nếu không VM nào thỏa thì chọn VM xong sớm nhất. Ước lượng bảo thủ và bỏ qua thời gian truyền dữ liệu (chính sách không dùng `data_mb`).
Hòa thì lấy VM id nhỏ nhất, nên edge (id 0-3, rẻ hơn) được lấp trước: edge nhận 1129/2137 tác vụ burst (rr: 400) và 1001/1462 Poisson (rr: 306). Đây là hệ quả
của cách xếp, không phải quy tắc "ưu tiên edge" được thiết kế.
Chọn `HEADROOM` (seed 1, miss Poisson / burst khi tắt co giãn): 1.0 -> 0.071 / 0.249; 0.8 -> 0.023 / 0.097; 0.6 -> 0.013 / 0.065; 0.4 -> 0.005 / 0.056 (rr: 0.006 / 0.075).
Xếp sát 100% deadline làm mọi sai số ước lượng (thời gian truyền, tính bảo thủ) thành tác vụ trễ. Mới thử trên một seed, chưa tinh chỉnh thêm.
Bằng chứng gom tải (CPU trung bình mỗi VM, Poisson, tắt co giãn): rr 0.20-0.30 đều trên 12 VM; binpack 0.81 0.73 0.63 0.42 0.90 0.23 rồi 6 VM bằng 0.00.

**Tham số co giãn mặc định** (`AutoscaleConfig.defaults`): chu kỳ 10 s (CPU trung bình theo thời gian trên chu kỳ), tăng khi > 0.70, giảm khi < 0.30,
cooldown 10 s, bước 4 VM, khởi động VM 10 s, edge 2..4 VM (host chỉ đủ chỗ cho 4 VM), cloud 6..16 VM (chọn ở Bước H; trước đó cooldown 20 s, bước 2, cloud 4..16). VM bị bớt chuyển sang "draining"
(không nhận việc mới) rồi bị hủy khi hết việc. VM mới vào cuộc với tải bằng mức thấp nhất đang có (tải của chính sách là cộng dồn).

**Cột `summary.csv`:** `trace,policy,seed,submitted,finished,deadline_miss_rate,mean_resp_s,p95_resp_s,total_cost,jain,cpu_edge,ram_edge,cpu_cloud,ram_cloud,`
`autoscale,vm_cost,avg_vms_edge,avg_vms_cloud,scale_ups,scale_downs`. `total_cost` chỉ tính thời gian chạy tác vụ; `vm_cost` = giây-VM x giá tầng,
tính từ lúc yêu cầu VM tới lúc hủy hoặc tới lúc tác vụ cuối hoàn thành.

**Kết quả cuối (Bước I)**: ma trận 2 trace x 3 chính sách x co giãn tắt/bật x 5 seed = 60 lần, tham số co giãn sau Bước H. Bảng đầy đủ (trung bình ± độ lệch chuẩn giữa seed):
`figures/summary_table.md` / `.csv`; tác động của co giãn so cặp theo seed: `figures/autoscale_effect.md`; biểu đồ `figures/fig1_deadline_miss.png`, `fig2_vm_cost.png`, `fig3_p95_latency.png`,
`fig4_vms_over_time_burst.png`. Tái tạo: `bash scripts/run_all.sh 5 results` rồi `python scripts/analyze.py` (xóa `results/summary.csv` cũ trước khi chạy lại). Số chính, trung bình 5 seed
(tắt -> bật co giãn):

| trace | policy | miss | p95 (s) | vm_cost |
|---|---|---|---|---|
| Poisson | random | 0.025 -> 0.037 | 5.6 -> 6.5 | 26.9 -> 21.2 |
| Poisson | rr | 0.015 -> 0.018 | 4.8 -> 4.7 | 26.7 -> 19.9 |
| Poisson | binpack | 0.008 -> 0.010 | 10.4 -> 10.5 | 27.1 -> 21.1 |
| burst | random | 0.169 -> 0.189 | 25.2 -> 27.2 | 27.0 -> 25.5 |
| burst | rr | 0.096 -> 0.152 | 8.5 -> 15.2 | 26.8 -> 25.0 |
| burst | binpack | 0.071 -> 0.120 | 12.6 -> 16.2 | 27.3 -> 25.2 |

**Nhận xét dựa trên số liệu** (n = 5 seed nên độ lệch chuẩn chỉ là ước lượng thô; "x/5 seed" là đếm so cặp theo seed):
1. *Chính sách.* Thứ hạng miss ổn định: binpack <= rr < random, đúng ở 5/5 seed trong cả 4 tổ hợp (trace x co giãn). Chênh binpack-rr nhỏ (Poisson tắt co giãn -0.008 ± 0.004; burst -0.025 ± 0.017),
   chênh rr-random lớn hơn (burst tắt -0.073 ± 0.005). Về latency, rr tốt nhất (p95 4.8 s Poisson, 8.5 s burst), binpack cao nhất ở Poisson (10.4 s) vì cố ý để tác vụ chờ khi còn dư deadline, random tệ nhất
   ở burst (p95 25 s, độ lệch chuẩn lớn 7 s).
2. *Tác động của co giãn.* Poisson: giảm chi phí 21-25% (random -21.4 ± 0.5%, rr -25.2 ± 0.5%, binpack -21.9 ± 0.6%), miss chỉ tăng +0.003..+0.012, p95 gần như không đổi với rr và binpack.
   Burst: chỉ giảm 5.6-7.6% chi phí, miss tăng +0.020 (random) đến +0.056 (rr), p95 tăng 2.0-6.7 s, và trễ nhiều hơn ở 5/5 seed. Co giãn đáng giá khi tải đều, ít đáng giá với burst ngắn.
3. *Vì sao co giãn kém trên burst (cấu trúc, không ngẫu nhiên).* Ở cả 5 seed (rr), CPU cloud nhảy từ 0.31-0.40 (giây 120) lên 0.88-0.90 (lần quan sát giây 130) và quyết định +4 VM được đưa ra ngay
   lúc đó, nhưng VM mới chỉ nhận việc sau thời gian khởi động 10 s (giây ~150); lượt tăng thứ hai (lên 14 VM) tới sau burst (cực đại giây 170-180, burst kết thúc giây 160), xem `fig4`. Độ trễ phản ứng
   >= 20 s (cửa sổ quan sát 10 s + khởi động 10 s) so với burst dài 40 s: một nửa burst chạy với công suất cũ.
4. *Bin-packing gom tải nhưng không rẻ hơn rr khi bật co giãn* (Poisson 21.1 so với 19.9). Số VM cloud trung bình bằng nhau ở mọi chính sách (6.13) nên chênh chi phí đến từ edge: rr giữ 2.13 VM edge,
   binpack 3.71, random 3.87. rr cân bằng theo MI/MIPS nên CPU edge = cloud (0.36), còn binpack/random dồn thêm tải vào edge (0.56 / 0.49) nên CPU trung bình tầng edge vượt ngưỡng giảm 0.30 và edge không bị bớt.
   Gom tải của binpack rơi vào tầng edge rẻ, không giải phóng được VM cloud (đã có sàn 6 VM từ Bước H). (Diễn giải từ số VM và CPU, chưa thử cô lập riêng.)
5. *Jain* (định nghĩa ở trên): rr 1.000 khi tắt co giãn, random 0.88, binpack 0.44-0.69 (cố ý gom tải); bật co giãn thấp hơn (rr 0.90-0.96).

**Hạn chế đã biết:**
1. ~~`binpack` gần như trùng `rr`~~ **→ đã xử lý ở Bước G1** (nguyên nhân đã kiểm chứng bằng số đo: binpack cũ trải CPU 0.21-0.42 đều trên 12 VM như rr).
   Còn lại: binpack mới có độ trễ trung bình cao hơn rr (Poisson 3.36 so với 2.01 s) vì cố ý để tác vụ chờ khi còn dư deadline; `rr` vẫn dùng tải cộng dồn
   (chấp nhận được cho baseline cân bằng).
2. ~~Jain tính trên MI thô, `random` cao hơn `rr`, và tụt ~0.5 khi co giãn~~ **→ đã xử lý ở Bước G2** (định nghĩa mới ở trên). Còn lại: Jain bật co giãn vẫn thấp hơn
   tắt (rr 0.86-0.95 so với 1.000) do VM sống ngắn có cửa sổ tính ngắn nên nhiễu hơn; chấp nhận được, không làm sâu thêm.
3. Chưa có quy tắc "ưu tiên edge, tràn sang cloud"; chính sách chỉ biết tầng qua danh sách VM và chưa dùng `data_mb`. **→ ghi vào báo cáo như hạn chế, không làm.**
4. Băng thông chỉ cộng vào response, không làm trễ lúc bắt đầu chạy, không chiếm tài nguyên VM; chưa có chi phí truyền dữ liệu. **→ ghi vào báo cáo, không làm.**
5. RAM luôn bằng CPU/2 (mỗi cloudlet 1 PE, 256 MB, VM 2 PE, 1024 MB) nên cột RAM chưa mang thông tin riêng. **→ ghi vào báo cáo, không làm.**
6. ~~Mới chạy seed 1~~ **→ Bước I đã chạy 5 seed**; n = 5 nên độ lệch chuẩn chỉ là ước lượng thô, chưa kiểm định thống kê chính thức (chỉ đếm số seed cùng chiều).

## NHIỆM VỤ TIẾP THEO (làm lần lượt; xong bước nào chạy thật, cho người dùng xem số liệu, ghi vào "Trạng thái"): không còn bước nào chờ làm

### Bước G1. ✔ XONG (30/10/2026) – xem bảng Trạng thái và mô tả Bin-packing ở trên.

### Bước G2. ✔ XONG (30/10/2026) – xem định nghĩa Jain ở bảng Trạng thái; mô tả trong mã (`ResultWriter.vmUtilizations`) và README.

### Bước H. ✔ XONG (30/10/2026) – cấu hình D làm mặc định, bảng các cấu hình đã thử ở mục Trạng thái; lý do ghi trong `AutoscaleConfig.defaults`.

### Bước I. ✔ XONG (30/10/2026) – ma trận 60 lần, `scripts/analyze.py`, bảng và biểu đồ trong `figures/`; kết quả và nhận xét ở mục Trạng thái.

### Bước J. ✔ XONG (30/10/2026) – `README.md` đầy đủ: cách chạy (PowerShell, đã chạy thử đúng các lệnh), mô hình hai tầng và tham số, 3 thuật toán + co giãn, bảng độ phức tạp, tham số trace, chỉ số, kết quả chính, hạn chế.

**Nhiệm vụ của agent đã hoàn thành đến hết Bước J.** Việc còn lại thuộc nhóm (xem mục dưới) hoặc cần người dùng yêu cầu thêm.

## Việc ngoài nhiệm vụ của agent này (chỉ để biết bức tranh)
- Mục 1, 2: cụm k3s thật và kiểm thử tải (người khác phụ trách). Số đo từ đó (thời gian khởi động pod, độ trễ) dùng để chỉnh hằng số trong mã.
- Sơ đồ kiến trúc, báo cáo, demo (nhóm tự làm).

## Quy ước
- Trả lời và comment bằng tiếng Việt.
- Mọi thuật toán đặt tác vụ đi qua `PlacementPolicy`; mọi kết quả xuất cùng định dạng CSV để script Python vẽ biểu đồ dùng chung.
- Không đổi định dạng trace CSV hiện có khi chưa báo; nếu thêm cột phải đọc được file cũ.
- Sau mỗi bước: chạy thật, cho người dùng xem số liệu, ghi lại thay đổi vào mục "Trạng thái".
- Không kết luận chung từ một seed; số liệu đưa vào báo cáo phải từ ma trận nhiều seed (Bước I).