import {Interactor} from "../../model"
import * as fs from "fs"
import * as path from "path"

type ListFilesInput = {
  relPath: string,
  root: string,
  interactor?: Interactor
};

export const listFilesAndDirectories = async ({
                                                relPath,
                                                root,
                                                interactor
                                              }: ListFilesInput): Promise<string[]> => {
  const fullPath = relPath ? path.resolve(root, relPath) : root
  
  // Check if the resolved path is within the root folder to prevent leaving the designated folder
  if (!fullPath.startsWith(path.resolve(root))) {
    throw new Error("Attempt to navigate outside the root folder")
  }
  
  interactor?.displayText(`Listing contents of: ${fullPath} ...`)
  
  let result: string [] | string
  
  result = fs.readdirSync(fullPath).map(file => {
    const dirFullPath = path.join(fullPath, file)
    const stat = fs.lstatSync(dirFullPath)
    
    return stat.isDirectory() ? `${file}/` : file
  })
  
  interactor?.displayText(`Found ${result.length} items.`)
  
  return result
}