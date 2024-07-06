import {runBash} from "../../function/run-bash";
import {Interactor} from "../../model/interactor";

export const gitCreateBranch = async ({ branchName, baseBranch, root, interactor }: {
  branchName: string,
  baseBranch?: string,
  root: string,
  interactor: Interactor
}): Promise<string> => {
  const command = baseBranch
    ? `git fetch --all && git checkout -b ${branchName} ${baseBranch}`
    : `git fetch --all && git checkout -b ${branchName}`;
  return await runBash({
    command,
    root,
    interactor
  });
};
