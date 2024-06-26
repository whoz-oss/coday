# Using Coday as an npm Package

## Overview
This document provides guidance on how to use Coday as a `devDependency` in other projects.

DISCLAIMER: it was written by Coday, cross-check every guidance.

## Steps to Use Coday as an npm Package

1. **Install Coday as a Dev Dependency**

   In the project where you want to use Coday, add it as a dev dependency:
   ```sh
   npm install --save-dev coday
   ```

2. **Update the `bin` Field in `package.json`**

   Ensure Coday can be invoked from the command line by adding a `bin` entry in its `package.json`:
   ```json
   {
     "bin": {
       "coday": "./start-coday.js"
     }
   }
   ```

3. **Create an Executable Wrapper**

   Create a JavaScript wrapper file, `start-coday.js`, to invoke the TypeScript main script:
   ```javascript
   #!/usr/bin/env node

   require('ts-node').register();
   require('./start-in-terminal.ts');
   ```

   Make this file executable:
   ```sh
   chmod +x start-coday.js
   ```

4. **Run Coday**

   In your project, invoke Coday using `npx`:
   ```sh
   npx coday
   ```

This setup ensures that Coday can be used as an npm package and easily invoked in other projects.
