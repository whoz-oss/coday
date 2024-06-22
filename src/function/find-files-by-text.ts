import { Interactor } from "../interactor";
import { exec } from "child_process";
import {promisify} from "util";

const execAsync = promisify(exec)

type FindFilesByTextInput = {
  text: string;
  path?: string;
  root: string;
  fileTypes?: string[];
  interactor: Interactor;
  timeout?: number;
};

const defaultTimeout = 10000;
const defaultMaxBuffer = 10 * 1024 * 1024; // 10MB

export const findFilesByText = async ({
  text,
  path,
  root,
  fileTypes = [],
  interactor,
  timeout = defaultTimeout,
}: FindFilesByTextInput): Promise<string> => {
  const fileTypePattern = fileTypes.length > 0 ? fileTypes.map(type => `-g "*.${type}"`).join(' ') : '';
  const searchPath = path ?? '.';
  const searchCommand = `rg ${text} ${searchPath} ${fileTypePattern} --color never -l`;

  interactor.displayText(`Executing search command: ${searchCommand}`);

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`Search timed out after ${timeout} milliseconds`));
    }, timeout);

    execAsync(searchCommand, { cwd: root, maxBuffer: defaultMaxBuffer })
      .then(({ stdout }) => {
        clearTimeout(timer);
        resolve(stdout);
      })
      .catch(({ _, stderr }) => {
        clearTimeout(timer);
        reject(`Error: ${stderr}`);
      });
  });
};
