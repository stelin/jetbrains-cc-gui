package com.github.claudecodegui.bridge;

import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.common.IBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Java-side facade for the daemon's {@code supervisor.*} methods.
 *
 * <p>Each Pair owns one instance. Internally talks to the shared
 * {@link ClaudeSDKBridge#sendDaemonCommand} entry point, so the Supervisor
 * channel coexists with the main Claude channel on the same daemon process.
 */
public class SupervisorBridge {

    private static final Logger LOG = Logger.getInstance(SupervisorBridge.class);

    /**
     * Appendix B (2026-05-24): protocol version this Java client codes against.
     * Daemon advertises its own {@code protocolVersion} on supervisor.start;
     * mismatches are logged below for diagnostic purposes. No v1 fallback is
     * wired because the autonomy refactor is a single cutover (plan §10.3).
     */
    public static final String CURRENT_PROTOCOL_VERSION = "v2";

    public static final String ACTION_LINE_PREFIX = "[SUPERVISOR_ACTION]";
    /**
     * v4 unified pipeline prefix. Each raw SDK message the daemon yields
     * during a supervisor turn is written as a line of this form so the
     * webview can render content blocks live (instead of waiting for the
     * post-turn wrapper). The line content is a JSON envelope:
     *   { pairId, supervisorId, turnId, message: <raw SDK msg> }
     */
    public static final String MSG_LINE_PREFIX = "[SUPERVISOR_MSG]";

    /**
     * 2026-05-28: live per-turn output-token estimate emitted by the daemon on
     * partial-message stream events, so the supervisor pane's WaitingIndicator
     * can show a CLI-style "↓ N tokens" counter that ticks during the turn.
     * Envelope: {@code { pairId, supervisorId, turnId, outputTokens }}.
     */
    public static final String LIVE_USAGE_PREFIX = "[SUPERVISOR_USAGE]";

    /**
     * Phase 2 (2026-05-24): emitted by the daemon when the SDK reports a
     * mid-turn auto-compaction event. Counted into per-Pair metrics for
     * Phase 5 rotation triggers.
     */
    public static final String COMPACT_BOUNDARY_PREFIX = "[COMPACT_BOUNDARY]";

    /** Phase 2: response prefix for {@link #health()}. */
    public static final String HEALTH_PREFIX = "[SUPERVISOR_HEALTH]";

    /** Phase 2: response prefix for {@link #getContextUsage()}. */
    public static final String CONTEXT_USAGE_PREFIX = "[CONTEXT_USAGE]";

    /** Phase 2: response prefix for {@link #interrupt()}. */
    public static final String INTERRUPT_RESULT_PREFIX = "[SUPERVISOR_INTERRUPT_RESULT]";

    /**
     * Phase 3 (2026-05-24): emitted whenever the supervisor calls the
     * {@code update_state} MCP tool. Carries a sparse JSON delta that
     * {@link com.github.claudecodegui.session.pair.l2.L2Store} merges into
     * the Pair's durable state.
     */
    public static final String STATE_UPDATE_PREFIX = "[STATE_UPDATE]";

    /**
     * Phase 3: emitted by the PreCompact SDK hook, immediately before the SDK
     * auto-compacts the supervisor's conversation. Java responds by writing
     * a timestamped L2 snapshot to disk so a crash mid-compact has a recent
     * recovery point.
     */
    public static final String PRE_COMPACT_PREFIX = "[PRE_COMPACT]";

    /**
     * Session resume (SR3, session-resume-plan.md): emitted on the first turn
     * once the SDK assigns a session_id. Java persists it (into NodeRuntime) so a
     * restart can resume this supervisor's transcript. Envelope:
     * {@code { pairId, supervisorId, sessionId }}.
     */
    public static final String SESSION_LINE_PREFIX = "[SUPERVISOR_SESSION]";

    /**
     * Session resume (SR5): emitted when a requested {@code resumeSessionId} was
     * NOT honoured (the SDK started a different session — resume not supported for
     * this session shape). Java falls back to "fresh + handoff" (SR6). Envelope:
     * {@code { pairId, supervisorId, requested, actual }}.
     */
    public static final String RESUME_MISS_PREFIX = "[SUPERVISOR_RESUME_MISS]";

    /**
     * Phase 4 (2026-05-24): response payload for {@link #produceHandoff}. The
     * daemon turns the LLM's prose JSON output into an envelope of the form
     * {@code { "json": "...", "raw": "..." }} so Java can both validate the
     * structured form and keep the original assistant text for diagnostics.
     */
    public static final String HANDOFF_DOC_PREFIX = "[HANDOFF_DOC]";

    /**
     * Translate the daemon's "Unknown provider: supervisor" / "Unknown supervisor command"
     * errors into a message that tells the user what to actually do. These errors come
     * from an out-of-date {@code daemon.js} that predates the supervisor channel —
     * usually the result of a half-extracted bridge directory on Windows.
     */
    static String translateDaemonError(String error) {
        if (error == null) return null;
        if (error.contains("Unknown provider: supervisor")
                || error.contains("Unknown supervisor command")) {
            return "Bridge daemon is out of date (does not implement the supervisor channel). "
                    + "Quit the IDE, delete the ai-bridge/ folder inside the plugin directory, "
                    + "and restart to force a fresh extraction. "
                    + "Original error: " + error;
        }
        return error;
    }

    private final ClaudeSDKBridge sdkBridge;
    private final String pairId;
    /**
     * Phase 4 (2026-05-24): mutable so {@link #setSupervisorId} can swap
     * the daemon-side runtime key when a rotation completes. All RPC calls
     * read this volatile field at the call site, so a swap is observed by
     * the very next method invocation. Existing callers that captured the
     * value via {@link #getSupervisorId} get the OLD value as a snapshot
     * — that is intentional for paths like {@code stopById(oldId)}.
     */
    private volatile String supervisorId;
    /**
     * v4 unified pipeline: stream handler for `[SUPERVISOR_MSG]` lines emitted
     * by the daemon during a supervisor turn. Set once by PairHandler when
     * the pair is created; null means stream messages are dropped (e.g. tests).
     */
    private volatile Consumer<JsonObject> messageHandler;

    /**
     * 2026-05-28: consumer of {@code [SUPERVISOR_USAGE]} live output-token lines.
     * Wired by PairHandler to push {@code window.onSupervisorLiveUsage}. Null
     * means the lines are dropped (e.g. tests). Kept separate from the turn-end
     * context-% usage push so the live ticker and the context indicator don't
     * fight over one payload.
     */
    private volatile Consumer<JsonObject> liveUsageHandler;

    /**
     * Phase 0 (2026-05-23): bounded buffer for `[SUPERVISOR_MSG]` lines that
     * arrive between {@code supervisor.start} completing on the daemon side
     * and {@link #setMessageHandler} being wired by PairHandler. Previously
     * these were silently dropped, which is one of the assistant-message-loss
     * paths the user reported. Buffer cap is generous (256) but bounded so a
     * permanently-unregistered handler can't leak memory.
     */
    private static final int MSG_BUFFER_CAP = 256;
    private final Deque<JsonObject> messageBuffer = new ArrayDeque<>();
    private final AtomicInteger droppedBufferedMessages = new AtomicInteger();

    /**
     * Phase 2 (2026-05-24): optional consumer of mid-turn {@code [COMPACT_BOUNDARY]}
     * lines emitted by the daemon when the SDK auto-compacts. Wired by the
     * PairSessionManager so the per-Pair compactCount counter and
     * PairStatusPusher can stay in sync without polling.
     */
    private volatile Consumer<JsonObject> compactBoundaryHandler;

    /**
     * Phase 3 (2026-05-24): consumer of {@code [STATE_UPDATE]} lines emitted by
     * the supervisor's {@code update_state} MCP tool. The PairSessionManager
     * wires this to {@code L2Store.applyUpdateStateDelta(pairId, payload)}.
     */
    private volatile Consumer<JsonObject> stateUpdateHandler;

    /**
     * Phase 3: consumer of {@code [PRE_COMPACT]} lines emitted by the
     * supervisor's PreCompact hook just before SDK auto-compacts.
     * The PairSessionManager wires this to {@code L2Store.writePrecompactSnapshot}.
     */
    private volatile Consumer<JsonObject> preCompactHandler;

    /**
     * Session resume (SR3): consumer of {@code [SUPERVISOR_SESSION]} lines.
     * PairHandler wires this to {@code PairSession.setSupervisorSessionId} +
     * the workflow manager so the captured id is persisted into NodeRuntime.
     */
    private volatile Consumer<JsonObject> sessionHandler;

    /** Session resume (SR5): consumer of {@code [SUPERVISOR_RESUME_MISS]} lines. */
    private volatile Consumer<JsonObject> resumeMissHandler;

    public SupervisorBridge(ClaudeSDKBridge sdkBridge, String pairId, String supervisorId) {
        this.sdkBridge = sdkBridge;
        this.pairId = pairId;
        this.supervisorId = supervisorId;
    }

    public String getPairId() { return pairId; }
    public String getSupervisorId() { return supervisorId; }

    /**
     * Phase 4 (2026-05-24): swap the daemon-side runtime key. Used by
     * {@code RotationCoordinator} after a successful rotate so subsequent
     * RPCs target the freshly-started supervisor session. Callers that
     * already captured the OLD id (e.g. for {@code stopById}) keep their
     * snapshot — that is desired.
     */
    public void setSupervisorId(String newSupervisorId) {
        if (newSupervisorId == null || newSupervisorId.isEmpty()) {
            throw new IllegalArgumentException("supervisorId must be non-empty");
        }
        LOG.info("[SupervisorBridge] supervisorId swap " + supervisorId + " -> " + newSupervisorId);
        this.supervisorId = newSupervisorId;
    }

    /**
     * Register the consumer for `[SUPERVISOR_MSG]` lines parsed in {@link #postEvent}.
     * Pass null to clear. Replaces any previously-set handler — the bridge
     * does not multicast (PairHandler wires exactly one).
     *
     * <p>If messages were buffered while no handler was registered, they are
     * drained synchronously into the new handler (preserves order). Calling
     * with null does NOT clear the buffer — a later non-null setter can still
     * receive the early messages.
     */
    public void setMessageHandler(Consumer<JsonObject> handler) {
        List<JsonObject> toDrain = null;
        synchronized (messageBuffer) {
            this.messageHandler = handler;
            if (handler != null && !messageBuffer.isEmpty()) {
                toDrain = new ArrayList<>(messageBuffer);
                messageBuffer.clear();
            }
        }
        if (toDrain != null) {
            LOG.info("[SupervisorBridge] draining " + toDrain.size()
                    + " buffered messages to newly-registered handler");
            for (JsonObject msg : toDrain) {
                try {
                    handler.accept(msg);
                } catch (Exception e) {
                    LOG.warn("[SupervisorBridge] buffered drain delivery failed: " + e.getMessage());
                }
            }
        }
    }

    /** Diagnostic: cumulative count of buffered messages evicted due to cap overflow. */
    public int getDroppedBufferedMessageCount() {
        return droppedBufferedMessages.get();
    }

    /**
     * Phase 2: register a callback to be invoked when the daemon emits a
     * {@code [COMPACT_BOUNDARY]} line during a supervisor turn. Pass null
     * to unregister. The callback runs on the daemon's IPC reader thread —
     * keep it fast and non-blocking (push to a queue if heavier work).
     */
    public void setCompactBoundaryHandler(Consumer<JsonObject> handler) {
        this.compactBoundaryHandler = handler;
    }

    /** Phase 3: register a callback for {@code [STATE_UPDATE]} lines. See field doc. */
    public void setStateUpdateHandler(Consumer<JsonObject> handler) {
        this.stateUpdateHandler = handler;
    }

    /** 2026-05-28: register a callback for {@code [SUPERVISOR_USAGE]} live-usage lines. */
    public void setLiveUsageHandler(Consumer<JsonObject> handler) {
        this.liveUsageHandler = handler;
    }

    /** Phase 3: register a callback for {@code [PRE_COMPACT]} lines. See field doc. */
    public void setPreCompactHandler(Consumer<JsonObject> handler) {
        this.preCompactHandler = handler;
    }

    /** Session resume (SR3): register a callback for {@code [SUPERVISOR_SESSION]} lines. */
    public void setSessionHandler(Consumer<JsonObject> handler) {
        this.sessionHandler = handler;
    }

    /** Session resume (SR5): register a callback for {@code [SUPERVISOR_RESUME_MISS]} lines. */
    public void setResumeMissHandler(Consumer<JsonObject> handler) {
        this.resumeMissHandler = handler;
    }

    /**
     * DN9 (§16.5): subscribe to this supervisor's dedicated daemon lifecycle
     * (ready / died) by passing through to its {@link ClaudeSDKBridge}. The
     * workflow engine registers an {@code onDaemonDied} handler that funnels the
     * owning node to WAITING_HUMAN. No-op effect for non-workflow pairs.
     */
    public void setDaemonLifecycleListener(
            com.github.claudecodegui.provider.common.IBridge.DaemonLifecycleListener listener) {
        sdkBridge.setLifecycleListener(listener);
    }

    private void bufferMessage(JsonObject parsed) {
        synchronized (messageBuffer) {
            if (messageBuffer.size() >= MSG_BUFFER_CAP) {
                messageBuffer.pollFirst();
                int dropped = droppedBufferedMessages.incrementAndGet();
                if (dropped == 1 || dropped == 10 || dropped % 50 == 0) {
                    LOG.warn("[SupervisorBridge] message buffer overflow for "
                            + supervisorId + ", total dropped=" + dropped);
                }
            }
            messageBuffer.offerLast(parsed);
        }
    }

    /**
     * Start the supervisor session on the daemon. Returns a future that
     * completes once the daemon acks {@code done}.
     *
     * <p>{@code autoCompactThreshold} (optional, 50-95) is forwarded to the
     * daemon and applied as {@code CLAUDE_AUTOCOMPACT_PCT_OVERRIDE} env var —
     * lowering it from CLI's ~95% default gives the supervisor more headroom
     * before a single file-heavy review turn blows past the context window.
     * The setting is daemon-process-wide so it also applies to the main AI
     * channel sharing the same daemon (acknowledged in the design).
     *
     * <p>{@code reasoningEffort} (optional, one of low/medium/high/xhigh/max)
     * is forwarded as {@code options.effort} on the daemon-side {@code sdk.query()}
     * call. Null leaves the SDK default in place. Before 2026-05-24 this was
     * stored only on the Java {@code PairSession} and never reached the daemon
     * — so even an explicit "max" selection had no effect on supervisor turns.
     */
    public CompletableFuture<Boolean> start(
            String agentName,
            String description,
            String planContent,
            String specContent,
            String model,
            Integer autoCompactThreshold,
            String reasoningEffort,
            boolean mcpAccess
    ) {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        params.addProperty("mcpAccess", mcpAccess);
        params.addProperty("name", agentName);
        params.addProperty("description", description);
        params.addProperty("planContent", planContent);
        if (specContent != null && !specContent.isEmpty()) {
            params.addProperty("specContent", specContent);
        }
        if (model != null && !model.isEmpty()) {
            params.addProperty("model", model);
        }
        if (autoCompactThreshold != null) {
            params.addProperty("autoCompactThreshold", autoCompactThreshold.intValue());
        }
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            params.addProperty("reasoningEffort", reasoningEffort);
        }
        return sdkBridge.sendDaemonCommand("supervisor.start", params, sinkCallback("start"));
    }

    /**
     * Phase 4 (2026-05-24): variant of {@link #start} used by the
     * {@code RotationCoordinator}. Differs in three ways:
     * <ul>
     *   <li>Uses {@code explicitSupervisorId} instead of the bridge's current
     *       {@link #supervisorId} — so the new generation has its own daemon
     *       runtime key while the old one is still alive.</li>
     *   <li>Passes {@code successorPromptAppend} so the daemon appends the
     *       rendered handoff doc to the base system prompt.</li>
     *   <li>Passes {@code generation} so the daemon can include it in
     *       diagnostics + future telemetry.</li>
     * </ul>
     * Does NOT swap {@link #supervisorId} on success — that's the
     * coordinator's job (separate atomic step).
     */
    public CompletableFuture<Boolean> startWithHandoff(
            String agentName,
            String description,
            String planContent,
            String specContent,
            String model,
            Integer autoCompactThreshold,
            String reasoningEffort,
            String explicitSupervisorId,
            String successorPromptAppend,
            int generation,
            boolean mcpAccess
    ) {
        // Default: fresh start (no transcript resume). Rotation / first-ever start
        // use this; only the session-resume node path passes a resumeSessionId.
        return startWithHandoff(agentName, description, planContent, specContent, model,
                autoCompactThreshold, reasoningEffort, explicitSupervisorId,
                successorPromptAppend, generation, mcpAccess, null);
    }

    /**
     * Session resume (SR4, session-resume-plan.md): start variant that can resume
     * a prior supervisor transcript by session_id. {@code resumeSessionId == null}
     * ⇒ identical to the fresh-start overload. The daemon self-checks whether the
     * SDK honoured the resume and emits {@code [SUPERVISOR_RESUME_MISS]} if not
     * (→ Java SR6 fallback).
     */
    public CompletableFuture<Boolean> startWithHandoff(
            String agentName,
            String description,
            String planContent,
            String specContent,
            String model,
            Integer autoCompactThreshold,
            String reasoningEffort,
            String explicitSupervisorId,
            String successorPromptAppend,
            int generation,
            boolean mcpAccess,
            String resumeSessionId
    ) {
        if (explicitSupervisorId == null || explicitSupervisorId.isEmpty()) {
            throw new IllegalArgumentException("explicitSupervisorId is required for handoff start");
        }
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", explicitSupervisorId);
        params.addProperty("mcpAccess", mcpAccess);
        params.addProperty("name", agentName);
        params.addProperty("description", description);
        params.addProperty("planContent", planContent);
        if (specContent != null && !specContent.isEmpty()) {
            params.addProperty("specContent", specContent);
        }
        if (model != null && !model.isEmpty()) {
            params.addProperty("model", model);
        }
        if (autoCompactThreshold != null) {
            params.addProperty("autoCompactThreshold", autoCompactThreshold.intValue());
        }
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            params.addProperty("reasoningEffort", reasoningEffort);
        }
        if (successorPromptAppend != null && !successorPromptAppend.isEmpty()) {
            params.addProperty("successorPromptAppend", successorPromptAppend);
        }
        params.addProperty("generation", generation);
        if (resumeSessionId != null && !resumeSessionId.isEmpty()) {
            params.addProperty("resumeSessionId", resumeSessionId);
        }
        return sdkBridge.sendDaemonCommand("supervisor.start", params, sinkCallback("startWithHandoff"));
    }

    /**
     * Forward an event to the supervisor and asynchronously receive its next
     * {@code ACTION}. The future resolves to the parsed action JSON (or null
     * if the daemon returned no action line, e.g. on transport failure).
     */
    public CompletableFuture<JsonObject> postEvent(JsonObject event) {
        return postEvent(event, "user");
    }

    /**
     * Contract State Machine v3 (2026-05-25): same as {@link #postEvent(JsonObject)}
     * but the daemon enqueues the message with the given {@code role}. Pass
     * {@code "system"} for R3 DECISION_REQUEST and other framework-injected
     * messages that should not appear as a user turn in the supervisor's
     * conversation history. Pass {@code "user"} (or use the no-role overload)
     * for normal event forwarding.
     *
     * <p>Daemon support: {@code supervisor-channel.js postEventToSupervisor}
     * accepts the new {@code role} param and falls back to {@code "user"} if
     * the SDK doesn't accept the requested role.
     */
    public CompletableFuture<JsonObject> postEvent(JsonObject event, String role) {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        params.add("event", event);
        if (role != null && !role.isEmpty() && !"user".equals(role)) {
            params.addProperty("role", role);
        }

        AtomicReference<JsonObject> captured = new AtomicReference<>();
        AtomicReference<String> capturedError = new AtomicReference<>();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();

        CompletableFuture<Boolean> sendFuture = sdkBridge.sendDaemonCommand(
                "supervisor.postEvent",
                params,
                new IBridge.DaemonOutputCallback() {
                    @Override
                    public void onLine(String line) {
                        // Lines come pre-stripped of the NDJSON envelope by the daemon.
                        // We recognize two prefixes:
                        //   [SUPERVISOR_MSG]    — one raw SDK message, streamed live during
                        //                         the turn; routed to the message handler
                        //                         so the webview can render content blocks
                        //                         as they arrive
                        //   [SUPERVISOR_ACTION] — the post-turn wrapper carrying the
                        //                         emit_action result; captured for the
                        //                         CompletableFuture return value
                        if (line == null) return;
                        String trimmed = line.trim();

                        // Phase 2: surface mid-turn SDK auto-compaction events to
                        // the per-Pair counter / PairStatusPusher. We deliberately
                        // peek for this BEFORE the MSG/ACTION checks so a
                        // [COMPACT_BOUNDARY] line interleaved with normal stream
                        // messages is never dropped just because it doesn't match
                        // the more common prefixes.
                        int compactIdx = trimmed.indexOf(COMPACT_BOUNDARY_PREFIX);
                        if (compactIdx >= 0) {
                            String jsonText = trimmed.substring(compactIdx + COMPACT_BOUNDARY_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                Consumer<JsonObject> ch = compactBoundaryHandler;
                                if (ch != null) {
                                    try { ch.accept(parsed); }
                                    catch (Exception e) {
                                        LOG.warn("[SupervisorBridge] compact-boundary handler failed: " + e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse COMPACT_BOUNDARY line: "
                                        + e.getMessage() + " | line=" + trimmed);
                            }
                            return;
                        }

                        // Phase 3: STATE_UPDATE — supervisor committed a sparse
                        // L2 delta via the update_state MCP tool. Same peek-first
                        // discipline; handler routes to L2Store.applyUpdateStateDelta.
                        int stateIdx = trimmed.indexOf(STATE_UPDATE_PREFIX);
                        if (stateIdx >= 0) {
                            String jsonText = trimmed.substring(stateIdx + STATE_UPDATE_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                Consumer<JsonObject> sh = stateUpdateHandler;
                                if (sh != null) {
                                    try { sh.accept(parsed); }
                                    catch (Exception e) {
                                        LOG.warn("[SupervisorBridge] state-update handler failed: " + e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse STATE_UPDATE line: "
                                        + e.getMessage() + " | line=" + trimmed);
                            }
                            return;
                        }

                        // Phase 3: PRE_COMPACT — SDK is about to auto-compact.
                        // Java writes a timestamped L2 snapshot for rotation
                        // fallback. Fast path; do not block the SDK on Java I/O.
                        int preIdx = trimmed.indexOf(PRE_COMPACT_PREFIX);
                        if (preIdx >= 0) {
                            String jsonText = trimmed.substring(preIdx + PRE_COMPACT_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                Consumer<JsonObject> ph = preCompactHandler;
                                if (ph != null) {
                                    try { ph.accept(parsed); }
                                    catch (Exception e) {
                                        LOG.warn("[SupervisorBridge] pre-compact handler failed: " + e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse PRE_COMPACT line: "
                                        + e.getMessage() + " | line=" + trimmed);
                            }
                            return;
                        }

                        // Session resume (SR3/SR5): capture the SDK session_id /
                        // resume-miss side channels. Peek-first like the others so a
                        // line interleaved with stream messages is never swallowed.
                        int sessionIdx = trimmed.indexOf(SESSION_LINE_PREFIX);
                        if (sessionIdx >= 0) {
                            String jsonText = trimmed.substring(sessionIdx + SESSION_LINE_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                Consumer<JsonObject> h = sessionHandler;
                                if (h != null) {
                                    try { h.accept(parsed); }
                                    catch (Exception e) {
                                        LOG.warn("[SupervisorBridge] session handler failed: " + e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse SUPERVISOR_SESSION line: "
                                        + e.getMessage() + " | line=" + trimmed);
                            }
                            return;
                        }
                        int resumeMissIdx = trimmed.indexOf(RESUME_MISS_PREFIX);
                        if (resumeMissIdx >= 0) {
                            String jsonText = trimmed.substring(resumeMissIdx + RESUME_MISS_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                Consumer<JsonObject> h = resumeMissHandler;
                                if (h != null) {
                                    try { h.accept(parsed); }
                                    catch (Exception e) {
                                        LOG.warn("[SupervisorBridge] resume-miss handler failed: " + e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse SUPERVISOR_RESUME_MISS line: "
                                        + e.getMessage() + " | line=" + trimmed);
                            }
                            return;
                        }

                        // 2026-05-28: live output-token estimate. Peek before the
                        // MSG/ACTION checks (same discipline as the other side
                        // channels) so a usage tick interleaved with stream
                        // messages is never swallowed by a more common prefix.
                        int usageIdx = trimmed.indexOf(LIVE_USAGE_PREFIX);
                        if (usageIdx >= 0) {
                            String jsonText = trimmed.substring(usageIdx + LIVE_USAGE_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                Consumer<JsonObject> uh = liveUsageHandler;
                                if (uh != null) {
                                    try { uh.accept(parsed); }
                                    catch (Exception e) {
                                        LOG.warn("[SupervisorBridge] live-usage handler failed: " + e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse SUPERVISOR_USAGE line: "
                                        + e.getMessage() + " | line=" + trimmed);
                            }
                            return;
                        }

                        int msgIdx = trimmed.indexOf(MSG_LINE_PREFIX);
                        if (msgIdx >= 0) {
                            String jsonText = trimmed.substring(msgIdx + MSG_LINE_PREFIX.length()).trim();
                            JsonObject parsed;
                            try {
                                parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse MSG line: " + e.getMessage()
                                        + " | line=" + trimmed);
                                return;
                            }
                            // Phase 0: if no handler yet, buffer instead of silent-drop. A handler
                            // registered later (via setMessageHandler) will receive the backlog in order.
                            Consumer<JsonObject> handler = messageHandler;
                            if (handler == null) {
                                bufferMessage(parsed);
                            } else {
                                try {
                                    handler.accept(parsed);
                                } catch (Exception e) {
                                    LOG.warn("[SupervisorBridge] handler delivery failed: " + e.getMessage());
                                }
                            }
                            return;
                        }

                        int idx = trimmed.indexOf(ACTION_LINE_PREFIX);
                        if (idx >= 0) {
                            String jsonText = trimmed.substring(idx + ACTION_LINE_PREFIX.length()).trim();
                            try {
                                JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                                captured.set(parsed);
                            } catch (Exception e) {
                                LOG.warn("[SupervisorBridge] Failed to parse ACTION line: " + e.getMessage()
                                        + " | line=" + trimmed);
                            }
                        }
                    }

                    @Override
                    public void onStderr(String text) {
                        if (text != null && !text.isBlank()) {
                            LOG.debug("[SupervisorBridge:stderr] " + text);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        String translated = translateDaemonError(error);
                        LOG.warn("[SupervisorBridge] Daemon error: " + translated);
                        if (translated != null) capturedError.set(translated);
                    }

                    @Override
                    public void onComplete(boolean success) {
                        if (success) {
                            result.complete(captured.get());
                        } else {
                            String msg = capturedError.get();
                            result.completeExceptionally(new RuntimeException(
                                    msg != null
                                        ? "supervisor.postEvent failed: " + msg
                                        : "supervisor.postEvent did not complete successfully"));
                        }
                    }
                }
        );

        // Propagate transport-level failure (e.g. daemon unavailable) to the result.
        sendFuture.whenComplete((ok, err) -> {
            if (err != null && !result.isDone()) {
                result.completeExceptionally(err);
            }
        });

        return result;
    }

    /**
     * Stop the supervisor session on the daemon. Idempotent.
     */
    public CompletableFuture<Boolean> stop() {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        return sdkBridge.sendDaemonCommand("supervisor.stop", params, sinkCallback("stop"));
    }

    /**
     * 2026-05-28 (daemon split): tear down the supervisor's DEDICATED daemon
     * session. Each pair now owns its own {@link ClaudeSDKBridge} → its own
     * ai-bridge-server session → its own daemon child process (so the supervisor
     * channel no longer shares the main AI's command queue). {@link #stop()}
     * only disposes the in-daemon SDK runtime; this kills the transport itself
     * (RemoteBridge.stop → DELETE /session) so the daemon process exits instead
     * of leaking until the server reaps it. Idempotent / best-effort.
     */
    public void shutdownTransport() {
        try {
            sdkBridge.shutdownDaemon();
        } catch (Exception e) {
            LOG.warn("[SupervisorBridge] shutdownTransport failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Phase 4: stop a specific supervisor session by id (rather than the
     * bridge's current one). Used by {@code RotationCoordinator} to retire
     * the OLD daemon runtime after the new one is alive.
     */
    public CompletableFuture<Boolean> stopById(String explicitSupervisorId) {
        if (explicitSupervisorId == null || explicitSupervisorId.isEmpty()) {
            throw new IllegalArgumentException("explicitSupervisorId is required");
        }
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", explicitSupervisorId);
        return sdkBridge.sendDaemonCommand("supervisor.stop", params, sinkCallback("stopById"));
    }

    /**
     * Phase 4: ask the current supervisor to produce a handoff document.
     * The daemon's {@code supervisor.produceHandoff} command enqueues
     * {@code producerPrompt} as a user message, runs the next turn, and
     * emits exactly one {@code [HANDOFF_DOC]} line carrying the model's
     * JSON output. The future resolves to the parsed envelope.
     */
    public CompletableFuture<JsonObject> produceHandoff(String producerPrompt) {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        params.addProperty("prompt", producerPrompt);
        return singleLineRequest("supervisor.produceHandoff", HANDOFF_DOC_PREFIX, "produceHandoff", params);
    }

    /**
     * Phase 2: query the daemon for the supervisor runtime's cheap health
     * snapshot (liveness, age, compactCount, inputStream pending size).
     * Does NOT touch the SDK — safe to call every monitor tick.
     */
    public CompletableFuture<JsonObject> health() {
        return singleLineRequest("supervisor.health", HEALTH_PREFIX, "health");
    }

    /**
     * Phase 2: ask the SDK for the real per-category context-window usage.
     * Round-trips into the CLI host so may take ~100ms-1s — call from the
     * monitor's idle health-check path, not on every event.
     */
    public CompletableFuture<JsonObject> getContextUsage() {
        return singleLineRequest("supervisor.getContextUsage", CONTEXT_USAGE_PREFIX, "getContextUsage");
    }

    /**
     * Phase 2: best-effort interrupt of the supervisor's currently-running
     * turn. Used by the monitor on tick timeout (escalation step before
     * Phase 4+ rotation).
     */
    public CompletableFuture<JsonObject> interrupt() {
        return singleLineRequest("supervisor.interrupt", INTERRUPT_RESULT_PREFIX, "interrupt");
    }

    /**
     * Generic single-line request: send a {@code supervisor.*} method, wait
     * for the daemon to emit exactly one tagged response line carrying the
     * payload, and complete the future with the parsed object (or null if
     * the daemon completed without ever writing the line).
     */
    private CompletableFuture<JsonObject> singleLineRequest(String method, String linePrefix, String opTag) {
        JsonObject params = new JsonObject();
        params.addProperty("pairId", pairId);
        params.addProperty("supervisorId", supervisorId);
        return singleLineRequest(method, linePrefix, opTag, params);
    }

    /**
     * Phase 4: overload that takes a pre-built params object so callers (e.g.
     * {@link #produceHandoff}) can include extra fields beyond pairId/supervisorId.
     */
    private CompletableFuture<JsonObject> singleLineRequest(String method, String linePrefix, String opTag, JsonObject params) {
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        AtomicReference<String> capturedError = new AtomicReference<>();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();

        CompletableFuture<Boolean> sendFuture = sdkBridge.sendDaemonCommand(
                method,
                params,
                new IBridge.DaemonOutputCallback() {
                    @Override
                    public void onLine(String line) {
                        if (line == null) return;
                        String trimmed = line.trim();
                        int idx = trimmed.indexOf(linePrefix);
                        if (idx < 0) return;
                        String jsonText = trimmed.substring(idx + linePrefix.length()).trim();
                        try {
                            captured.set(JsonParser.parseString(jsonText).getAsJsonObject());
                        } catch (Exception e) {
                            LOG.warn("[SupervisorBridge:" + opTag + "] Failed to parse "
                                    + linePrefix + " line: " + e.getMessage() + " | line=" + trimmed);
                        }
                    }
                    @Override
                    public void onStderr(String text) {
                        if (text != null && !text.isBlank()) {
                            LOG.debug("[SupervisorBridge:" + opTag + ":stderr] " + text);
                        }
                    }
                    @Override
                    public void onError(String error) {
                        String translated = translateDaemonError(error);
                        LOG.warn("[SupervisorBridge:" + opTag + "] Daemon error: " + translated);
                        if (translated != null) capturedError.set(translated);
                    }
                    @Override
                    public void onComplete(boolean success) {
                        if (success) {
                            result.complete(captured.get());
                        } else {
                            String msg = capturedError.get();
                            result.completeExceptionally(new RuntimeException(
                                    msg != null
                                        ? method + " failed: " + msg
                                        : method + " did not complete successfully"));
                        }
                    }
                }
        );

        sendFuture.whenComplete((ok, err) -> {
            if (err != null && !result.isDone()) {
                result.completeExceptionally(err);
            }
        });
        return result;
    }

    private IBridge.DaemonOutputCallback sinkCallback(String op) {
        return new IBridge.DaemonOutputCallback() {
            @Override
            public void onLine(String line) {
                LOG.debug("[SupervisorBridge:" + op + "] " + line);
                // Appendix B: sniff daemon's protocol advertisement from the
                // [supervisor] started log line ("...protocol=v2"). The daemon
                // response also includes protocolVersion as a field, but the
                // bridge result is just Boolean; sniffing the log line gives
                // us the diagnostic without expanding the bridge API.
                if (("start".equals(op) || "startWithHandoff".equals(op))
                        && line != null && line.contains("protocol=")) {
                    int idx = line.indexOf("protocol=");
                    String tail = line.substring(idx + "protocol=".length());
                    int end = tail.indexOf(')');
                    String reported = end >= 0 ? tail.substring(0, end).trim() : tail.trim();
                    if (!CURRENT_PROTOCOL_VERSION.equals(reported)) {
                        LOG.warn("[SupervisorBridge] daemon protocol mismatch: daemon=" + reported
                                + " java=" + CURRENT_PROTOCOL_VERSION + " — features may not align");
                    }
                }
            }
            @Override
            public void onStderr(String text) {
                if (text != null && !text.isBlank()) {
                    LOG.debug("[SupervisorBridge:" + op + ":stderr] " + text);
                }
            }
            @Override
            public void onError(String error) {
                LOG.warn("[SupervisorBridge:" + op + "] " + translateDaemonError(error));
            }
            @Override
            public void onComplete(boolean success) {
                LOG.debug("[SupervisorBridge:" + op + "] complete success=" + success);
            }
        };
    }
}
