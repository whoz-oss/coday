import {exec} from "child_process"
import * as http from "http" // Use 'https' if the server runs on SSL

const port = process.env.PORT || 3000
const url = `http://localhost:${port}`
const interval = 2000 // Check every two seconds
const maxRetries = 5
let retries = 0

function checkServer() {
  if (retries >= maxRetries) {
    console.error(`Server not available after ${maxRetries} attempts. Exiting.`)
    return
  }
  
  http.get(url, (res) => {
    if (res.statusCode === 200) {
      console.log(`Server is up at ${url}`)
      openFrontend()
    } else {
      console.log(`Status Code: ${res.statusCode}`)
      retry()
    }
  }).on("error", (err) => {
    console.log(`Server not available, retrying in ${interval / 1000} seconds...`)
    retry()
  })
}

function retry() {
  retries++
  setTimeout(checkServer, interval)
}

function openFrontend() {
  exec(`yarn run open-cli ${url}`, (err: any, stdout: string, stderr: string) => {
    if (err) {
      console.error(`Error opening frontend: ${err}`)
      return
    }
    console.log(`Frontend opened successfully: ${stdout}`)
  })
}

checkServer()
