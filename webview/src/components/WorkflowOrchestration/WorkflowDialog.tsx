/**
 * @deprecated The modal dialog was replaced by the full-page {@link WorkflowView}
 * (2026-06-01) so the editor gets the whole tool-window surface. Kept as a thin
 * alias to avoid breaking any stray imports; prefer importing WorkflowView.
 */
export { default } from './WorkflowView';
export { default as WorkflowDialog } from './WorkflowView';
