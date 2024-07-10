import {runBash} from "../../function/run-bash"
import {Interactor} from "../../model/interactor"

export const gitLog = async ({
                               params,
                               root,
                               interactor
                             }: {
  params: string;
  root: string;
  interactor: Interactor;
}) => {
  return await runBash({
    command: `git log ${params || ""}`,
    root,
    interactor
  })
}
