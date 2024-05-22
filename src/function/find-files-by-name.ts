import {Interactor} from "../interactor";
import {glob} from "glob";

type FindFilesInput = {
    text: string
    path?: string
    root: string
    interactor?: Interactor
    timeout?: number
}

const defaultTimeout=5000

export const findFilesByName = async ({text, path, root, interactor, timeout}: FindFilesInput) => {
    // need to prevent double slashes
    const tweakedPath = path?.startsWith('/')
        ? path.substring(1)
        : path

    const expression = `${path ? tweakedPath + '/': ''}**/*${text}*`

    interactor?.displayText(`Looking for "${expression}" in ${root}`)
    const results = await glob(expression, {
        cwd: root,
        absolute: false,
        dotRelative: false,
        follow: false,
        signal: AbortSignal.timeout(timeout || defaultTimeout),
        ignore: ['**/node_modules/**', '**/build/**']
    })
    interactor?.displayText(`Found ${results.length} files.`)

    return results
}