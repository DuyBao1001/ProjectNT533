package dcs;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModel;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mô phỏng Cloud-Edge hai tầng trên CloudSim Plus: dựng 2 datacenter, VM, nạp trace, chạy và xuất kết quả.
 *
 * Chạy: mvn -q compile exec:java "-Dexec.args=traces/burst_s1.csv binpack 1 results [on|off]"
 *       (đối số: trace, chính sách {random|rr|binpack}, seed, thư mục kết quả, bật co giãn on|off, mặc định off)
 */
public class Main {

    enum Tier { EDGE, CLOUD }

    /**
     * Thông số mỗi tầng: giá/giây VM, độ trễ mạng cộng thêm (giây) và băng thông đường truyền
     * từ nguồn tác vụ tới tầng (Mbps). Thời gian truyền = data_mb*8/netMbps + latencySec.
     * (bwMbps là băng thông của host trong datacenter, khác với netMbps.)
     */
    record TierSpec(int hosts, int pesPerHost, long mips, long ramMb, long bwMbps,
                    double costPerVmSecond, double latencySec, double netMbps) {
        double transferSec(double dataMb) { return dataMb * 8.0 / netMbps + latencySec; }
    }

    // netMbps: edge gần nguồn nên đường truyền rộng hơn cloud (giá trị tạm, sẽ hiệu chỉnh theo số đo k3s).
    static final TierSpec EDGE_SPEC  = new TierSpec(2, 4, 1000, 8192, 1000, 0.002, 0.005, 100);
    static final TierSpec CLOUD_SPEC = new TierSpec(4, 8, 2000, 32768, 10000, 0.010, 0.080, 40);

    static TierSpec specOf(Tier t) { return t == Tier.EDGE ? EDGE_SPEC : CLOUD_SPEC; }

    // VM: mỗi VM 2 PE. Số VM ban đầu mỗi tầng (autoscaler có thể thêm/bớt, xem AutoscaleConfig).
    static final int VM_PES = 2;
    static final int VM_RAM_MB = 1024;
    static final int VM_BW_MBPS = 100;
    static final int VM_SIZE_MB = 1000;
    static final int EDGE_VMS = 4;
    static final int CLOUD_VMS = 8;
    // Mỗi cloudlet cần bấy nhiêu RAM (MB) và băng thông (Mbps) của VM (VM có 1024 MB, 100 Mbps).
    static final double CLOUDLET_RAM_MB = 256;
    static final double CLOUDLET_BW_MBPS = 10;
    /** Chu kỳ lấy mẫu CPU/RAM (giây mô phỏng) cho results/<tag>_util.csv. */
    static final double UTIL_SAMPLE_INTERVAL_S = 5.0;

    record TraceRow(int id, double arrival, long lengthMi, double deadline, double dataMb) {}

    /** Tham số một lần chạy, đọc từ dòng lệnh. */
    record RunConfig(Path trace, String policy, long seed, Path outDir, boolean autoscale) {
        static RunConfig fromArgs(String[] a) {
            return new RunConfig(
                    Path.of(a.length > 0 ? a[0] : "traces/poisson_s1.csv"),
                    a.length > 1 ? a[1] : "binpack",
                    a.length > 2 ? Long.parseLong(a[2]) : 1L,
                    Path.of(a.length > 3 ? a[3] : "results"),
                    a.length > 4 && a[4].equalsIgnoreCase("on"));
        }

        /** Tiền tố tên file kết quả; bật co giãn thì có hậu tố _as. */
        String tag() {
            return trace.getFileName().toString().replace(".csv", "") + "_" + policy + "_seed" + seed
                    + (autoscale ? "_as" : "");
        }
    }

    public static void main(String[] args) throws IOException {
        RunConfig run = RunConfig.fromArgs(args);
        AutoscaleConfig cfg = AutoscaleConfig.defaults(run.autoscale());
        Files.createDirectories(run.outDir());

        List<TraceRow> trace = readTrace(run.trace());
        Map<Integer, TraceRow> rowOf = new HashMap<>();
        for (TraceRow r : trace) rowOf.put(r.id(), r);

        CloudSimPlus sim = new CloudSimPlus();
        Datacenter edgeDc  = createDatacenter(sim, EDGE_SPEC);
        Datacenter cloudDc = createDatacenter(sim, CLOUD_SPEC);

        DatacenterBroker broker = new DatacenterBrokerSimple(sim);
        VmFleet fleet = new VmFleet(sim, broker, edgeDc, cloudDc);

        // VM ban đầu; mọi VM (kể cả do autoscaler thêm sau) đều đi qua VmFleet.
        for (int i = 0; i < EDGE_VMS; i++)  fleet.addVm(Tier.EDGE, 0);
        for (int i = 0; i < CLOUD_VMS; i++) fleet.addVm(Tier.CLOUD, 0);
        // Ép mỗi VM tạo trong đúng datacenter của tầng nó.
        broker.setDatacenterMapper((lastDc, vm) -> fleet.datacenterOf(vm, lastDc));

        // Chính sách chỉ chọn trong các VM đang hoạt động tại thời điểm tác vụ đến.
        PlacementPolicy policy = PlacementPolicy.byName(run.policy(), run.seed(),
                c -> rowOf.get((int) c.getId()).deadline());
        broker.setVmMapper(c -> policy.select(c, fleet.activeVms()));

        // Tác vụ được đưa vào broker đúng lúc đến (xem TraceFeeder).
        new TraceFeeder(sim, broker, trace, Main::newCloudlet);

        try (UtilizationRecorder util = new UtilizationRecorder(sim, fleet.allVms(), fleet.tierMap(),
                     UTIL_SAMPLE_INTERVAL_S, run.outDir().resolve(run.tag() + "_util.csv"));
             ScalingController scaler = new ScalingController(sim, fleet, new Autoscaler.StaticThreshold(cfg), cfg,
                     run.outDir().resolve(run.tag() + "_scale.csv"))) {
            sim.start();
            // Tính tiền VM tới lúc tác vụ cuối hoàn thành, không dùng sim.clock(): lần quan sát cuối của
            // ScalingController có thể kéo đồng hồ chạy thừa tới 1 chu kỳ sau khi hết việc.
            double endTime = broker.getCloudletFinishedList().stream()
                    .mapToDouble(Cloudlet::getFinishTime).max().orElse(sim.clock());
            ResultWriter.write(run, broker, fleet, rowOf, util, scaler, endTime);
        }
    }

    /** Cloudlet cho một dòng trace. */
    static Cloudlet newCloudlet(TraceRow r) {
        Cloudlet c = new CloudletSimple(r.lengthMi(), 1);
        c.setId(r.id());
        // CPU chiếm hết 1 PE; RAM/băng thông là mức tuyệt đối nhỏ. Nếu dùng UtilizationModelFull cho cả ba,
        // mỗi cloudlet chiếm 100% RAM/BW của VM, cloudlet sau bị trì hoãn và không bao giờ được kích hoạt lại.
        c.setUtilizationModelCpu(new UtilizationModelFull());
        c.setUtilizationModelRam(new UtilizationModelDynamic(UtilizationModel.Unit.ABSOLUTE, CLOUDLET_RAM_MB));
        c.setUtilizationModelBw(new UtilizationModelDynamic(UtilizationModel.Unit.ABSOLUTE, CLOUDLET_BW_MBPS));
        return c;
    }

    static Datacenter createDatacenter(CloudSimPlus sim, TierSpec s) {
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < s.hosts(); h++) {
            List<Pe> pes = new ArrayList<>();
            for (int p = 0; p < s.pesPerHost(); p++) pes.add(new PeSimple(s.mips()));
            hosts.add(new HostSimple(s.ramMb(), s.bwMbps(), 1_000_000, pes));
        }
        return new DatacenterSimple(sim, hosts);
    }

    static List<TraceRow> readTrace(Path p) throws IOException {
        List<TraceRow> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {           // bỏ dòng tiêu đề
            String[] f = lines.get(i).trim().split(",");
            if (f.length < 4) continue;
            double dataMb = f.length >= 5 && !f[4].isBlank() ? Double.parseDouble(f[4]) : 0.0; // file cũ: không có cột
            rows.add(new TraceRow(Integer.parseInt(f[0]), Double.parseDouble(f[1]),
                    Long.parseLong(f[2]), Double.parseDouble(f[3]), dataMb));
        }
        return rows;
    }
}
