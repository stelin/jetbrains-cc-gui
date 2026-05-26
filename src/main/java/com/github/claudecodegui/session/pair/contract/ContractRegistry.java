package com.github.claudecodegui.session.pair.contract;

import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-pair registry of all open/closed Contracts. Owns deadline timers and
 * notifies a single deadline callback when one fires (callback decides
 * R1/R2/R3 — registry only exposes mechanism, not policy).
 *
 * <p>Concurrency: state maps are concurrent. Public mutators
 * ({@link #issue}, {@link #discharge}, {@link #retry}, {@link #escalate},
 * {@link #cancel}) are synchronized on the registry instance to keep the
 * triple (state mutate → schedule timer → notify) atomic relative to other
 * mutators. Read methods are lock-free.
 */
public class ContractRegistry {

    private static final Logger LOG = Logger.getInstance(ContractRegistry.class);

    public static final long DEFAULT_DEADLINE_MS = 10L * 60L * 1000L;
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final int CLOSED_HISTORY_MAX = 200;
    /** Hydrated contracts older than this are auto-cancelled as stale.
     *  Beyond this window, the previous-session supervisor is almost
     *  certainly gone and re-running the contract makes no sense. */
    public static final long MAX_HYDRATION_AGE_MS = 30L * 60L * 1000L;

    private final String pairId;
    private final Map<String, Contract> openContracts = new ConcurrentHashMap<>();
    private final Deque<Contract> closedContracts = new ArrayDeque<>();
    private final ScheduledExecutorService deadlineScheduler;
    private final Map<String, ScheduledFuture<?>> deadlineFutures = new ConcurrentHashMap<>();
    private final List<ContractListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    // 2026-05-25: cumulative counters for the UI live-stats strip. Closed
    // contracts get GC'd from the rolling history after 200, but operators
    // want a session-lifetime total — these AtomicLongs never reset.
    private final AtomicLong totalIssued = new AtomicLong(0);
    private final AtomicLong totalRetried = new AtomicLong(0);
    private final AtomicLong totalDischarged = new AtomicLong(0);
    private final AtomicLong totalEscalated = new AtomicLong(0);

    private volatile Consumer<Contract> deadlineCallback;

    public ContractRegistry(String pairId) {
        this.pairId = pairId;
        this.deadlineScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "contract-registry-" + pairId);
            t.setDaemon(true);
            return t;
        });
    }

    public void setDeadlineCallback(Consumer<Contract> callback) {
        this.deadlineCallback = callback;
    }

    // ─── Creation ────────────────────────────────────────────────────────

    public synchronized Contract issue(ContractIssueRequest req) {
        if (disposed.get()) {
            throw new IllegalStateException("ContractRegistry " + pairId + " is disposed");
        }
        // Dedup: same parentStepId + assignedTo with status OPEN → replace.
        if (req.replaceExisting) {
            for (Contract existing : openContracts.values()) {
                if (existing.status == ContractStatus.OPEN
                        && existing.parentStepId.equals(req.parentStepId)
                        && existing.assignedTo == req.assignedTo) {
                    cancelInternal(existing, "replaced by new issue " + (req.contractIdHint != null ? req.contractIdHint : "(auto)"));
                    break;
                }
            }
        }

        String id = req.contractIdHint != null && !req.contractIdHint.isEmpty()
                ? req.contractIdHint
                : generateId(req.parentStepId);

        long deadline = req.deadlineMs > 0 ? req.deadlineMs : DEFAULT_DEADLINE_MS;

        Contract c = Contract.create(
                id,
                req.parentStepId,
                req.type,
                req.assignedTo,
                req.payloadJson,
                deadline,
                req.maxRetries,
                req.retryOf,
                req.retryCount
        );
        c.history.add(ContractEvent.of(ContractEvent.EventType.ISSUED,
                req.retryOf != null ? "retry of " + req.retryOf : null));

        openContracts.put(c.id, c);
        scheduleDeadline(c);
        totalIssued.incrementAndGet();

        LOG.info("[ContractRegistry] " + pairId + " issued id=" + c.id
                + " step=" + c.parentStepId + " type=" + c.type
                + " assignee=" + c.assignedTo + " retry=" + c.retryCount + "/" + c.maxRetries);

        notifyIssued(c);
        return c;
    }

    // ─── Mark received (intermediate ack, deadline keeps running) ────────

    public synchronized boolean markReceived(String contractId, String note) {
        Contract c = openContracts.get(contractId);
        if (c == null) return false;
        if (c.status != ContractStatus.OPEN) return false;
        c.status = ContractStatus.RECEIVED;
        c.lastActivityAt = System.currentTimeMillis();
        c.history.add(ContractEvent.of(ContractEvent.EventType.RECEIVED, note));
        // Don't cancel the deadline timer — only DISCHARGED / RETRIED / CANCELLED do that.
        return true;
    }

    // ─── Discharge ───────────────────────────────────────────────────────

    public synchronized boolean discharge(String contractId, String note) {
        Contract c = openContracts.get(contractId);
        if (c == null) return false;

        cancelDeadlineTimer(contractId);
        c.status = ContractStatus.DISCHARGED;
        c.lastActivityAt = System.currentTimeMillis();
        c.history.add(ContractEvent.of(ContractEvent.EventType.DISCHARGED, note));

        moveToClosed(c);
        totalDischarged.incrementAndGet();
        LOG.info("[ContractRegistry] " + pairId + " discharged id=" + contractId);
        notifyDischarged(c);
        return true;
    }

    // ─── Retry (R1 / R2) ─────────────────────────────────────────────────

    public synchronized Contract retry(String contractId, ContractType retryType, String hint) {
        Contract original = openContracts.get(contractId);
        if (original == null) {
            throw new IllegalArgumentException("retry: contract not open: " + contractId);
        }

        cancelDeadlineTimer(contractId);
        original.status = ContractStatus.EXPIRED_RETRIED;
        original.history.add(ContractEvent.of(ContractEvent.EventType.RETRIED,
                hint != null ? "→ retry hint: " + hint : null));
        moveToClosed(original);

        String newId = generateId(original.parentStepId);
        Contract retry = Contract.create(
                newId,
                original.parentStepId,
                retryType,
                original.assignedTo,
                original.payloadJson,
                original.deadlineMs,
                original.maxRetries,
                original.id,
                original.retryCount + 1
        );
        retry.history.add(ContractEvent.of(ContractEvent.EventType.ISSUED,
                "retry " + retry.retryCount + "/" + retry.maxRetries
                        + " of " + original.id
                        + (hint != null ? " hint=" + hint : "")));

        openContracts.put(retry.id, retry);
        scheduleDeadline(retry);
        totalRetried.incrementAndGet();

        LOG.info("[ContractRegistry] " + pairId + " retried original=" + original.id
                + " → new=" + retry.id + " retryCount=" + retry.retryCount);

        notifyRetried(original, retry);
        return retry;
    }

    // ─── Escalate (R3) ───────────────────────────────────────────────────

    public synchronized void escalate(String contractId) {
        Contract c = openContracts.get(contractId);
        if (c == null) return;

        cancelDeadlineTimer(contractId);
        c.status = ContractStatus.EXPIRED_ESCALATED;
        c.history.add(ContractEvent.of(ContractEvent.EventType.ESCALATED, null));
        moveToClosed(c);
        totalEscalated.incrementAndGet();

        LOG.warn("[ContractRegistry] " + pairId + " escalated id=" + contractId);
        notifyEscalated(c);
    }

    // ─── Cancel ──────────────────────────────────────────────────────────

    public synchronized void cancel(String contractId, String reason) {
        Contract c = openContracts.get(contractId);
        if (c == null) return;
        cancelInternal(c, reason);
    }

    private void cancelInternal(Contract c, String reason) {
        cancelDeadlineTimer(c.id);
        c.status = ContractStatus.CANCELLED;
        c.history.add(ContractEvent.of(ContractEvent.EventType.CANCELLED, reason));
        moveToClosed(c);
        LOG.info("[ContractRegistry] " + pairId + " cancelled id=" + c.id + " reason=" + reason);
        notifyCancelled(c, reason);
    }

    // ─── Queries ─────────────────────────────────────────────────────────

    public List<Contract> getOpenContracts() {
        return new ArrayList<>(openContracts.values());
    }

    public Contract findById(String contractId) {
        Contract c = openContracts.get(contractId);
        if (c != null) return c;
        synchronized (closedContracts) {
            for (Contract closed : closedContracts) {
                if (closed.id.equals(contractId)) return closed;
            }
        }
        return null;
    }

    public Optional<Contract> findOldestOpen() {
        Contract oldest = null;
        for (Contract c : openContracts.values()) {
            if (oldest == null || c.issuedAt < oldest.issuedAt) {
                oldest = c;
            }
        }
        return Optional.ofNullable(oldest);
    }

    public boolean hasPendingFor(String parentStepId, ContractAssignee assignee) {
        if (parentStepId == null || assignee == null) return false;
        for (Contract c : openContracts.values()) {
            if (c.status == ContractStatus.OPEN
                    && parentStepId.equals(c.parentStepId)
                    && c.assignedTo == assignee) {
                return true;
            }
        }
        return false;
    }

    public List<Contract> getClosedContractsCopy() {
        synchronized (closedContracts) {
            return new ArrayList<>(closedContracts);
        }
    }

    public int openCount() {
        return openContracts.size();
    }

    /** Session-lifetime total: all contracts ever issued (including retries). */
    public long getTotalIssued() { return totalIssued.get(); }

    /** Session-lifetime total: how many R1/R2 retry contracts were issued. */
    public long getTotalRetried() { return totalRetried.get(); }

    /** Session-lifetime total: discharged contracts. */
    public long getTotalDischarged() { return totalDischarged.get(); }

    /** Session-lifetime total: R3-escalated contracts. */
    public long getTotalEscalated() { return totalEscalated.get(); }

    // ─── Hydration ───────────────────────────────────────────────────────

    public synchronized void hydrateFromL2(List<Contract> contracts) {
        if (disposed.get()) return;
        // Clear current
        for (String id : new ArrayList<>(deadlineFutures.keySet())) {
            cancelDeadlineTimer(id);
        }
        openContracts.clear();

        long now = System.currentTimeMillis();
        if (contracts == null) return;
        int hydrated = 0;
        int staleCancelled = 0;
        for (Contract c : contracts) {
            if (c == null || c.id == null) continue;
            if (c.status != ContractStatus.OPEN && c.status != ContractStatus.RECEIVED) {
                // Skip non-open contracts — they belong in closed history,
                // but that ring is in-memory only and not persisted (per §11.1).
                continue;
            }

            // 2026-05-25 fix: stale contracts (issued >30min ago) get
            // auto-cancelled on hydrate. The previous-session supervisor is
            // gone; replaying its deadline timer or letting the contract sit
            // in openContracts forever is just noise.
            long ageMs = now - c.issuedAt;
            if (ageMs > MAX_HYDRATION_AGE_MS) {
                c.status = ContractStatus.CANCELLED;
                c.lastActivityAt = now;
                c.history.add(ContractEvent.of(ContractEvent.EventType.CANCELLED,
                        "stale on hydration (age=" + (ageMs / 60_000L) + "min)"));
                synchronized (closedContracts) {
                    closedContracts.addLast(c);
                    while (closedContracts.size() > CLOSED_HISTORY_MAX) closedContracts.removeFirst();
                }
                staleCancelled++;
                continue;
            }

            openContracts.put(c.id, c);
            // 2026-05-25 fix: hydrated contracts count toward totalIssued so the
            // UI doesn't show "Open=1, 累计=0" (impossible math). They were
            // historically issued; we just lost the in-memory counter at restart.
            totalIssued.incrementAndGet();

            long remaining = c.deadlineMs - ageMs;
            if (remaining <= 0) {
                // Already expired — fire callback ASAP (small delay to let caller wire up first).
                scheduleDeadlineWith(c, 1L);
            } else {
                scheduleDeadlineWith(c, remaining);
            }
            hydrated++;
        }
        LOG.info("[ContractRegistry] " + pairId
                + " hydrated " + hydrated + " open contracts, cancelled " + staleCancelled + " stale");
    }

    // ─── Listeners ───────────────────────────────────────────────────────

    public void addListener(ContractListener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(ContractListener l) {
        listeners.remove(l);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public void dispose() {
        if (!disposed.compareAndSet(false, true)) return;
        for (ScheduledFuture<?> f : deadlineFutures.values()) {
            f.cancel(false);
        }
        deadlineFutures.clear();
        deadlineScheduler.shutdownNow();
        try {
            deadlineScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        openContracts.clear();
        synchronized (closedContracts) {
            closedContracts.clear();
        }
        listeners.clear();
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private String generateId(String parentStepId) {
        long ts = System.currentTimeMillis();
        String rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return "ctr_" + parentStepId + "_" + ts + "_" + rand;
    }

    private void scheduleDeadline(Contract c) {
        scheduleDeadlineWith(c, c.deadlineMs);
    }

    private void scheduleDeadlineWith(Contract c, long delayMs) {
        if (disposed.get()) return;
        try {
            ScheduledFuture<?> f = deadlineScheduler.schedule(
                    () -> fireDeadline(c.id),
                    delayMs,
                    TimeUnit.MILLISECONDS);
            deadlineFutures.put(c.id, f);
        } catch (Exception e) {
            LOG.warn("[ContractRegistry] " + pairId + " schedule failed for " + c.id + ": " + e.getMessage());
        }
    }

    private void cancelDeadlineTimer(String contractId) {
        ScheduledFuture<?> f = deadlineFutures.remove(contractId);
        if (f != null) f.cancel(false);
    }

    private void fireDeadline(String contractId) {
        if (disposed.get()) return;
        Contract c = openContracts.get(contractId);
        if (c == null || !c.isOpen()) return;
        Consumer<Contract> cb = this.deadlineCallback;
        if (cb == null) {
            LOG.debug("[ContractRegistry] " + pairId + " deadline fired but no callback: " + contractId);
            return;
        }
        try {
            cb.accept(c);
        } catch (Exception e) {
            LOG.warn("[ContractRegistry] " + pairId + " deadline callback threw for " + contractId + ": " + e.getMessage());
        }
    }

    private void moveToClosed(Contract c) {
        openContracts.remove(c.id);
        synchronized (closedContracts) {
            closedContracts.addLast(c);
            while (closedContracts.size() > CLOSED_HISTORY_MAX) {
                closedContracts.removeFirst();
            }
        }
    }

    private void notifyIssued(Contract c) {
        for (ContractListener l : listeners) {
            try { l.onIssued(c); } catch (Exception e) { logListenerError(e); }
        }
    }

    private void notifyDischarged(Contract c) {
        for (ContractListener l : listeners) {
            try { l.onDischarged(c); } catch (Exception e) { logListenerError(e); }
        }
    }

    private void notifyRetried(Contract original, Contract retry) {
        for (ContractListener l : listeners) {
            try { l.onRetried(original, retry); } catch (Exception e) { logListenerError(e); }
        }
    }

    private void notifyEscalated(Contract c) {
        for (ContractListener l : listeners) {
            try { l.onEscalated(c); } catch (Exception e) { logListenerError(e); }
        }
    }

    private void notifyCancelled(Contract c, String reason) {
        for (ContractListener l : listeners) {
            try { l.onCancelled(c, reason); } catch (Exception e) { logListenerError(e); }
        }
    }

    private void logListenerError(Exception e) {
        LOG.warn("[ContractRegistry] " + pairId + " listener threw: " + e.getMessage());
    }
}
