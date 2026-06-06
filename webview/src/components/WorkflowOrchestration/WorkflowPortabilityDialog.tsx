import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ConfigProvider, Modal, theme as antdTheme } from 'antd';
import { copyToClipboard } from '../../utils/copyUtils';
import styles from './style.module.less';

export type PortabilityMode = 'import' | 'export' | 'rules';

interface Props {
  mode: PortabilityMode;
  open: boolean;
  onClose: () => void;
  /** export / rules: the read-only text to show + copy. */
  text?: string;
  /** import: parse + load as draft. Returns warnings/error. */
  onImport?: (text: string) => { ok: boolean; warnings: string[]; error?: string };
}

/** The webview mirrors the IDE theme onto <html data-theme>; default to dark. */
function isDarkTheme(): boolean {
  return (document.documentElement.getAttribute('data-theme') || 'dark') !== 'light';
}

/**
 * One modal, three modes (import / export / rules) for workflow portability.
 * Text-only (copy / paste) — no file IO. Import loads an UNSAVED draft and shows
 * a result view with any warnings before the user closes and reviews on canvas.
 */
export default function WorkflowPortabilityDialog({ mode, open, onClose, text, onImport }: Props) {
  const { t } = useTranslation();
  const [pasted, setPasted] = useState('');
  const [result, setResult] = useState<{ ok: boolean; warnings: string[]; error?: string } | null>(null);
  const [copied, setCopied] = useState(false);

  // Reset transient state whenever the dialog opens or switches mode.
  useEffect(() => {
    if (open) { setPasted(''); setResult(null); setCopied(false); }
  }, [open, mode]);

  const title = mode === 'import'
    ? t('workflow.io.importTitle', '导入工作流')
    : mode === 'export'
      ? t('workflow.io.exportTitle', '导出工作流')
      : t('workflow.io.rulesTitle', '工作流定义规则');

  const doCopy = async () => {
    const ok = await copyToClipboard(text || '');
    if (ok) { setCopied(true); window.setTimeout(() => setCopied(false), 1500); }
  };

  const doImport = () => {
    if (!onImport) return;
    setResult(onImport(pasted));
  };

  return (
    <ConfigProvider theme={{ algorithm: isDarkTheme() ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm }}>
      <Modal
        open={open}
        title={title}
        onCancel={onClose}
        footer={null}
        width={640}
      >
        {/* ── export / rules: read-only text + copy ── */}
        {(mode === 'export' || mode === 'rules') && (
          <div className={styles.ioBody}>
            <textarea className={styles.ioTextarea} readOnly value={text || ''} spellCheck={false} />
            <div className={styles.ioActions}>
              <button className={styles.secondaryBtn} onClick={doCopy}>
                <span className="codicon codicon-copy" />{' '}
                {copied ? t('workflow.io.copied', '已复制') : t('workflow.io.copy', '复制')}
              </button>
            </div>
          </div>
        )}

        {/* ── import: paste → result ── */}
        {mode === 'import' && (
          result?.ok ? (
            <div className={styles.ioBody}>
              <div className={styles.ioSuccess}>
                <span className="codicon codicon-check" />{' '}
                {t('workflow.io.imported', '已导入，请在画布中复核监督者等设置后保存。')}
              </div>
              {result.warnings.length > 0 && (
                <ul className={styles.ioWarnList}>
                  {result.warnings.map((w, i) => (
                    <li key={i}><span className="codicon codicon-warning" /> {w}</li>
                  ))}
                </ul>
              )}
              <div className={styles.ioActions}>
                <button className={styles.runBtn} onClick={onClose}>{t('common.done', '完成')}</button>
              </div>
            </div>
          ) : (
            <div className={styles.ioBody}>
              <textarea
                className={styles.ioTextarea}
                value={pasted}
                spellCheck={false}
                placeholder={t('workflow.io.pastePlaceholder', '粘贴工作流 JSON（可由 LLM 按「查看规则」生成）…')}
                onChange={(e) => setPasted(e.target.value)}
              />
              {result && !result.ok && (
                <div className={styles.ioError}>
                  <span className="codicon codicon-error" /> {result.error}
                </div>
              )}
              <div className={styles.ioActions}>
                <button className={styles.secondaryBtn} onClick={onClose}>{t('common.cancel', '取消')}</button>
                <button className={styles.runBtn} onClick={doImport} disabled={!pasted.trim()}>
                  <span className="codicon codicon-cloud-download" /> {t('workflow.io.import', '导入')}
                </button>
              </div>
            </div>
          )
        )}
      </Modal>
    </ConfigProvider>
  );
}
