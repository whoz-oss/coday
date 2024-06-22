import {Interactor} from "../interactor";
import {runBash} from "./run-bash";

type FindFilesByTextInput = {
    text: string
    path?: string
    root: string
    fileTypes?: string[]
    interactor: Interactor
    timeout: number
}

const defaultTimeout = 10000

export const findFilesByText = async ({
    text,
    path,
    root,
    fileTypes = [],
    interactor,
    timeout = defaultTimeout
}: FindFilesByTextInput): Promise<string> => {
    // Construct the file type pattern (e.g., `--include=*.{js,ts}`)
    const fileTypePattern = fileTypes.length > 0 ? `--include=\*.{${fileTypes.join(',')}}` : '';
    const searchCommand = `egrep -lri "${text}" ${fileTypePattern} ${path ?? ''}`;

    interactor.displayText(`Executing search command: ${searchCommand}`);

    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            reject(new Error(`Search timed out after ${timeout} milliseconds`));
        }, timeout);

        runBash({
            command: searchCommand,
            root,
            interactor,
            lineLimit: 1000,
            maxBuffer: 10 * 1024 * 1024
        })
            .then(output => {
                clearTimeout(timer);
                resolve(output);
            })
            .catch(error => {
                clearTimeout(timer);
                reject(`Error: ${error}`);
            });
    });
}