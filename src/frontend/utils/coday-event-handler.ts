import {CodayEvent} from "../../shared/coday-events.js"

export interface CodayEventHandler {
  handle(event: CodayEvent): void
}