import {readFileSync} from "fs";
import {existsSync} from "node:fs";
import {Interactor} from "../interactor";
import path from "path";

type ReadFileWithLinesByPathInput = {
    relPath: string
    root: string
    interactor?: Interactor
}

export const readFileWithLinesByPath = ({relPath, root, interactor}: ReadFileWithLinesByPathInput): Map<number, string> | string => {
    const fullPath = relPath ? path.resolve(root, relPath) : root
    try {
        interactor?.displayText(`reading file with lines ${fullPath}`)
        if (existsSync(fullPath)) {
            const fileContent = readFileSync(fullPath, 'utf-8').toString()
            const linesMap: { [key: number]: string} = {}
            fileContent.split('\n').forEach((line, index) => {
                linesMap[index+1] = line
            })
            console.debug(`returning a file content by lines ${Object.entries(linesMap).length}`)
            const result = JSON.stringify(linesMap)
            console.debug(`result length: ${result.length}`)
            return result
        } else {
            return "File does not exist or path incorrect"
        }

    } catch (err) {
        interactor?.error(`Error reading file ${fullPath}`)
        console.error(err)
        return "Error reading file"
    }
}