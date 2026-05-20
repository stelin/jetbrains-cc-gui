import { useCallback } from 'react';
import type { Attachment } from '../types.js';
import { generateId } from '../utils/generateId.js';
import { debugError } from '../../../utils/debug.js';
import { compressImage } from '../../../utils/imageCompressor.js';

export interface UseAttachmentHandlersOptions {
  externalAttachments: Attachment[] | undefined;
  onAddAttachment?: (files: FileList) => void;
  onRemoveAttachment?: (id: string) => void;
  setInternalAttachments: React.Dispatch<React.SetStateAction<Attachment[]>>;
}

/**
 * useAttachmentHandlers - Handle attachment add/remove
 *
 * Supports both controlled (external) and uncontrolled (internal) attachment modes.
 */
export function useAttachmentHandlers({
  externalAttachments,
  onAddAttachment,
  onRemoveAttachment,
  setInternalAttachments,
}: UseAttachmentHandlersOptions) {
  const handleAddAttachment = useCallback(
    (files: FileList) => {
      if (externalAttachments !== undefined) {
        onAddAttachment?.(files);
        return;
      }

      Array.from(files).forEach((file) => {
        compressImage(file)
          .then((result) => {
            const attachment: Attachment = {
              id: generateId(),
              fileName: file.name,
              mediaType: result.mediaType || file.type || 'application/octet-stream',
              data: result.base64,
            };
            setInternalAttachments((prev) => [...prev, attachment]);
          })
          .catch((err) => {
            debugError('[useAttachmentHandlers] compressImage failed:', file.name, err);
          });
      });
    },
    [externalAttachments, onAddAttachment, setInternalAttachments]
  );

  const handleRemoveAttachment = useCallback(
    (id: string) => {
      if (externalAttachments !== undefined) {
        onRemoveAttachment?.(id);
        return;
      }
      setInternalAttachments((prev) => prev.filter((a) => a.id !== id));
    },
    [externalAttachments, onRemoveAttachment, setInternalAttachments]
  );

  return { handleAddAttachment, handleRemoveAttachment };
}

