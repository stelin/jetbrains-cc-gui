import { useTranslation } from 'react-i18next';
import type { ToolInput, ToolResultBlock } from '../../types';

/**
 * Centred "context was compacted" notice. Dispatched by ContentBlockRenderer
 * for synthetic {@code mcp__supervisor__compact_boundary} tool_use blocks
 * (synthesised from the SDK's {@code system/compact_boundary} message so the
 * compaction marker flows through the same rendering pipeline as the rest of
 * the stream).
 */

interface SupervisorCompactionBlockProps {
  name?: string;
  input?: ToolInput;
  result?: ToolResultBlock | null;
  toolId?: string;
}

const SupervisorCompactionBlock = ({ input }: SupervisorCompactionBlockProps) => {
  const { t } = useTranslation();
  const preTokens = typeof input?.preTokens === 'number' ? input.preTokens : null;
  const preTokensLabel = preTokens != null ? ` (${preTokens.toLocaleString()} → ?)` : '';
  return (
    <div className="supervisor-compaction-note">
      <span className="supervisor-compaction-icon">💾</span>
      <span>
        {t('pairLayout.compaction.notice', '上下文已自动压缩')}
        {preTokensLabel}
      </span>
    </div>
  );
};

export default SupervisorCompactionBlock;
