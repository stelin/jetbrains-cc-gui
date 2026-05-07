import { memo } from 'react';
import type { TFunction } from 'i18next';

interface ProviderNotConfiguredCardProps {
  t: TFunction;
  onNavigateToSettings?: () => void;
}

declare global {
  interface Window {
    __INITIAL_REMOTE_MODE__?: boolean;
  }
}

const SettingsIcon = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path
      d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.5" />
  </svg>
);

const ArrowRightIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M5 12h14m-7-7 7 7-7 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

/**
 * Detects whether an error message indicates a provider-not-configured error.
 * The backend throws "API Key not configured and no CLI session found" and may
 * append Node.js diagnostics, so we match on the leading substring.
 */
export function isProviderNotConfiguredError(errorText: string): boolean {
  return errorText.includes('API Key not configured')
    || errorText.includes('local configuration access is not authorized')
    || errorText.includes('本地配置读取未获授权');
}

export const ProviderNotConfiguredCard = memo(function ProviderNotConfiguredCard({
  t,
  onNavigateToSettings,
}: ProviderNotConfiguredCardProps) {
  // In remote mode the provider management UI is disabled (the daemon runs on
  // a remote ai-bridge-server inside a container). The default "go to settings"
  // CTA cannot resolve the issue — swap copy and hide the button.
  const isRemote = typeof window !== 'undefined' && window.__INITIAL_REMOTE_MODE__ === true;
  const titleKey = isRemote ? 'error.providerNotConfiguredRemote' : 'error.providerNotConfigured';
  const descKey = isRemote ? 'error.providerNotConfiguredRemoteDesc' : 'error.providerNotConfiguredDesc';
  const showCta = !isRemote && Boolean(onNavigateToSettings);

  return (
    <div className="provider-not-configured-card">
      <div className="provider-card-header">
        <span className="provider-card-icon">
          <SettingsIcon />
        </span>
        <span className="provider-card-title">
          {t(titleKey)}
        </span>
      </div>
      <p className="provider-card-description">
        {t(descKey)}
      </p>
      {showCta && (
        <button
          type="button"
          className="provider-card-action"
          onClick={onNavigateToSettings}
        >
          {t('error.goToProviderSettings')}
          <ArrowRightIcon />
        </button>
      )}
    </div>
  );
});
