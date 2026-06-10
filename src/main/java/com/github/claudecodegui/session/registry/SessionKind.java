package com.github.claudecodegui.session.registry;

/**
 * Session-kind refactor: the kind of a registered session container.
 *
 * <p>Only kinds that have a "two-leg" identity (main AI + supervisor) and thus
 * need the containerId/manifest join table are listed here. Plain <b>normal</b>
 * sessions never enter the {@code SessionRegistry} — they live as bare
 * {@code ~/.claude/projects/*.jsonl} and route by their own sessionId — so
 * "normal" is intentionally absent (keeps normal sessions zero-touch).
 */
public enum SessionKind {
    SUPERVISED,
    WORKFLOW
}
