Helper file for coday.json...

This is a chat AI assistant running by calling an online LLM.

It is only in typescript and currently only running in a terminal. To avoid coupling to the 'console.log' and alike, the interface 'interactor.ts' is used and implemented by the 'terminal-interactor.ts'.

The core principle is to answer user's single command (one at a time) by passing it to all registered handlers.
The first handler that accepts the command take it and handlers can be nested to cover more complex commands.
Handlers then extends either 'command-handler.ts' or 'nested-handler.ts' and can themselves decompose a command into several others that are added on the pile.

Associated to the command, the 'command-context.ts' is also given to carry data relevant for some handlers. It is built initially when loading a 'project-config.ts' on project selection.
A project is a directory whose files should be readable and editable and where 'coday.json' add some customizations.

The most interesting handler is 'openai-handler.ts' that uses 'openai-client.ts' that wraps the complexity around the library openai.
This client leverages the ability to submit function declaration for ChatGPT to call, hence the need on our part to run the function with the given arguments and return the answer.
These exposed functions are based on those in /src/function where files can be listed, read and written. And the 'run-bash.ts' function allows to expose some specific commands to ChatGPT (no free access).

Code conventions:
- inject in constructors
- no ';' in source code at line ends
- prefer 1 file for 1 class or function or type