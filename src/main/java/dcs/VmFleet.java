package dcs;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.*;

/**
 * Quản lý vòng đời VM của cả hai tầng: tạo (kể cả giữa lúc chạy), thu hồi và tính tiền theo thời gian VM thực sự chạy.
 *
 * Vòng đời: pending (đã yêu cầu, đang khởi động) -> active (nhận việc) -> draining (không nhận việc mới, đợi xong việc)
 * -> destroyed. Mọi map khóa theo Vm là IdentityHashMap vì hashCode của Vm phụ thuộc id (gán lúc submit).
 */
public class VmFleet {

    private final CloudSimPlus sim;
    private final DatacenterBroker broker;
    private final Map<Main.Tier, Datacenter> dcOfTier = new EnumMap<>(Main.Tier.class);
    private final Map<Vm, Main.Tier> tierOf = new IdentityHashMap<>();
    private final List<Vm> all = new ArrayList<>();      // mọi VM từng được yêu cầu
    private final List<Vm> active = new ArrayList<>();   // đã tạo, nhận việc, sắp theo id
    private final Set<Vm> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Vm> draining = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Vm, double[]> bill = new IdentityHashMap<>();   // {bắt đầu tính tiền, kết thúc (NaN nếu còn chạy)}
    private final Map<Vm, Double> activatedAt = new IdentityHashMap<>();   // lúc VM khởi động xong và bắt đầu nhận việc
    private final Map<Vm, Double> drainStartedAt = new IdentityHashMap<>();   // lúc VM bị chọn thu hồi (thôi nhận việc mới)

    public VmFleet(CloudSimPlus sim, DatacenterBroker broker, Datacenter edgeDc, Datacenter cloudDc) {
        this.sim = sim;
        this.broker = broker;
        dcOfTier.put(Main.Tier.EDGE, edgeDc);
        dcOfTier.put(Main.Tier.CLOUD, cloudDc);
    }

    // ---- truy vấn ----

    /** Datacenter của tầng chứa VM; dùng cho datacenterMapper của broker. */
    public Datacenter datacenterOf(Vm vm, Datacenter fallback) {
        Main.Tier t = tierOf.get(vm);
        return t == null ? fallback : dcOfTier.get(t);
    }

    public Main.Tier tierOf(Vm vm) { return tierOf.get(vm); }

    /** Bản xem trực tiếp (live) của mọi VM từng yêu cầu. */
    public List<Vm> allVms() { return Collections.unmodifiableList(all); }

    /** Bản xem trực tiếp của bản đồ VM -> tầng. */
    public Map<Vm, Main.Tier> tierMap() { return Collections.unmodifiableMap(tierOf); }

    /** VM đang nhận việc (mọi tầng), sắp theo id; là danh sách ứng viên cho PlacementPolicy. */
    public List<Vm> activeVms() { return Collections.unmodifiableList(active); }

    public List<Vm> activeVms(Main.Tier t) {
        List<Vm> out = new ArrayList<>();
        for (Vm v : active) if (tierOf.get(v) == t) out.add(v);
        return out;
    }

    public int activeCount(Main.Tier t) { return count(active, t); }
    public int pendingCount(Main.Tier t) { return count(pending, t); }
    public int drainingCount(Main.Tier t) { return count(draining, t); }

    private int count(Collection<Vm> vms, Main.Tier t) {
        int n = 0;
        for (Vm v : vms) if (tierOf.get(v) == t) n++;
        return n;
    }

    /** CPU trung bình (0..1) tức thời của các VM đang hoạt động trong tầng; 0 nếu không có VM nào. */
    public double avgCpu(Main.Tier t) {
        double sum = 0; int n = 0;
        for (Vm v : active) {
            if (tierOf.get(v) == t && v.isCreated()) { sum += v.getCpuPercentUtilization(); n++; }
        }
        return n == 0 ? 0 : sum / n;
    }

    // ---- tạo / thu hồi ----

    /** Yêu cầu tạo một VM; nhận việc sau bootDelaySec (thời gian khởi động). */
    public Vm addVm(Main.Tier tier, double bootDelaySec) {
        Main.TierSpec s = Main.specOf(tier);
        Vm vm = new VmSimple(s.mips(), Main.VM_PES);
        vm.setRam(Main.VM_RAM_MB).setBw(Main.VM_BW_MBPS).setSize(Main.VM_SIZE_MB);
        vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vm.setSubmissionDelay(bootDelaySec);     // broker gửi yêu cầu tạo VM sau đúng khoảng này
        tierOf.put(vm, tier);
        all.add(vm);
        pending.add(vm);
        bill.put(vm, new double[]{sim.clock(), Double.NaN});
        vm.addOnHostAllocationListener(info -> activate(vm));
        vm.addOnCreationFailureListener(info -> creationFailed(vm));
        broker.submitVmList(List.of(vm));
        return vm;
    }

    private void activate(Vm vm) {
        activatedAt.put(vm, sim.clock());
        pending.remove(vm);
        active.add(vm);
        active.sort(Comparator.comparingLong(Vm::getId));
    }

    private void creationFailed(Vm vm) {
        pending.remove(vm);
        bill.get(vm)[1] = bill.get(vm)[0];       // chưa chạy thì không tính tiền
        System.err.printf(Locale.ROOT, "CANH BAO %.2f: tao VM %d that bai (host het cho)%n", sim.clock(), vm.getId());
    }

    /** Thêm tối đa n VM cho tầng, không vượt maxVms (tính cả VM đang khởi động và đang thu hồi). Trả số VM thật sự thêm. */
    public int scaleUp(Main.Tier t, int n, int maxVms, double bootDelaySec) {
        int live = activeCount(t) + pendingCount(t) + drainingCount(t);
        int add = Math.max(0, Math.min(n, maxVms - live));
        for (int i = 0; i < add; i++) addVm(t, bootDelaySec);
        return add;
    }

    /** Bắt đầu thu hồi tối đa n VM ít bận nhất của tầng, giữ ít nhất minVms. Trả số VM thật sự bắt đầu thu hồi. */
    public int beginDrain(Main.Tier t, int n, int minVms) {
        List<Vm> mine = activeVms(t);
        int done = 0;
        while (done < n && mine.size() > minVms) {
            Vm pick = null; int pickBusy = Integer.MAX_VALUE;
            for (Vm v : mine) {
                int busy = v.getCloudletScheduler().getCloudletExecList().size()
                        + v.getCloudletScheduler().getCloudletWaitingList().size();
                // Ít việc nhất; hòa thì lấy VM mới nhất (id lớn nhất).
                if (busy < pickBusy || (busy == pickBusy && v.getId() > pick.getId())) { pick = v; pickBusy = busy; }
            }
            mine.remove(pick);
            active.remove(pick);
            draining.add(pick);
            drainStartedAt.put(pick, sim.clock());
            done++;
        }
        return done;
    }

    /** Hủy các VM đang thu hồi đã hết việc; dừng tính tiền ngay lúc hủy. */
    public void finishDraining(double now) {
        for (Iterator<Vm> it = draining.iterator(); it.hasNext(); ) {
            Vm vm = it.next();
            if (vm.getCloudletScheduler().isEmpty()) {
                vm.shutdown();
                bill.get(vm)[1] = now;
                it.remove();
            }
        }
    }

    /**
     * Số giây VM đủ điều kiện nhận tác vụ mới: từ lúc khởi động xong tới lúc bắt đầu bị thu hồi (hoặc tới lúc hủy / tới end
     * nếu không bị thu hồi). Khác thời gian tính tiền: không tính thời gian khởi động và thời gian "draining" (lúc đó VM chỉ
     * làm nốt việc cũ). 0 nếu VM chưa bao giờ khởi động xong.
     */
    public double eligibleSeconds(Vm vm, double end) {
        Double from = activatedAt.get(vm);
        if (from == null) return 0;
        Double drain = drainStartedAt.get(vm);
        double[] b = bill.get(vm);
        double to = drain != null ? drain : Double.isNaN(b[1]) ? end : b[1];
        return Math.max(0, to - from);
    }

    // ---- chi phí ----

    /** Tổng số giây-VM của tầng tính tới thời điểm end (VM còn chạy được tính tới end). */
    public double vmSeconds(Main.Tier t, double end) {
        double total = 0;
        for (Vm v : all) {
            if (tierOf.get(v) != t) continue;
            double[] b = bill.get(v);
            total += Math.max(0, (Double.isNaN(b[1]) ? end : b[1]) - b[0]);
        }
        return total;
    }

    /** Chi phí theo thời gian VM thực sự chạy = tổng giây-VM x giá/giây của tầng. */
    public double vmCost(double end) {
        double cost = 0;
        for (Main.Tier t : Main.Tier.values()) cost += vmSeconds(t, end) * Main.specOf(t).costPerVmSecond();
        return cost;
    }
}
