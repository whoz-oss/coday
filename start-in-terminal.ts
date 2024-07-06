import {TerminalInteractor} from "./src/terminal-interactor"
import {Coday} from "./src/coday"
import yargs from "yargs"
import {hideBin} from "yargs/helpers"
import {TerminalNonInteractiveInteractor} from "./src/terminal-non-interactive-interactor"

// Define the argv type
interface Argv {
  project?: string
  prompt?: string[]
  oneshot?: boolean
  _: (string | number)[]
  $0: string
}

// Parse arguments
const argv: Argv = yargs(hideBin(process.argv))
  .option("project", {
    type: "string",
    description: "Project name",
  })
  .option("prompt", {
    type: "array",
    description: "Prompt(s) to execute",
  })
  .option("oneshot", {
    type: "boolean",
    description: "Run in one-shot mode (non-interactive)",
  })
  .help().argv as Argv

const projectName = argv.project || (argv._[0] as string)
const prompts = (argv.prompt || argv._.slice(1)) as string[]
const oneshot = !!argv.oneshot

const options = {
  oneshot: oneshot,
  project: projectName,
  prompts: prompts,
}

const interactor = options.oneshot
  ? new TerminalNonInteractiveInteractor()
  : new TerminalInteractor()

new Coday(interactor, options).run()
