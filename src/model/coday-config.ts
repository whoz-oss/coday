import {ProjectConfig} from "./project-config";

export type CodayConfig = {
    project: {
      [key: string]: ProjectConfig
    }
    currentProject?: string
}
