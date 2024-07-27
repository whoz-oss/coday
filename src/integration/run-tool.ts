import {ToolCall} from "./tool-call"
import {Tool} from "./assistant-tool-factory"
import {Interactor} from "../model"

export async function runTool(toolCall: ToolCall, tools: Tool[], interactor: Interactor): Promise<string> {
  let output
  const funcWrapper = tools.find(
    (t) => t.function.name === toolCall.name,
  )
  if (!funcWrapper) {
    output = `Function ${toolCall.name} not found.`
    return output
  }
  
  const toolFunc = funcWrapper.function.function
  
  try {
    let args: any = JSON.parse(toolCall.args)
    
    if (!Array.isArray(args)) {
      args = [args]
    }
    output = await toolFunc.apply(null, args)
  } catch (err) {
    interactor.error(err)
    output = `Error on executing function, got error: ${JSON.stringify(err)}`
  }
  
  if (!output) {
    output = `Tool function ${funcWrapper.function.name} finished without error.`
  }
  
  if (typeof output !== "string") {
    output = JSON.stringify(output)
  }
  return output
}