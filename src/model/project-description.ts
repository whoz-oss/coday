import {Scripts} from "./scripts";
import {AssistantDescription} from "./assistant-description";

export type ProjectDescription = {
  assistants?: AssistantDescription[]
  description: string
  scripts?: Scripts
}