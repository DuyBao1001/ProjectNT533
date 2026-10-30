package dcs;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimEntity;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.CloudSimTag;
import org.cloudsimplus.core.events.SimEvent;

import java.util.List;
import java.util.function.Function;

/**
 * Đưa tác vụ của trace vào broker đúng lúc chúng đến.
 *
 * Nếu nạp hết trước khi chạy (setSubmissionDelay), broker gọi vmMapper cho mọi tác vụ ngay ở giây 0 và chỉ trì hoãn
 * lúc gửi tới datacenter, nên chính sách đặt tác vụ không thấy được VM do autoscaler thêm vào sau đó.
 * Ở đây mỗi tác vụ được gửi vào broker đúng thời điểm đến, nên vmMapper chạy lúc đó với danh sách VM hiện có.
 */
public class TraceFeeder extends CloudSimEntity {

    /** Tag riêng (CloudSim dành riêng các số dưới 300 và 9600). */
    static final int TAG_ARRIVAL = 9700;

    private final DatacenterBroker broker;
    private final List<Main.TraceRow> rows;
    private final Function<Main.TraceRow, Cloudlet> factory;
    private int next = 0;

    public TraceFeeder(CloudSimPlus sim, DatacenterBroker broker, List<Main.TraceRow> rows,
                       Function<Main.TraceRow, Cloudlet> factory) {
        super(sim);
        this.broker = broker;
        this.rows = rows;      // phải sắp theo thời điểm đến (trace sinh ra đã sắp)
        this.factory = factory;
    }

    @Override
    protected void startInternal() {
        if (!rows.isEmpty()) schedule(rows.get(0).arrival(), TAG_ARRIVAL);
    }

    @Override
    public void processEvent(SimEvent evt) {
        switch (evt.getTag()) {
            case TAG_ARRIVAL -> feedDue();
            case CloudSimTag.SIMULATION_END, CloudSimTag.ENTITY_SHUTDOWN -> shutdown();
            default -> { }
        }
    }

    private void feedDue() {
        double now = getSimulation().clock();
        while (next < rows.size() && rows.get(next).arrival() <= now + 1e-9) {
            broker.submitCloudletList(List.of(factory.apply(rows.get(next))));
            next++;
        }
        if (next < rows.size()) schedule(Math.max(0, rows.get(next).arrival() - now), TAG_ARRIVAL);
    }
}
