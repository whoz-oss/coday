import axios from 'axios'
import * as crypto from 'crypto'
import {Interactor} from "../../model/interactor";

export async function addMRThread({
                                      mergeRequestId,
                                      base_sha,
                                      head_sha,
                                      start_sha,
                                      oldFilePath,
                                      newFilePath,
                                      comment,
                                      oldLineNumber,
                                      newLineNumber,
                                      gitlabBaseUrl,
                                      gitlabApiToken,
                                      interactor
                                  }: {
    mergeRequestId: string,
    base_sha: string,
    head_sha: string,
    start_sha: string,
    oldFilePath: string,
    newFilePath: string,
    comment: string,
    oldLineNumber: number | null,
    newLineNumber: number | null,
    gitlabBaseUrl: string,
    gitlabApiToken: string,
    interactor: Interactor
}): Promise<string> {
    const headers = {'PRIVATE-TOKEN': gitlabApiToken}
    const url = `${gitlabBaseUrl}/merge_requests/${mergeRequestId}/discussions`

    const data = {
        body: comment,
        position: {
            base_sha: base_sha,
            start_sha: start_sha,
            head_sha: head_sha,
            position_type: 'text',
            old_path: oldFilePath,
            new_path: newFilePath,
            old_line: oldLineNumber,
            new_line: newLineNumber,
            line_code: generateLineCode(oldFilePath, oldLineNumber, newLineNumber)
        }
    }

    try {
        interactor.displayText(`Add MR comment to file ${newFilePath}`)
        const response = await axios.post(url, data, {headers})
        if (response.status >= 400) {
            return `Error: ${response.status} with data: ${response.data}`
        }
        return response.data
    } catch (error: any) {
        console.error(error)
        return `Error: ${error}`
    }
}

function generateLineCode(filePath: string, oldLineNumber: number | null, newLineNumber: number | null): string {
    // This is a placeholder function. The actual implementation of generating a line code
    // depends on how GitLab calculates the line code hash which typically involves hashing
    // the file path and line number along with the start SHA.
    // You will need to replace this with the actual logic used by your GitLab instance.
    const filePathSha = crypto.createHash('sha1').update(filePath).digest('hex')
    const oldLineStr = oldLineNumber !== null ? oldLineNumber.toString() : 'null'
    const newLineStr = newLineNumber !== null ? newLineNumber.toString() : 'null'
    return `${filePathSha}_${oldLineStr}_${newLineStr}`;
}
