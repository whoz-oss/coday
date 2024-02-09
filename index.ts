import * as readlineSync from 'readline-sync';
import {handlers} from "./src/handlers";
import {CommandContext} from "./src/command-context";
import os from 'os';

const PROJECT_ROOT: string = '/Users/vincent.audibert/Workspace/biznet.io/app/whoz'

async function main() {

    const userInfo = os.userInfo()
    let context: CommandContext = {
        projectRootPath: PROJECT_ROOT,
        username: userInfo.username
    }

    do {
        const command = readlineSync.question(`${context.username} : `)

        const handler = handlers.find(h => h.accept(command, context))

        if (handler) {
            context = await handler.handle(command, context)
        } else if (command === "exit") {
            break;
        } else {
            console.log("Command not understood, available commands:")
            handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
        }

    } while (true)

}

// Run the script
main();