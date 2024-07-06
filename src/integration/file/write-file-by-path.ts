import {existsSync, mkdirSync, writeFileSync} from "node:fs";
import {Interactor} from "../../model/interactor";
import path from "path";

type WriteFileByPathInput = {
    relPath: string
    root: string
    interactor?: Interactor
    content: string
}

export const writeFileByPath = ({relPath, root, interactor, content}: WriteFileByPathInput) => {

    // need to prevent double slashes
    const fullPath = relPath ? path.resolve(root, relPath) : root

    try {
        interactor?.displayText(`writing file ${fullPath}`)
        const dir = path.dirname(fullPath)
        if (!existsSync(dir)) {
            interactor?.displayText(`Making directory ${dir}`)
            mkdirSync(dir, {recursive: true})
        }
        const data = new Uint8Array(Buffer.from(content))
        writeFileSync(fullPath, data)
        return "File write success"
    } catch (err) {
        interactor?.error(`Error writing file ${fullPath}`)
        console.error(err)
        return "Error writing file"
    }
}