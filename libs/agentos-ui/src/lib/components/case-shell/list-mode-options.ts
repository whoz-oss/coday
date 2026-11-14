import { SegmentedOption } from '@whoz-oss/design-system'
import { CaseListMode } from '../../services/case-state.service'

/** The compact Mine / All switch offered to the administrators of a namespace whose setting is SWITCH. */
export const LIST_MODE_OPTIONS: ReadonlyArray<SegmentedOption<CaseListMode>> = [
  { value: 'mine', label: 'Mine' },
  { value: 'all', label: 'All' },
]

/** Tooltip and accessible name of the switch: the buttons stay short, this says what they do. */
export const LIST_MODE_HINT = "Show only your conversations, or all of this namespace's"
