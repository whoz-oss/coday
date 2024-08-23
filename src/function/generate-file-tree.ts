import {execSync} from "child_process"
import {Interactor} from "../model"

const maxBuffer = 50 * 1024 * 1024

type FileTree = { [key: string]: FileTree | null } | null

export function generateFileTree(rootPath: string, interactor: Interactor, timeout: number = 10000, stringChunkSize: number = 200000): string[] {
  try {
    // Execute ripgrep to get all file paths, respecting .gitignore
    interactor.displayText("Mapping project files...")
    const output = execSync("rg --files", {cwd: rootPath, encoding: "utf-8", timeout, maxBuffer})
    const files = output.split("\n").filter(Boolean).sort()
    interactor.displayText(`... mapped ${files.length} files.`)
    
    const fileTree: FileTree = {}
    
    // files.forEach(file => {
    //   const parts = file.split("/")
    //   let accumulator = fileTree
    //   parts.forEach((currentPart, index) => {
    //     if (index === parts.length - 1) {
    //       accumulator[currentPart] = null // Mark as file
    //       return
    //     }
    //     accumulator[currentPart] = accumulator[currentPart] || {} // Mark as directory
    //     accumulator = accumulator[currentPart]
    //   })
    // })
    //
    // const formatTree = (node: any, depth: number = 0): string => {
    //   return Object.entries(node)
    //     .map(([key, value]) => {
    //       const spaces = "  ".repeat(depth)
    //       if (value === null) {
    //         return `${spaces}${key}`  // File
    //       } else {
    //         return `${spaces}${key}/\n${formatTree(value, depth + 1)}`  // Directory
    //       }
    //     })
    //     .join("\n")
    // }
    
    // const text = formatTree(fileTree)
    const text = files.join("\n")
    const chunkCount = Math.ceil(text.length / stringChunkSize)
    const title = `\n\n## File tree\n\n`
    if (chunkCount === 1) {
      return [`${title}${text}`]
    }
    
    const lines = text.split("\n")
    const chunks: string[] = []
    
    for (let i = 0; i < chunkCount; i++) {
      const start = i * (lines.length / chunkCount)
      const end = (i + 1) * (lines.length / chunkCount)
      const chunk = `${i === 0 ? title : ""}chunk${i + 1}/${chunkCount}\n\n${lines.slice(start, end).join("\n")}`
      chunks.push(chunk)
    }
    
    return chunks
  } catch (error: any) {
    interactor.error(`Error generating file tree or process timeout exceeded. ${error}`)
    return ["File tree generation timed out or encountered an error."]
  }
}