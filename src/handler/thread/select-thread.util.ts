import {Thread} from "../../model/thread"
import {Interactor} from "../../model/interactor"
import {threadService} from "../../service/thread.service"
import {formatThread} from "./format-thread.util"

export async function selectThread(
  interactor: Interactor,
  openaiClientThreadId: string | null
): Promise<Thread | undefined> {
  const threads = threadService.listThreads()
  const threadsByText = new Map<string, Thread>()
  threads.forEach(t => threadsByText.set(formatThread(t.threadId, t.name, openaiClientThreadId), t))
  const options = Array.from(threadsByText.keys())
  const selected = await interactor.chooseOption(options, "Selection")
  return threadsByText.get(selected)
}
