package com.github.claudecodegui.session.pair;

/**
 * Phase 1 (2026-05-23): supervisor health state machine.
 *
 * <ul>
 *   <li>{@link #HEALTHY} — last tick succeeded.</li>
 *   <li>{@link #DEGRADED} — one tick failed (e.g. wall-clock timeout). Next tick
 *       still runs normally; if it succeeds, transition back to HEALTHY.</li>
 *   <li>{@link #UNHEALTHY} — two or more consecutive failures. In Phase 1 this
 *       only marks the request to rotate (no rotation infrastructure yet); in
 *       Phase 4+ this triggers an automatic L2-fallback rotation.</li>
 * </ul>
 */
public enum HealthState {
    HEALTHY,
    DEGRADED,
    UNHEALTHY
}
