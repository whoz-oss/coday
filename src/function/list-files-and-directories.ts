import { Interactor } from "../interactor";
import * as fs from 'fs';
import * as path from 'path';

type ListFilesInput = {
    relPath: string,
    root: string,
    interactor?: Interactor
};

export const listFilesAndDirectories = async ({ relPath, root, interactor }: ListFilesInput): Promise<string[]> => {
    const resolvedPath = path.resolve(root, relPath);
    
    // Check if the resolved path is within the root folder to prevent leaving the designated folder
    if (!resolvedPath.startsWith(path.resolve(root))) {
        throw new Error("Attempt to navigate outside the root folder");
    }

    interactor?.displayText(`Listing contents of: ${resolvedPath} ...`);
    
    const results = fs.readdirSync(resolvedPath).map(file => {
        const fullPath = path.join(resolvedPath, file);
        const stat = fs.lstatSync(fullPath);
        
        return stat.isDirectory() ? `${file}/` : file;
    });
    
    interactor?.displayText(`Found ${results.length} items.`);
    
    return results;
};