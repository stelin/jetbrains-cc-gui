/**
 * usageModeCallbacks.ts
 *
 * Registers window bridge callbacks for usage statistics, permission modes, and
 * model/provider updates: onUsageUpdate, onModeChanged, onModeReceived,
 * onModelChanged, onModelConfirmed, updateActiveProvider, updateThinkingEnabled,
 * updateStreamingEnabled, updateSendShortcut, updateAutoOpenFileEnabled.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode } from '../../../components/ChatInputBox/types';
import { isValidPermissionMode, normalizeClaudeModelId } from '../../../components/ChatInputBox/types';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';

export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    addToast,
    t,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    currentProviderRef,
    syncActiveProviderModelMapping,
    handleLongContextChange,
  } = options;

  window.onUsageUpdate = (json) => {
    try {
      const data = JSON.parse(json);

      // Supervisor pane shares this callback. Java sets scope="supervisor"
      // (with supervisorId=<agentId>) when the snapshot is for a supervisor
      // pair; we forward it via CustomEvent so PairContext can react without
      // owning its own window.* callback. Main AI payloads have no scope (or
      // scope="main") and fall through to the original path — zero behavior
      // change for the main-AI flow.
      if (data && data.scope === 'supervisor') {
        try {
          window.dispatchEvent(
            new CustomEvent('cc-gui:supervisor-usage', { detail: data }),
          );
        } catch (e) {
          console.error('[Frontend] supervisor usage dispatch failed:', e);
        }
        return;
      }

      if (typeof data.percentage === 'number') {
        const used =
          typeof data.usedTokens === 'number'
            ? data.usedTokens
            : typeof data.totalTokens === 'number'
              ? data.totalTokens
              : undefined;
        const max =
          typeof data.maxTokens === 'number'
            ? data.maxTokens
            : typeof data.limit === 'number'
              ? data.limit
              : undefined;

        if (used !== undefined && max !== undefined && used > max * 2) {
          console.warn(
            '[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max,
          );
        }

        const safePercentage = Math.max(0, Math.min(100, data.percentage));
        setUsagePercentage(safePercentage);
        setUsageUsedTokens(used);
        setUsageMaxTokens(max);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  };

  const updateMode = (mode?: PermissionMode, providerOverride?: string) => {
    const activeProvider = providerOverride || currentProviderRef.current;
    if (isValidPermissionMode(mode)) {
      const nextMode: PermissionMode =
        activeProvider === 'codex' && mode === 'plan' ? 'default' : mode;
      setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      if (activeProvider === 'codex') {
        setCodexPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      } else {
        setClaudePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      }
    }
  };

  window.onModeChanged = (mode) => updateMode(mode as PermissionMode);
  window.onModeReceived = (mode) => updateMode(mode as PermissionMode);

  window.onModelChanged = (modelId) => {
    const provider = currentProviderRef.current;
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'codex') {
      setSelectedCodexModel(modelId);
    }
  };

  window.onModelConfirmed = (modelId, provider) => {
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'codex') {
      setSelectedCodexModel(modelId);
    }
  };

  window.updateActiveProvider = (jsonStr: string) => {
    try {
      const provider = JSON.parse(jsonStr);
      syncActiveProviderModelMapping(provider);
      setProviderConfigVersion((prev) => prev + 1);
      setActiveProviderConfig(provider);
    } catch (error) {
      console.error('[Frontend] Failed to parse active provider in App:', error);
    }
  };

  window.updateThinkingEnabled = (jsonStr: string) => {
    const trimmed = (jsonStr || '').trim();
    try {
      const data = JSON.parse(trimmed);
      if (typeof data === 'boolean') {
        setClaudeSettingsAlwaysThinkingEnabled(data);
        return;
      }
      if (data && typeof data.enabled === 'boolean') {
        setClaudeSettingsAlwaysThinkingEnabled(data.enabled);
        return;
      }
    } catch {
      if (trimmed === 'true' || trimmed === 'false') {
        setClaudeSettingsAlwaysThinkingEnabled(trimmed === 'true');
      }
    }
  };

  window.updateStreamingEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setStreamingEnabledSetting(data.streamingEnabled ?? true);
    } catch (error) {
      console.error('[Frontend] Failed to parse streaming enabled:', error);
    }
  };

  window.updateSendShortcut = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      if (data.sendShortcut === 'enter' || data.sendShortcut === 'cmdEnter') {
        setSendShortcut(data.sendShortcut);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse send shortcut:', error);
    }
  };

  window.updateAutoOpenFileEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setAutoOpenFileEnabled(data.autoOpenFileEnabled ?? false);
    } catch (error) {
      console.error('[Frontend] Failed to parse auto open file enabled:', error);
    }
  };

  // Structured Claude API error classification forwarded from the bridge.
  // For now we only act on LONG_CONTEXT_NOT_ENTITLED: turn the 1M toggle off
  // (which also re-syncs the model without the [1m] suffix and persists the
  // preference via the usual save effect), and surface a toast so the user
  // knows why their original send failed.
  window.onClaudeErrorCode = (code) => {
    if (code === 'LONG_CONTEXT_NOT_ENTITLED') {
      handleLongContextChange(false);
      addToast(
        t('models.longContext.notEntitledToast', {
          defaultValue:
            '1M context is not available on your Claude plan — disabled automatically. Please resend.',
        }),
        'warning',
      );
    }
  };

  // Drain any pending settings that arrived before callback registration
  drainPendingSettings();
  // Kick off initial settings requests
  startInitialSettingsRequest();
}
