package dcs;

/**
 * Giao diện bộ co giãn: nhìn trạng thái một tầng, trả về số VM cần thêm (+n), bớt (-n) hoặc giữ (0).
 * Tách khỏi {@link PlacementPolicy}. {@link ScalingController} luôn chặn kết quả theo min/max và số PE vật lý.
 */
public interface Autoscaler {

    int decide(ScaleState state);

    /**
     * @param avgCpu                CPU trung bình (0..1) của các VM đang hoạt động trong tầng, tính trên chu kỳ quan sát vừa qua
     * @param activeVms             số VM đang nhận việc
     * @param pendingVms            số VM đã yêu cầu nhưng chưa khởi động xong
     * @param secondsSinceLastScale thời gian từ lần co giãn gần nhất của tầng này (rất lớn nếu chưa có)
     */
    record ScaleState(Main.Tier tier, double time, int activeVms, int pendingVms, double avgCpu,
                      int minVms, int maxVms, double secondsSinceLastScale) {}

    /** Ngưỡng tĩnh: tăng khi CPU > scaleUpCpu, giảm khi CPU < scaleDownCpu, có cooldown và min/max. */
    final class StaticThreshold implements Autoscaler {
        private final AutoscaleConfig cfg;

        StaticThreshold(AutoscaleConfig cfg) { this.cfg = cfg; }

        @Override
        public int decide(ScaleState s) {
            if (s.secondsSinceLastScale() < cfg.cooldownSec()) return 0;
            if (s.pendingVms() > 0) return 0;                       // đợi VM đang khởi động rồi mới đo lại
            if (s.avgCpu() > cfg.scaleUpCpu()) {
                return Math.max(0, Math.min(cfg.step(), s.maxVms() - s.activeVms()));
            }
            if (s.avgCpu() < cfg.scaleDownCpu()) {
                return -Math.max(0, Math.min(cfg.step(), s.activeVms() - s.minVms()));
            }
            return 0;
        }
    }
}
