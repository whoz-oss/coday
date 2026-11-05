import { ButtonDirective } from './components/button/button.directive'
import { CardComponent } from './components/card/card.component'
import { CheckDirective } from './components/check/check.directive'
import { ControlDirective } from './components/control/control.directive'
import { DefinitionListDirective } from './components/definition-list/definition-list.directive'
import { FieldsetComponent } from './components/fieldset/fieldset.component'
import { FormActionsComponent } from './components/form-actions/form-actions.component'
import { FormFieldComponent } from './components/form-field/form-field.component'
import { GroupComponent } from './components/group/group.component'
import { NoticeComponent } from './components/notice/notice.component'
import { PageComponent } from './components/page/page.component'
import { SectionHeaderComponent } from './components/section-header/section-header.component'

/**
 * Everything a settings page is made of, in one import:
 *
 * ```ts
 * @Component({ imports: [DS_PAGE_KIT, ReactiveFormsModule], … })
 * ```
 *
 * ds-page, ds-section-header, ds-form-field, ds-fieldset, ds-group, ds-form-actions, ds-card, ds-notice,
 * dsButton, dsControl, dsCheck, dsDefinitionList. Add `TableDirective`, `TagComponent`, `SegmentedComponent`,
 * `DisclosureComponent` or `MemberRowComponent` on top when the page needs them.
 */
export const DS_PAGE_KIT = [
  PageComponent,
  SectionHeaderComponent,
  FormFieldComponent,
  FieldsetComponent,
  GroupComponent,
  FormActionsComponent,
  CardComponent,
  NoticeComponent,
  ButtonDirective,
  ControlDirective,
  CheckDirective,
  DefinitionListDirective,
] as const
