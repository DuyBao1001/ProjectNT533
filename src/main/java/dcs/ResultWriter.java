package dcs;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ghi kết quả một lần chạy: {@code <tag>_tasks.csv} (từng tác vụ), một dòng nối thêm vào {@code summary.csv}
 * và một dòng tóm tắt ra màn hình. Đổi cột của summary thì phải xóa summary.csv cũ.
 */
final class ResultWriter {

    private static final String SUMMARY_HEADER =
            "trace,policy,seed,submitted,finished,deadline_miss_rate,mean_resp_s,p95_resp_s,total_cost,jain,"
            + "cpu_edge,ram_edge,cpu_cloud,ram_cloud,autoscale,vm_cost,avg_vms_edge,avg_vms_cloud,scale_ups,scale_downs";

    /** Chỉ số tính từ các tác vụ đã xong. Tác vụ chưa xong được tính là trễ deadline. */
    private record Metrics(int submitted, int finished, double missRate, double meanResp, double p95Resp,
                           double execCost, double jain) {}

    private ResultWriter() {}

    static void write(Main.RunConfig run, DatacenterBroker broker, VmFleet fleet, Map<Integer, Main.TraceRow> rowOf,
                      UtilizationRecorder util, ScalingController scaler, double endTime) throws IOException {
        Metrics m = writeTasks(run, broker, fleet, rowOf, endTime);

        // Chi phí theo thời gian VM thực sự chạy (execCost chỉ tính thời gian thực thi tác vụ).
        double vmCost = fleet.vmCost(endTime);
        double avgVmsEdge = fleet.vmSeconds(Main.Tier.EDGE, endTime) / endTime;
        double avgVmsCloud = fleet.vmSeconds(Main.Tier.CLOUD, endTime) / endTime;

        Path summary = run.outDir().resolve("summary.csv");
        boolean header = !Files.exists(summary);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(summary,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (header) w.println(SUMMARY_HEADER);
            w.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%.4f,%.3f,%.3f,%d,%d%n",
                    run.trace().getFileName(), run.policy(), run.seed(), m.submitted(), m.finished(),
                    m.missRate(), m.meanResp(), m.p95Resp(), m.execCost(), m.jain(),
                    util.meanCpu(Main.Tier.EDGE), util.meanRam(Main.Tier.EDGE),
                    util.meanCpu(Main.Tier.CLOUD), util.meanRam(Main.Tier.CLOUD),
                    run.autoscale() ? "on" : "off", vmCost, avgVmsEdge, avgVmsCloud,
                    scaler.scaleUps(), scaler.scaleDowns());
        }
        System.out.printf(Locale.ROOT,
                "[%s] finished=%d/%d miss=%.3f mean=%.3fs p95=%.3fs cost=%.3f jain=%.3f "
                        + "vm_cost=%.3f avgVMs(edge/cloud)=%.2f/%.2f scale(+%d/-%d)%n",
                run.tag(), m.finished(), m.submitted(), m.missRate(), m.meanResp(), m.p95Resp(), m.execCost(),
                m.jain(), vmCost, avgVmsEdge, avgVmsCloud, scaler.scaleUps(), scaler.scaleDowns());
    }

    private static Metrics writeTasks(Main.RunConfig run, DatacenterBroker broker, VmFleet fleet,
                                      Map<Integer, Main.TraceRow> rowOf, double endTime) throws IOException {
        int finished = broker.getCloudletFinishedList().size();
        double[] resp = new double[finished];
        double execCost = 0, sumResp = 0;
        int missed = 0, i = 0;
        Map<Vm, Double> busyMi = new IdentityHashMap<>();     // MI đã xử lý bởi mỗi VM, dùng cho Jain
        for (Vm v : fleet.allVms()) busyMi.put(v, 0.0);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(run.outDir().resolve(run.tag() + "_tasks.csv")))) {
            w.println("id,tier,arrival,finish,response_s,deadline_s,missed,cost,transfer_s");
            for (Cloudlet c : broker.getCloudletFinishedList()) {
                Main.TraceRow r = rowOf.get((int) c.getId());
                Main.Tier tier = fleet.tierOf(c.getVm());
                Main.TierSpec spec = Main.specOf(tier);
                double transfer = spec.transferSec(r.dataMb());
                double response = c.getFinishTime() - r.arrival() + transfer;
                double cost = c.getTotalExecutionTime() * spec.costPerVmSecond();
                boolean miss = response > r.deadline();
                if (miss) missed++;
                execCost += cost;
                sumResp += response;
                resp[i++] = response;
                busyMi.merge(c.getVm(), (double) c.getLength(), Double::sum);
                w.printf(Locale.ROOT, "%d,%s,%.4f,%.4f,%.4f,%.2f,%d,%.6f,%.4f%n",
                        r.id(), tier, r.arrival(), c.getFinishTime(), response,
                        r.deadline(), miss ? 1 : 0, cost, transfer);
            }
        }

        Arrays.sort(resp);
        int submitted = rowOf.size();
        double p95 = finished == 0 ? 0 : resp[(int) Math.min(finished - 1, Math.floor(finished * 0.95))];
        return new Metrics(submitted, finished,
                (double) (missed + (submitted - finished)) / submitted,
                finished == 0 ? 0 : sumResp / finished, p95, execCost, jain(vmUtilizations(fleet, busyMi, endTime)));
    }

    /**
     * Hiệu suất sử dụng của từng VM = MI đã gán cho VM / (MIPS x số PE x số giây VM đủ điều kiện nhận việc mới), thường trong 0..1
     * (có thể vượt 1 chút với VM vừa bị thu hồi, vì việc gán trước đó chạy nốt sau khi VM thôi nhận việc mới).
     * Chia cho MIPS để VM edge (1000) và cloud (2000) so sánh được với nhau; chia cho thời gian đủ điều kiện để VM do
     * autoscaler tạo ra sống ngắn, hoặc VM đã bị chọn thu hồi (thôi nhận việc), không bị coi là "được chia ít việc".
     * VM không bao giờ khởi động xong thì bỏ qua; VM còn nhận việc mà không được chia việc nào có hiệu suất 0
     * (gom tải làm Jain thấp là đúng bản chất: tải không đều giữa các VM).
     */
    private static List<Double> vmUtilizations(VmFleet fleet, Map<Vm, Double> busyMi, double endTime) {
        List<Double> out = new ArrayList<>();
        for (Vm v : fleet.allVms()) {
            double seconds = fleet.eligibleSeconds(v, endTime);
            if (seconds > 0) out.add(busyMi.get(v) / (v.getMips() * v.getPesNumber() * seconds));
        }
        return out;
    }

    /** Chỉ số công bằng Jain: (tổng x)^2 / (n * tổng x^2); 1.0 là hoàn toàn đều. */
    private static double jain(Iterable<Double> x) {
        double s = 0, s2 = 0;
        int n = 0;
        for (double v : x) { s += v; s2 += v * v; n++; }
        return s2 == 0 ? 1.0 : (s * s) / (n * s2);
    }
}
