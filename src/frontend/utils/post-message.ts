import {CodayEvent} from "../../shared/coday-events.js"

export function postEvent(event: CodayEvent): Promise<Response> {
  return fetch("/api/message", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(event),
  })
}