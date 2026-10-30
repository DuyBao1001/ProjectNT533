package dcs;

import org.cloudsimplus.core.CloudSimEntity;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.CloudSimTag;
import org.cloudsimplus.core.events.SimEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Quan sát tải mỗi {@link AutoscaleConfig#intervalSec()} giây, hỏi {@link Autoscaler} rồi thêm/bớt VM qua {@link VmFleet}.
 * Luôn ghi {@code <tag>_scale.csv} (kể cả khi tắt co giãn, lúc đó quyết định luôn là 0) để so sánh có/không co giãn.
 *
 * CPU của tầng được lấy trung bình theo thời gian trên cả chu kỳ quan sát (tích phân qua các tick đồng hồ),
 * không phải giá trị tức thời tại thời điểm quan sát, vì giá trị tức thời dao động rất mạnh với vài VM.
 */
public class ScalingController extends CloudSimEntity implements AutoCloseable {

    static final int TAG_OBSERVE = 9701;

    private final VmFleet fleet;
    private final Autoscaler autoscaler;
    private final AutoscaleConfig cfg;
    private final PrintWriter log;

    private final double[] cpuArea = new double[Main.Tier.values().length];   // tích phân CPU trung bình tầng theo thời gian
    private final double[] lastScale = new double[Main.Tier.values().length];
    private double lastTick = 0;
    private double windowStart = 0;
    private int scaleUps = 0, scaleDowns = 0;

    public ScalingController(CloudSimPlus sim, VmFleet fleet, Autoscaler autoscaler, AutoscaleConfig cfg, Path file)
            throws IOException {
        super(sim);
        this.fleet = fleet;
        this.autoscaler = autoscaler;
        this.cfg = cfg;
        this.log = new PrintWriter(Files.newBufferedWriter(file));
        log.println("time,tier,active,pending,draining,avg_cpu,decision,applied");
        Arrays.fill(lastScale, -1e9);
        sim.addOnClockTickListener(info -> accumulate(info.getTime()));
    }

    public int scaleUps() { return scaleUps; }
    public int scaleDowns() { return scaleDowns; }

    @Override
    protected void startInternal() {
        schedule(cfg.intervalSec(), TAG_OBSERVE);
    }

    @Override
    public void processEvent(SimEvent evt) {
        switch (evt.getTag()) {
            case TAG_OBSERVE -> observe();
            case CloudSimTag.SIMULATION_END, CloudSimTag.ENTITY_SHUTDOWN -> shutdown();
            default -> { }
        }
    }

    private void accumulate(double t) {
        double dt = t - lastTick;
        if (dt > 0) {
            for (Main.Tier tier : Main.Tier.values()) cpuArea[tier.ordinal()] += fleet.avgCpu(tier) * dt;
            lastTick = t;
        }
    }

    private void observe() {
        // Không còn sự kiện nào khác nghĩa là hết việc: không quyết định và không lặp lại,
        // nếu không mô phỏng không bao giờ kết thúc (và sẽ ghi một lần giảm VM vô nghĩa sau khi xong).
        if (!getSimulation().isThereAnyFutureEvt(e -> e.getTag() != TAG_OBSERVE)) return;

        double now = getSimulation().clock();
        double window = now - windowStart;
        fleet.finishDraining(now);

        for (Main.Tier tier : Main.Tier.values()) {
            int k = tier.ordinal();
            int active = fleet.activeCount(tier);
            int pending = fleet.pendingCount(tier);
            int draining = fleet.drainingCount(tier);
            double avg = window > 0 ? cpuArea[k] / window : 0;

            int decision = 0, applied = 0;
            if (cfg.enabled()) {
                decision = autoscaler.decide(new Autoscaler.ScaleState(tier, now, active, pending, avg,
                        cfg.minVms(tier), cfg.maxVms(tier), now - lastScale[k]));
                // Chặn lần cuối theo min/max và số PE vật lý, bất kể autoscaler trả gì.
                if (decision > 0) applied = fleet.scaleUp(tier, decision, cfg.maxVms(tier), cfg.vmBootSec());
                else if (decision < 0) applied = -fleet.beginDrain(tier, -decision, cfg.minVms(tier));
                if (applied != 0) {
                    lastScale[k] = now;
                    if (applied > 0) scaleUps += applied; else scaleDowns += -applied;
                }
            }
            log.printf(Locale.ROOT, "%.2f,%s,%d,%d,%d,%.4f,%d,%d%n", now, tier, active, pending, draining, avg,
                    decision, applied);
            cpuArea[k] = 0;
        }
        windowStart = now;
        schedule(cfg.intervalSec(), TAG_OBSERVE);
    }

    @Override
    public void close() { log.close(); }
}
