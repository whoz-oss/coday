import {existsSync, mkdirSync, writeFileSync} from "node:fs";
import {Interactor} from "../interactor";
import path from "path";

type WriteFileByPathInput = {
    relPath: string
    root: string
    interactor?: Interactor
    content: string
}

export const writeFile = ({relPath, root, interactor, content}: WriteFileByPathInput) => {

    // need to prevent double slashes
    const fullPath = relPath ? path.resolve(root, relPath) : root

    try {
        interactor?.displayText(`writing file ${fullPath}`)
        if (!existsSync(fullPath)) {
            mkdirSync(fullPath, {recursive: true})
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