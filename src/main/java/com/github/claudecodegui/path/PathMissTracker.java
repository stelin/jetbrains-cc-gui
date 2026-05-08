package com.github.claudecodegui.path;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-scoped collector for paths that did not match the configured
 * mapping root. Outbound misses are surfaced to the user via a badge in the
 * remote-mode settings panel; inbound misses are kept for diagnostics only.
 *
 * <p>Capped at {@value #MAX_SAMPLES} entries per direction to bound memory.
 */
@Service(Service.Level.PROJECT)
public final class PathMissTracker {

    private static final int MAX_SAMPLES = 200;

    private final Set<String> outbound = ConcurrentHashMap.newKeySet();
    private final Set<String> inbound  = ConcurrentHashMap.newKeySet();

    public static PathMissTracker getInstance(Project project) {
        return project.getService(PathMissTracker.class);
    }

    public void recordOutboundMiss(String p) {
        if (p != null && outbound.size() < MAX_SAMPLES) {
            outbound.add(p);
        }
    }

    public void recordInboundMiss(String p) {
        if (p != null && inbound.size() < MAX_SAMPLES) {
            inbound.add(p);
        }
    }

    public int outboundCount() { return outbound.size(); }

    public int inboundCount()  { return inbound.size(); }

    public List<String> outboundSamples(int max) {
        List<String> out = new ArrayList<>(Math.min(max, outbound.size()));
        for (String s : outbound) {
            if (out.size() >= max) break;
            out.add(s);
        }
        return out;
    }

    public List<String> inboundSamples(int max) {
        List<String> out = new ArrayList<>(Math.min(max, inbound.size()));
        for (String s : inbound) {
            if (out.size() >= max) break;
            out.add(s);
        }
        return out;
    }

    public void clear() {
        outbound.clear();
        inbound.clear();
    }
}
