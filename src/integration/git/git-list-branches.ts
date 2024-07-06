import {runBash} from "../../function/run-bash";
import {Interactor} from "../../model/interactor";

export const gitListBranches = async ({ root, interactor }: {
  root: string,
  interactor: Interactor
}): Promise<string> => {
  const commandLocal = `git branch`;
  const commandRemote = `git branch -r`;
  const localBranches = await runBash({
    command: commandLocal,
    root,
    interactor
  });
  const remoteBranches = await runBash({
    command: commandRemote,
    root,
    interactor
  });
  return `Local branches:\n${localBranches}\nRemote branches:\n${remoteBranches}`;
};
