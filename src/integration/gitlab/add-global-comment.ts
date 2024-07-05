import axios from 'axios';
import {Interactor} from "../../interactor";

export async function addGlobalComment({ mergeRequestId, comment, gitlabBaseUrl, gitlabApiToken, interactor }: {
    mergeRequestId: string,
    comment: string,
    gitlabBaseUrl: string,
    gitlabApiToken: string,
    interactor: Interactor
}): Promise<string> {
    const headers = { 'PRIVATE-TOKEN': gitlabApiToken }
    const url = new URL(`/merge_requests/${mergeRequestId}/notes`, gitlabBaseUrl).toString()

    try {
        interactor.displayText("Add MR note")
        const response = await axios.post(url, { body: comment }, { headers });
        if (response.status >= 400) {
            return `Error: ${response.status} with data: ${response.data}`;
        }
        return JSON.stringify(response.data);
    } catch (error:any) {
        console.error(error);
        return `Error: ${error}`;
    }
}

