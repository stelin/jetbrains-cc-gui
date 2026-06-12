import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import DOMPurify from 'dompurify';
import MarkdownBlock from '../MarkdownBlock';
import { sendBridgeEvent, openBrowser } from '../../utils/bridge';
import styles from './detailModal.module.less';

/* ──────────────────────────────────────────────────────────────────
 * BugDetailModal
 *
 * 「查看详情」弹窗: 拉取单个云效缺陷的 基础信息 + 描述(BUG内容, 图片已由
 * Java 内联为 data URI 渲染) + 附件(可下载)。
 *
 * Wires to Java:
 *   - load_yunxiao_bug_detail     → window.onYunxiaoBugDetail
 *   - download_yunxiao_attachment → window.onYunxiaoAttachmentUrl (→ openBrowser)
 * ──────────────────────────────────────────────────────────────── */

interface Attachment {
  id: string;
  name?: string;
  size?: number;
  suffix?: string;
}

interface Comment {
  content?: string;
  author?: string;
  gmtCreate?: number;
}

interface BugDetail {
  basic?: {
    identifier?: string;
    serialNumber?: string;
    subject?: string;
    status?: string;
    assignedTo?: string;
    creator?: string;
    priority?: string;
    gmtCreate?: number;
    gmtModified?: number;
  };
  formatType?: string;
  description?: string;
  attachments?: Attachment[];
  comments?: Comment[];
}

interface BugDetailModalProps {
  bugId: string;
  onClose: () => void;
}

const fmtSize = (n?: number): string => {
  if (!n || n <= 0) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

const fmtTime = (ms?: number): string => {
  if (!ms) return '';
  try {
    return new Date(ms).toLocaleString();
  } catch {
    return '';
  }
};

export function BugDetailModal({ bugId, onClose }: BugDetailModalProps) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<BugDetail | null>(null);

  // Bottom comment entry (fixed; the body scrolls behind it).
  const [commentOpen, setCommentOpen] = useState(false);
  const [commentText, setCommentText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [uploading, setUploading] = useState(false);
  const commentInputRef = useRef<HTMLTextAreaElement>(null);

  // JCEF webview: a textarea's `autoFocus` fires too early (during mount, before the
  // embedded browser is ready to focus it) and click-focus can also be flaky on a
  // freshly-shown element. Focus it explicitly a frame after it appears.
  useEffect(() => {
    if (!commentOpen) return;
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => commentInputRef.current?.focus());
    });
    const fallback = setTimeout(() => commentInputRef.current?.focus(), 80);
    return () => {
      cancelAnimationFrame(raf1);
      if (raf2) cancelAnimationFrame(raf2);
      clearTimeout(fallback);
    };
  }, [commentOpen]);

  useEffect(() => {
    window.onYunxiaoBugDetail = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; bugId?: string; detail?: BugDetail; error?: string };
        if (r.bugId && r.bugId !== bugId) return; // response for a different bug
        setLoading(false);
        if (r.ok && r.detail) {
          setDetail(r.detail);
          setError(null);
        } else {
          setError(r.error || 'load failed');
        }
      } catch {
        setLoading(false);
        setError('parse failed');
      }
    };
    window.onYunxiaoAttachmentUrl = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; url?: string; error?: string };
        if (r.ok && r.url) openBrowser(r.url);
      } catch {
        /* ignore */
      }
    };
    setLoading(true);
    setError(null);
    setDetail(null);
    sendBridgeEvent('load_yunxiao_bug_detail', JSON.stringify({ bugId }));
    return () => {
      delete window.onYunxiaoBugDetail;
      delete window.onYunxiaoAttachmentUrl;
    };
  }, [bugId]);

  // Comment submit / image-upload callbacks.
  useEffect(() => {
    window.onYunxiaoCommentAdded = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; error?: string };
        setSubmitting(false);
        if (r.ok) {
          setCommentText('');
          setCommentOpen(false);
          // Reload detail (no full-modal spinner) so the new comment shows.
          sendBridgeEvent('load_yunxiao_bug_detail', JSON.stringify({ bugId }));
        }
      } catch {
        setSubmitting(false);
      }
    };
    window.onYunxiaoCommentImage = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; markdown?: string; error?: string };
        setUploading(false);
        if (r.ok && r.markdown) {
          // Append the embed markdown (云效 renders it; our detail inlines it on reload).
          setCommentText((prev) => (prev && !prev.endsWith('\n') ? prev + '\n' : prev) + r.markdown + '\n');
        }
      } catch {
        setUploading(false);
      }
    };
    return () => {
      delete window.onYunxiaoCommentAdded;
      delete window.onYunxiaoCommentImage;
    };
  }, [bugId]);

  // ESC closes the modal.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const isMarkdown = (detail?.formatType || '').toUpperCase() === 'MARKDOWN';

  // RICHTEXT description is sanitized HTML; MARKDOWN goes through MarkdownBlock.
  const richHtml = useMemo(() => {
    const d = detail?.description || '';
    if (!d || isMarkdown) return '';
    return DOMPurify.sanitize(d, { ADD_ATTR: ['target'] });
  }, [detail?.description, isMarkdown]);

  const basic = detail?.basic || {};

  const onDownload = (att: Attachment) => {
    sendBridgeEvent(
      'download_yunxiao_attachment',
      JSON.stringify({ bugId, attachmentId: att.id, name: att.name || '' }),
    );
  };

  // Paste: images upload to 云效 (insert embed markdown); text falls through to default.
  const onPasteComment = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (item.type.startsWith('image/')) {
        e.preventDefault();
        const file = item.getAsFile();
        if (!file) continue;
        const reader = new FileReader();
        reader.onload = () => {
          const dataUrl = String(reader.result || '');
          const m = dataUrl.match(/^data:([^;]+);base64,(.*)$/);
          if (!m) return;
          setUploading(true);
          sendBridgeEvent(
            'upload_yunxiao_comment_image',
            JSON.stringify({
              bugId,
              fileName: file.name || `paste-${Date.now()}.png`,
              contentType: m[1],
              dataBase64: m[2],
            }),
          );
        };
        reader.readAsDataURL(file);
        return;
      }
    }
  };

  const submitComment = () => {
    const text = commentText.trim();
    if (!text || submitting) return;
    setSubmitting(true);
    sendBridgeEvent('submit_yunxiao_comment', JSON.stringify({ bugId, content: text }));
  };

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <div className={styles.headTitle}>
            {basic.serialNumber && <span className={styles.serial}>BUG-{basic.serialNumber}</span>}
            <span className={styles.subject} title={basic.subject}>
              {basic.subject || t('bugList.detail.untitled')}
            </span>
          </div>
          <button type="button" className={styles.closeBtn} onClick={onClose} title={t('common.close')}>
            ×
          </button>
        </div>

        <div className={styles.body}>
          {loading && <div className={styles.state}>{t('bugList.detail.loading')}</div>}
          {error && <div className={styles.stateErr}>{t('bugList.detail.error', { error })}</div>}

          {!loading && !error && detail && (
            <>
              <div className={styles.meta}>
                {basic.status && (
                  <span className={styles.metaItem}>{t('bugList.detail.status')}: {basic.status}</span>
                )}
                {basic.assignedTo && (
                  <span className={styles.metaItem}>{t('bugList.detail.assignee')}: {basic.assignedTo}</span>
                )}
                {basic.creator && (
                  <span className={styles.metaItem}>{t('bugList.detail.creator')}: {basic.creator}</span>
                )}
                {basic.priority && (
                  <span className={styles.metaItem}>{t('bugList.detail.priority')}: {basic.priority}</span>
                )}
                {!!basic.gmtCreate && (
                  <span className={styles.metaItem}>{t('bugList.detail.created')}: {fmtTime(basic.gmtCreate)}</span>
                )}
              </div>

              <div className={styles.sectionTitle}>{t('bugList.detail.content')}</div>
              <div className={styles.desc}>
                {detail.description ? (
                  isMarkdown ? (
                    <MarkdownBlock content={detail.description} />
                  ) : (
                    <div className="markdown-content" dangerouslySetInnerHTML={{ __html: richHtml }} />
                  )
                ) : (
                  <div className={styles.empty}>{t('bugList.detail.noContent')}</div>
                )}
              </div>

              <div className={styles.sectionTitle}>
                {t('bugList.detail.attachments')} ({detail.attachments?.length || 0})
              </div>
              {detail.attachments && detail.attachments.length > 0 ? (
                <ul className={styles.attList}>
                  {detail.attachments.map((a) => (
                    <li key={a.id} className={styles.attItem}>
                      <span className={styles.attName} title={a.name}>
                        {a.name || a.id}
                      </span>
                      {fmtSize(a.size) && <span className={styles.attSize}>{fmtSize(a.size)}</span>}
                      <button type="button" className={styles.attDownload} onClick={() => onDownload(a)}>
                        {t('bugList.detail.download')}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <div className={styles.empty}>{t('bugList.detail.noAttachments')}</div>
              )}

              <div className={styles.sectionTitle}>
                {t('bugList.detail.comments')} ({detail.comments?.length || 0})
              </div>
              {detail.comments && detail.comments.length > 0 ? (
                <ul className={styles.cmtList}>
                  {detail.comments.map((c, i) => (
                    <li key={i} className={styles.cmtItem}>
                      <div className={styles.cmtHead}>
                        <span className={styles.cmtAuthor}>{c.author || '—'}</span>
                        {!!c.gmtCreate && <span className={styles.cmtTime}>{fmtTime(c.gmtCreate)}</span>}
                      </div>
                      {/* 评论可能是 HTML(云效富文本) 或 markdown(本插件发的); HTML 走 DOMPurify,
                          否则走 MarkdownBlock 才能把 ![](图) 渲染成图片。 */}
                      {/<[a-z][\s\S]*>/i.test(c.content || '') ? (
                        <div
                          className="markdown-content"
                          dangerouslySetInnerHTML={{
                            __html: DOMPurify.sanitize(c.content || '', { ADD_ATTR: ['target'] }),
                          }}
                        />
                      ) : (
                        <MarkdownBlock content={c.content || ''} />
                      )}
                    </li>
                  ))}
                </ul>
              ) : (
                <div className={styles.empty}>{t('bugList.detail.noComments')}</div>
              )}
            </>
          )}
        </div>

        <div className={styles.footer}>
          {!commentOpen ? (
            <button type="button" className={styles.commentEntry} onClick={() => setCommentOpen(true)}>
              {t('bugList.detail.commentPlaceholder')}
            </button>
          ) : (
            <div className={styles.commentEditor}>
              <textarea
                ref={commentInputRef}
                className={styles.commentInput}
                value={commentText}
                onChange={(e) => setCommentText(e.target.value)}
                onPaste={onPasteComment}
                onMouseDown={(e) => e.stopPropagation()}
                placeholder={t('bugList.detail.commentEditorPlaceholder')}
                rows={3}
              />
              <div className={styles.commentActions}>
                {uploading && <span className={styles.commentUploading}>{t('bugList.detail.uploading')}</span>}
                <button
                  type="button"
                  className={styles.commentCancel}
                  onClick={() => {
                    setCommentOpen(false);
                    setCommentText('');
                  }}
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="button"
                  className={styles.commentSubmit}
                  disabled={submitting || !commentText.trim()}
                  onClick={submitComment}
                >
                  {submitting ? t('bugList.detail.submitting') : t('bugList.detail.submit')}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default BugDetailModal;
