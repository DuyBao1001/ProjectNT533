package dcs;

/**
 * Mọi tham số co giãn đặt ở đây (sau này đọc từ file cấu hình ở Bước F).
 *
 * @param enabled         bật/tắt co giãn; tắt thì số VM giữ nguyên nhưng vẫn ghi log và tính chi phí theo thời gian VM chạy
 * @param intervalSec     chu kỳ quan sát tải (giây mô phỏng)
 * @param scaleUpCpu      tăng VM khi CPU trung bình của tầng (trên chu kỳ vừa qua) lớn hơn ngưỡng này
 * @param scaleDownCpu    giảm VM khi CPU trung bình nhỏ hơn ngưỡng này
 * @param vmBootSec       thời gian khởi động VM mới (VM bị tính tiền từ lúc yêu cầu, nhận việc từ lúc khởi động xong)
 * @param cooldownSec     thời gian chờ tối thiểu giữa hai lần co giãn của cùng một tầng
 * @param step            số VM thêm/bớt mỗi lần
 * @param edgeMinVms      số VM tối thiểu của tầng edge
 * @param edgeMaxVms      số VM tối đa của tầng edge (bị chặn thêm bởi số PE của host)
 * @param cloudMinVms     số VM tối thiểu của tầng cloud
 * @param cloudMaxVms     số VM tối đa của tầng cloud
 */
public record AutoscaleConfig(boolean enabled, double intervalSec, double scaleUpCpu, double scaleDownCpu,
                              double vmBootSec, double cooldownSec, int step,
                              int edgeMinVms, int edgeMaxVms, int cloudMinVms, int cloudMaxVms) {

    /**
     * Giá trị mặc định. Edge: 2 host x 4 PE / 2 PE mỗi VM = tối đa 4 VM (đã đầy từ đầu nên edge chỉ giảm rồi tăng lại tới 4).
     * Cloud: 4 host x 8 PE / 2 = tối đa 16 VM.
     *
     * Cooldown 10 s, bước 4 VM và cloud tối thiểu 6 được chọn ở Bước H (3 chính sách x 2 trace x 3 seed, so với 3 cấu hình khác;
     * bảng so sánh trong CLAUDE.md): phản ứng nhanh hơn (cooldown 20 -> 10 s, bước 2 -> 4) và không bớt cloud xuống 4 VM trước
     * burst (tối thiểu 4 -> 6) giảm miss trên burst từ +0.14 xuống +0.04 so với tắt co giãn, đổi lại chi phí tiết kiệm trên burst chỉ còn 6%
     * (so với 18%). Trên Poisson vẫn tiết kiệm 23% chi phí với miss tăng 0.007.
     */
    static AutoscaleConfig defaults(boolean enabled) {
        return new AutoscaleConfig(enabled, 10.0, 0.70, 0.30, 10.0, 10.0, 4, 2, 4, 6, 16);
    }

    int minVms(Main.Tier t) { return t == Main.Tier.EDGE ? edgeMinVms : cloudMinVms; }
    int maxVms(Main.Tier t) { return t == Main.Tier.EDGE ? edgeMaxVms : cloudMaxVms; }
}
