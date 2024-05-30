import {runBash} from "./run-bash";
import {Interactor} from "../interactor";

export const gitCreateBranch = async ({ branchName, baseBranch, root, interactor }: {
  branchName: string,
  baseBranch?: string,
  root: string,
  interactor: Interactor
}): Promise<string> => {
  const command = baseBranch
    ? `git checkout -b ${branchName} ${baseBranch}`
    : `git checkout -b ${branchName}`;
  return await runBash({
    command,
    root,
    interactor
  });
};
