import {readFileSync, existsSync, mkdirSync, writeFileSync} from "node:fs"
import {Interactor} from "../interactor"
import path from "path"

export interface Change {
    fromLine: number
    toLine: number
    content: string
}

export type PartialWriteFileInput = {
    relPath: string
    root: string
    interactor?: Interactor
    changes: Change[]
}

const applyChanges = (fileContent: string[], changes: Change[]): string[] => {
    // Order changes by decreasing line order to not mess with previous line counts
    changes.sort((a, b) => b.fromLine - a.fromLine)
    for (const change of changes) {
        console.debug(`apply for lines ${change.fromLine} to ${change.toLine} : ${change.content}`)
        fileContent.splice(change.fromLine-1, change.toLine - change.fromLine + 1, ...change.content.split('\n'))
    }
    return fileContent
}

export const partialWriteFile = ({relPath, root, interactor, changes}: PartialWriteFileInput) => {
    const fullPath = relPath ? path.resolve(root, relPath) : root

    try {
        interactor?.displayText(`Modifying file ${fullPath}`)
        const dir = path.dirname(fullPath)
        if (!existsSync(dir)) {
            interactor?.displayText(`Making directory ${dir}`)
            mkdirSync(dir, {recursive: true})
        }

        const fileContent = existsSync(fullPath)
            ? readFileSync(fullPath, 'utf-8').split('\n')
            : []

        const updatedContent = applyChanges(fileContent, changes).join('\n')
        const data = new Uint8Array(Buffer.from(updatedContent))
        writeFileSync(fullPath, data)

        interactor?.displayText(`File modification success`)
        return "File modification success"
    } catch (err: any) {
        interactor?.error(`Error modifying file ${fullPath}`)
        return `Error modifying file ${fullPath}: ${err.message}`
    }
}
