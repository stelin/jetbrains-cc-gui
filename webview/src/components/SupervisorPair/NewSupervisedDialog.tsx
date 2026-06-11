import { useEffect, useState } from 'react';
import type {
  SupervisorAgent,
  SupervisorAgentListPayload,
} from '../../types/supervisorAgent';
import { sendBridgeEvent } from '../../utils/bridge';
import { usePairContext } from './PairContext';
import PickerDialog from '../ChatInputBox/SupervisorToggle/PickerDialog';

interface NewSupervisedDialogProps {
  open: boolean;
  /** Close without creating. */
  onClose: () => void;
  /**
   * Invoked after the create IPC is dispatched so the host can switch to the
   * chat view + reset the main-chat UI for the new session.
   */
  onCreated: () => void;
  /** Open Settings → supervisor manager (passthrough to the picker footer). */
  onOpenManager?: () => void;
}

/**
 * Session-kind refactor (born-at-birth). Entry dialog for "new supervised
 * session": pick a supervisor agent, then create a session that is supervised
 * from creation (no runtime SupervisorToggle anymore). Owns its own agent-list
 * fetch (moved here from the removed SupervisorToggle) and delegates the actual
 * create to {@link usePairContext().createSupervisedSession}, which ships a
 * single `session_create_supervised` IPC.
 */
export default function NewSupervisedDialog({
  open,
  onClose,
  onCreated,
  onOpenManager,
}: NewSupervisedDialogProps) {
  const { createSupervisedSession } = usePairContext();
  const [agents, setAgents] = useState<SupervisorAgent[]>([]);
  const [defaultId, setDefaultId] = useState<string | null>(null);

  // Subscribe to supervisor-agents list pushes (chained with any existing
  // callback so the settings panel keeps working). Mirrors the fetch the
  // removed SupervisorToggle used to own.
  useEffect(() => {
    const previous = window.updateSupervisorAgents;
    window.updateSupervisorAgents = (jsonStr: string) => {
      previous?.(jsonStr);
      try {
        const payload: SupervisorAgentListPayload = JSON.parse(jsonStr);
        setAgents(payload.agents || []);
        setDefaultId(payload.defaultAgentId ?? null);
      } catch {
        // ignore malformed
      }
    };
    return () => {
      window.updateSupervisorAgents = previous;
    };
  }, []);

  // Refresh the list each time the dialog opens (the user may have added /
  // removed agents in settings since last open).
  useEffect(() => {
    if (open) sendBridgeEvent('get_supervisor_agents', '');
  }, [open]);

  const handleConfirm = (agent: SupervisorAgent) => {
    createSupervisedSession(agent);
    onClose();
    onCreated();
  };

  return (
    <PickerDialog
      open={open}
      agents={agents}
      defaultId={defaultId}
      initialSelectedId={null}
      requiredChoice
      onCancel={onClose}
      onConfirm={handleConfirm}
      onOpenManager={onOpenManager}
    />
  );
}
