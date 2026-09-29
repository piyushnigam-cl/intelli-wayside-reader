package com.intelli.rfid.wayside.pass;

import com.intelli.rfid.core.ReaderSession;
import com.intelli.rfid.spring.ReaderService;
import com.intelli.rfid.wayside.WaysideProperties.CarrierMode;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the RF carrier where the pass logic wants it (design sec.5.2).
 *
 * <p>The carrier is wanted when the mode is ALWAYS, when a pass is open, <b>or when the wheel link is
 * down</b> — degraded mode opens a pass on the first tag, and a dropped carrier would hear none.
 *
 * <p>Reconciled rather than commanded: {@link #passOpen} asks for an immediate reconcile, and a
 * 1 s timer repeats it. The timer is what catches the reader's own changes behind our back — the
 * connector starts inventory on every connect, and the fault supervisor restarts it after a
 * recovery — so a triggered carrier that the core raised is dropped again within a second.
 * Reader calls run on their own thread: {@code stopReading} costs 20–60 ms and must not sit in
 * front of wheel events.
 */
public class CarrierController implements PassTracker.Carrier {

    private static final Logger log = LoggerFactory.getLogger(CarrierController.class);

    private final ReaderService reader;
    private final CarrierMode mode;
    private final BooleanSupplier wheelLinkUp;
    private volatile boolean passOpen;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wayside-carrier");
        thread.setDaemon(true);
        return thread;
    });

    public CarrierController(ReaderService reader, CarrierMode mode, BooleanSupplier wheelLinkUp) {
        this.reader = reader;
        this.mode = mode;
        this.wheelLinkUp = wheelLinkUp;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::reconcile, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    @Override
    public void passOpen(boolean open) {
        passOpen = open;
        executor.execute(this::reconcile);
    }

    public boolean wanted() {
        return mode == CarrierMode.ALWAYS || passOpen || !wheelLinkUp.getAsBoolean();
    }

    /** Never throws: a carrier that will not move costs power, an exception here costs a pass. */
    void reconcile() {
        try {
            ReaderSession session = reader.session();
            ReaderSession.State state = session.state();
            if (state == ReaderSession.State.CLOSED || state == ReaderSession.State.FAULTED) {
                return;
            }
            boolean want = wanted();
            if (want && !session.isReading()) {
                session.startReading();
                log.info("Carrier on ({})", reason());
            } else if (!want && session.isReading()) {
                session.stopReading();
                log.info("Carrier off (triggered, no pass open)");
            }
        } catch (RuntimeException e) {
            log.warn("Could not reconcile the carrier: {}", e.getMessage());
        }
    }

    private String reason() {
        if (mode == CarrierMode.ALWAYS) {
            return "carrier: ALWAYS";
        }
        return passOpen ? "pass open" : "wheel link down, RFID-only";
    }
}
