| Trace | Chính sách | n | Δ tỉ lệ trễ deadline | Δ p95 (s) | Δ chi phí VM (%) | Số seed trễ nhiều hơn |
|---|---|---|---|---|---|---|
| Trace Poisson | Random | 5 | +0.012 ± 0.005 | +0.9 ± 0.7 | -21.4 ± 0.5 | 5/5 |
| Trace Poisson | Round-robin | 5 | +0.003 ± 0.002 | -0.1 ± 0.1 | -25.2 ± 0.5 | 5/5 |
| Trace Poisson | Bin-packing | 5 | +0.003 ± 0.003 | +0.2 ± 0.2 | -21.9 ± 0.6 | 3/5 |
| Trace burst | Random | 5 | +0.020 ± 0.012 | +2.0 ± 2.2 | -5.6 ± 1.5 | 5/5 |
| Trace burst | Round-robin | 5 | +0.056 ± 0.008 | +6.7 ± 1.0 | -6.6 ± 2.9 | 5/5 |
| Trace burst | Bin-packing | 5 | +0.049 ± 0.018 | +3.6 ± 0.9 | -7.6 ± 1.3 | 5/5 |

Δ = bật co giãn − tắt co giãn, tính cặp theo từng seed (cùng trace và chính sách), rồi lấy trung bình ± độ lệch chuẩn.
