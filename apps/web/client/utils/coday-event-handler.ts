
import { CodayEvent } from '@coday/coday-events'

export interface CodayEventHandler {
  handle(event: CodayEvent): void
}
