package com.github.claudecodegui.path;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.PathMappingConfig;
import com.github.claudecodegui.settings.RemoteModeContext;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

/**
 * Project-scoped holder of the active {@link PathMapper}.
 *
 * <p>Resolves on each {@link #rebuild()}:
 * <pre>
 *   remote mode + isUsable config  →  DefaultPathMapper
 *   anything else                  →  IdentityPathMapper
 * </pre>
 *
 * <p>Callers should fetch via {@link #get()} just-in-time rather than caching
 * the {@link PathMapper} reference, so that config changes take effect after
 * the next {@link #rebuild()}.
 */
@Service(Service.Level.PROJECT)
public final class PathMapperHolder {

    private static final Logger LOG = Logger.getInstance(PathMapperHolder.class);

    private final Project project;
    private volatile PathMapper current = IdentityPathMapper.INSTANCE;

    public PathMapperHolder(Project project) {
        this.project = project;
        rebuild();
    }

    public static PathMapperHolder getInstance(Project project) {
        return project.getService(PathMapperHolder.class);
    }

    public PathMapper get() {
        return current;
    }

    /** Re-evaluate the active mapper from current settings. */
    public void rebuild() {
        try {
            boolean remote = RemoteModeContext.getInstance().isRemote();
            String  base   = project.getBasePath();
            if (!remote || base == null) {
                current = IdentityPathMapper.INSTANCE;
                return;
            }
            PathMappingConfig cfg = new CodemossSettingsService().getPathMappingConfig(base);
            if (!cfg.isUsable()) {
                current = IdentityPathMapper.INSTANCE;
                return;
            }
            current = new DefaultPathMapper(cfg, PathMissTracker.getInstance(project));
            LOG.info("[PathMapperHolder] Rebuilt: project=" + base
                    + " " + cfg.localOs + ":" + cfg.localRoot
                    + " <-> " + cfg.remoteOs + ":" + cfg.remoteRoot);
        } catch (Exception e) {
            LOG.warn("[PathMapperHolder] rebuild failed: " + e.getMessage());
            current = IdentityPathMapper.INSTANCE;
        }
    }
}
