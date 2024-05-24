import {existsSync, readFileSync, writeFileSync} from "fs";
import {ProjectConfig} from "./project-config";

const CONFIG_FILENAME= 'coday.json'

export const loadOrInitProjectConfig = (projectPath: string): ProjectConfig | null => {
    const projectConfigPath = `${projectPath}/${CONFIG_FILENAME}`
    let projectConfig: ProjectConfig;
    if (!existsSync(projectConfigPath)) {
        projectConfig = {
            description : `Dummy description of the project. Write here a description of the project with all that matters: purpose, users, technologies, frameworks, conventions, tools, architecture... You can also write the paths to some relevant files the LLM could get on the go.`,
            scripts: {
                example: {
                    description: "Dummy description of the example script so that the LLM can get a grasp of what it does (so to understand when to use it). Better long than short. Here it echoes an simple text.",
                    command: 'echo "example script run with great success"'
                }
            }
        };
        const json = JSON.stringify(projectConfig, null, 2);
        writeFileSync(projectConfigPath, json);
    } else {
        projectConfig = JSON.parse(readFileSync(projectConfigPath, 'utf-8')) as ProjectConfig;
    }
    return projectConfig;
}
