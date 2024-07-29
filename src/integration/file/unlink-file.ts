import fs from "fs"
import {promisify} from "util"
import {Interactor} from "../../model"

const unlink = promisify(fs.unlink)

export async function unlinkFile(filePath: string, interactor: Interactor): Promise<void> {
  interactor.displayText(`Removing file ${filePath}`)
  await unlink(filePath)
}
