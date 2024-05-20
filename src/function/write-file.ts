import {FilePath} from "./path";
import {writeFileSync} from "node:fs";

export const writeFile = ({path, root, interactor, content}: FilePath & {content: string}) => {
    // need to prevent double slashes
    const tweakedPath = path.startsWith('/')
        ? path.substring(1)
        : path

    const fullPath = `${root}/${tweakedPath}`
    try {
        interactor?.displayText(`writing file ${fullPath}`)
        const data = new Uint8Array(Buffer. from(content))
        writeFileSync('message. txt', data)
        return "File write success"
    } catch (err) {
        interactor?.error(`Error writing file ${fullPath}`)
        console.error(err)
        return "Error writing file"
    }
}