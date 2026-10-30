package dcs;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Giao diện chung cho mọi thuật toán đặt tác vụ (baseline và RL sau này đều cắm vào đây).
 * Tải được ước lượng bằng tổng MI đã gán cho mỗi VM (xấp xỉ, đủ cho baseline).
 */
public interface PlacementPolicy {

    /**
     * Chọn VM cho tác vụ.
     *
     * @param candidates các VM đang hoạt động (đã tạo xong, không đang bị thu hồi), sắp theo id; danh sách này thay đổi
     *                   khi autoscaler thêm/bớt VM. Rỗng thì trả {@link Vm#NULL} (broker sẽ hoãn tác vụ).
     */
    Vm select(Cloudlet cloudlet, List<Vm> candidates);

    /** @param deadlineOf deadline tương đối của tác vụ, chỉ Bin-packing dùng */
    static PlacementPolicy byName(String name, long seed, ToDoubleFunction<Cloudlet> deadlineOf) {
        return switch (name) {
            case "random" -> new RandomPolicy(seed);
            case "rr"     -> new FairRoundRobinPolicy();
            case "binpack"-> new BinPackingPolicy(deadlineOf);
            default -> throw new IllegalArgumentException("Chính sách không hợp lệ: " + name);
        };
    }

    /** Random Placement: O(1) mỗi tác vụ. */
    final class RandomPolicy implements PlacementPolicy {
        private final Random rng;
        RandomPolicy(long seed) { this.rng = new Random(seed); }
        public Vm select(Cloudlet c, List<Vm> candidates) {
            return candidates.isEmpty() ? Vm.NULL : candidates.get(rng.nextInt(candidates.size()));
        }
    }

    /**
     * Nền chung cho các chính sách theo dõi tải đã gán (MI) của từng VM.
     * Map theo danh tính (IdentityHashMap) vì hashCode của Vm phụ thuộc id.
     */
    abstract class LoadTracking implements PlacementPolicy {
        protected final Map<Vm, Double> load = new IdentityHashMap<>();

        /**
         * VM mới xuất hiện (do autoscaler) vào cuộc với tải tương đương mức (tải/MIPS) thấp nhất đang có,
         * nếu không nó có tải 0 và bị dồn hết tác vụ cho tới khi các VM cũ bắt kịp.
         */
        protected void track(List<Vm> candidates) {
            double minScore = Double.MAX_VALUE;
            for (Vm v : candidates) {
                Double l = load.get(v);
                if (l != null) minScore = Math.min(minScore, l / v.getMips());
            }
            double base = minScore == Double.MAX_VALUE ? 0 : minScore;
            for (Vm v : candidates) load.putIfAbsent(v, base * v.getMips());
        }
    }

    /**
     * Round-robin with Fairness: duyệt vòng tròn nhưng chọn VM có
     * (tải đã gán / MIPS) nhỏ nhất, hòa thì theo thứ tự vòng. O(n) mỗi tác vụ.
     */
    final class FairRoundRobinPolicy extends LoadTracking {
        private int next = 0;
        public Vm select(Cloudlet c, List<Vm> candidates) {
            if (candidates.isEmpty()) return Vm.NULL;
            track(candidates);
            Vm best = null; double bestScore = Double.MAX_VALUE; int n = candidates.size();
            for (int k = 0; k < n; k++) {
                Vm v = candidates.get((next + k) % n);
                double score = load.get(v) / v.getMips();
                if (score < bestScore) { bestScore = score; best = v; }
            }
            next = (candidates.indexOf(best) + 1) % n;
            load.merge(best, (double) c.getLength(), Double::sum);
            return best;
        }
    }

    /**
     * Bin-packing best-fit theo deadline: coi "dung lượng" của mỗi VM là thời hạn của tác vụ. Với mỗi tác vụ,
     * ước lượng thời gian hoàn thành trên VM v = backlog(v) / (MIPS x số PE) + độ dài / MIPS, trong đó backlog(v) là MI đã
     * gán cho v mà chưa xong (cộng lúc gán, trừ khi cloudlet hoàn thành). Chọn VM BẬN NHẤT trong số các VM vẫn kịp deadline
     * (nhồi vào ít VM nhất có thể, các VM còn lại rảnh để autoscaler thu hồi); nếu không VM nào kịp thì chọn VM xong sớm nhất.
     * Ước lượng bảo thủ (coi hàng đợi chạy trên cả các PE) và bỏ qua thời gian truyền dữ liệu. O(n) mỗi tác vụ, n là số VM.
     */
    final class BinPackingPolicy implements PlacementPolicy {
        /**
         * Chỉ nhận tác vụ vào VM nếu dự kiến xong trong HEADROOM x deadline. Chừa phần còn lại làm dự phòng cho thời gian truyền
         * dữ liệu (chính sách không biết) và sai số ước lượng; xếp sát 100% deadline thì sai số nhỏ nào cũng làm tác vụ trễ
         * (thử trên seed 1: 1.0 -> miss 0.071 Poisson / 0.249 burst; 0.8 -> 0.023 / 0.097; 0.6 -> 0.013 / 0.065; 0.4 -> 0.005 / 0.056).
         * Chọn 0.4: miss không tệ hơn Round-robin mà vẫn gom tải (khoảng một nửa số VM nhàn rỗi khi tải nhẹ).
         */
        static final double HEADROOM = 0.4;
        /** Deadline tương đối (giây, tính từ lúc tác vụ đến) của một tác vụ, tra từ trace. */
        private final ToDoubleFunction<Cloudlet> deadlineOf;
        private final Map<Vm, Double> backlogMi = new IdentityHashMap<>();

        BinPackingPolicy(ToDoubleFunction<Cloudlet> deadlineOf) { this.deadlineOf = deadlineOf; }

        public Vm select(Cloudlet c, List<Vm> candidates) {
            if (candidates.isEmpty()) return Vm.NULL;
            double deadline = HEADROOM * deadlineOf.applyAsDouble(c);
            Vm busiestFit = null; double busiestBacklog = -1;
            Vm earliest = null; double earliestFinish = Double.MAX_VALUE;
            for (Vm v : candidates) {
                double backlog = backlogMi.getOrDefault(v, 0.0);
                double finish = backlog / (v.getMips() * v.getPesNumber()) + c.getLength() / v.getMips();
                if (finish <= deadline && backlog > busiestBacklog) { busiestBacklog = backlog; busiestFit = v; }
                if (finish < earliestFinish) { earliestFinish = finish; earliest = v; }
            }
            Vm pick = busiestFit != null ? busiestFit : earliest;
            double mi = c.getLength();
            backlogMi.merge(pick, mi, Double::sum);
            c.addOnFinishListener(info -> backlogMi.merge(pick, -mi, Double::sum));
            return pick;
        }
    }
}
