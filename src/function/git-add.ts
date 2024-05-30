import {runBash} from "./run-bash";
import {Interactor} from "../interactor";

export const gitAdd = async ({ filePaths, root, interactor }: {
  filePaths: string[],
  root: string,
  interactor: Interactor
}): Promise<string> => {
  const command = `git add ${filePaths.join(' ')}`;
  return await runBash({
    command,
    root,
    interactor
  });
};
