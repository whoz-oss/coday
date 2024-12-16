import { CodayEvent } from '@coday/shared/coday-events'

export interface CodayEventHandler {
  handle(event: CodayEvent): void
}
