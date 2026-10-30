package dcs;

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lấy mẫu định kỳ mức dùng CPU/RAM của từng VM và từng tầng, ghi ra CSV
 * (time,tier,vm,cpu,ram; dòng tổng hợp của tầng có vm = ALL, giá trị là trung bình các VM của tầng).
 *
 * CPU lấy từ API của VM; RAM suy ra từ số cloudlet đang chạy (xem sample()).
 * Mẫu được lấy ở tick đồng hồ đầu tiên có thời điểm >= mốc kế tiếp, nên thời điểm thực tế có thể
 * lệch nhẹ so với bội số của chu kỳ. Giá trị là tỉ lệ 0..1.
 */
public class UtilizationRecorder implements AutoCloseable {

    private final List<Vm> vms;
    private final Map<Vm, Main.Tier> tierOf;
    private final double intervalSec;
    private final PrintWriter out;
    private double nextSample = 0;

    // Tổng (qua các mẫu) của trung bình-theo-tầng, để tính trung bình toàn lượt chạy.
    private final double[] cpuSum = new double[Main.Tier.values().length];
    private final double[] ramSum = new double[Main.Tier.values().length];
    private int samples = 0;

    public UtilizationRecorder(CloudSimPlus sim, List<Vm> vms, Map<Vm, Main.Tier> tierOf,
                               double intervalSec, Path file) throws IOException {
        this.vms = vms;
        this.tierOf = tierOf;
        this.intervalSec = intervalSec;
        this.out = new PrintWriter(Files.newBufferedWriter(file));
        out.println("time,tier,vm,cpu,ram");
        sim.addOnClockTickListener(info -> {
            if (info.getTime() >= nextSample) {
                sample(info.getTime());
                // Bỏ qua các mốc đã trôi qua nếu có khoảng trống không có sự kiện.
                nextSample = (Math.floor(info.getTime() / intervalSec) + 1) * intervalSec;
            }
        });
    }

    private void sample(double time) {
        int n = Main.Tier.values().length;
        double[] cpu = new double[n], ram = new double[n];
        int[] count = new int[n];
        for (Vm vm : vms) {
            // Chỉ lấy VM đã được tạo; VM chưa tạo không có mức dùng để báo cáo.
            if (!vm.isCreated()) continue;
            Main.Tier t = tierOf.get(vm);
            double c = vm.getCpuPercentUtilization();
            // Không dùng vm.getRam().getPercentUtilization(): giá trị đó là 100% lúc VM mới tạo và giữ nguyên
            // phần RAM của cloudlet đã xong. RAM tính từ số cloudlet đang chạy x RAM mỗi cloudlet.
            int running = vm.getCloudletScheduler().getCloudletExecList().size();
            double r = Math.min(1.0, running * Main.CLOUDLET_RAM_MB / vm.getRam().getCapacity());
            int k = t.ordinal();
            cpu[k] += c; ram[k] += r; count[k]++;
            out.printf(Locale.ROOT, "%.2f,%s,%d,%.4f,%.4f%n", time, t, vm.getId(), c, r);
        }
        for (Main.Tier t : Main.Tier.values()) {
            int k = t.ordinal();
            if (count[k] == 0) continue;
            double c = cpu[k] / count[k], r = ram[k] / count[k];
            out.printf(Locale.ROOT, "%.2f,%s,ALL,%.4f,%.4f%n", time, t, c, r);
            cpuSum[k] += c; ramSum[k] += r;
        }
        samples++;
    }

    /** CPU trung bình của tầng trên mọi mẫu (0 nếu chưa có mẫu nào). */
    public double meanCpu(Main.Tier t) { return samples == 0 ? 0 : cpuSum[t.ordinal()] / samples; }

    /** RAM trung bình của tầng trên mọi mẫu (0 nếu chưa có mẫu nào). */
    public double meanRam(Main.Tier t) { return samples == 0 ? 0 : ramSum[t.ordinal()] / samples; }

    @Override public void close() { out.close(); }
}
