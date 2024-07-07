type Script = {
  /**
   * Description of what the command line does.
   *
   * It should precise if needed when to call and not to call and any information relevant for anyone to make a decision in using it.
   */
  description: string
  
  /**
   * The command line that will be executed, as ran from the project root folder (ie do `cd [folder]` as needed)
   */
  command: string
  
  /**
   * Definition of parameters given to the command line a single string, added as suffix or replacing `PARAMETERS` in command.
   *
   * If the command is parameterized, this description is needed for the LLM to generate the right arguments.
   * Be extensive in describing what are the arguments, the order, meaning and connection to the command line.
   */
  parametersDescription?: string
}

/**
 * Scripts is the definition of bash command lines that are made available to the LLM as a tool
 */
export type Scripts = {
  /**
   * Keys of a Script object are the names of the tool that will be exposed
   */
  [key: string]: Script
}
