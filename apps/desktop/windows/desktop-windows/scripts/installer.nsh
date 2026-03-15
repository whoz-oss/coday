; Custom NSIS installer script for Coday Desktop
; Runs after the main installation to set up required dependencies

!macro customInstall
  ; Check if Node.js is installed
  nsExec::ExecToStack 'where node'
  Pop $0
  ${If} $0 != 0
    DetailPrint "Node.js not found. Attempting to install via winget..."
    nsExec::ExecToLog 'winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements --silent'
    Pop $0
    ${If} $0 != 0
      MessageBox MB_OK|MB_ICONINFORMATION "Node.js could not be installed automatically.$\n$\nPlease install Node.js LTS manually from https://nodejs.org/$\n$\nCoday Desktop requires Node.js to run.$\n$\nAfter installing Node.js, restart Coday Desktop."
    ${Else}
      DetailPrint "Node.js installed successfully"
    ${EndIf}
  ${Else}
    DetailPrint "Node.js is already installed"
  ${EndIf}
!macroend
