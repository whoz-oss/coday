import {writeFileSync} from "node:fs";
import {Interactor} from "../interactor";

type WriteFileByPathInput = {
    path: string
    root: string
    interactor?: Interactor
    content: string
}

export const writeFile = ({path, root, interactor, content}: WriteFileByPathInput) => {

    // need to prevent double slashes
    const tweakedPath = path.startsWith('/')
        ? path.substring(1)
        : path

    const fullPath = `${root}/${tweakedPath}`
    try {
        interactor?.displayText(`writing file ${fullPath}`)
        const data = new Uint8Array(Buffer. from(content))
        writeFileSync(fullPath, data)
        return "File write success"
    } catch (err) {
        interactor?.error(`Error writing file ${fullPath}`)
        console.error(err)
        return "Error writing file"
    }
}