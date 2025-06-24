import { CodayEvent, ProjectSelectedEvent } from '@coday/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'

export class HeaderComponent implements CodayEventHandler {
  headerTitle = document.getElementById('header-title') as HTMLHeadingElement

  handle(event: CodayEvent): void {
    if (event instanceof ProjectSelectedEvent) {
      const title = event.projectName || `Coday`
      document.title = title
      this.headerTitle.innerHTML = title
    }
  }
}
