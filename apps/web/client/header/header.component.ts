import { CodayEvent, ProjectSelectedEvent } from '@coday/shared/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'

export class HeaderComponent implements CodayEventHandler {
  headerTitle = document.getElementById('header-title') as HTMLHeadingElement

  handle(event: CodayEvent): void {
    if (event instanceof ProjectSelectedEvent) {
      const projectSuffix = event.projectName ? ` (${event.projectName})` : ''
      const title = `Coday${projectSuffix}`
      document.title = title
      this.headerTitle.innerHTML = title
    }
  }
}
