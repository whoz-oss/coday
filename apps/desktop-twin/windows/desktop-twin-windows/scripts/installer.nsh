; Custom NSIS installer script for Coday Twin Desktop
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
      MessageBox MB_OK|MB_ICONINFORMATION "Node.js could not be installed automatically.$\n$\nPlease install Node.js LTS manually from https://nodejs.org/$\n$\nCoday Twin requires Node.js to run.$\n$\nAfter installing Node.js, restart Coday Twin."
    ${Else}
      DetailPrint "Node.js installed successfully"
    ${EndIf}
  ${Else}
    DetailPrint "Node.js is already installed"
  ${EndIf}

  ; Check if ripgrep is installed (optional, used for search)
  nsExec::ExecToStack 'where rg'
  Pop $0
  ${If} $0 != 0
    DetailPrint "ripgrep not found. Attempting to install via winget..."
    nsExec::ExecToLog 'winget install BurntSushi.ripgrep.MSVC --accept-package-agreements --accept-source-agreements --silent'
    Pop $0
    ${If} $0 != 0
      DetailPrint "ripgrep could not be installed automatically (optional - search features may be limited)"
    ${Else}
      DetailPrint "ripgrep installed successfully"
    ${EndIf}
  ${Else}
    DetailPrint "ripgrep is already installed"
  ${EndIf}

  ; Check if Obsidian is installed (optional, used for vault editing)
  IfFileExists "$PROGRAMFILES64\Obsidian\Obsidian.exe" obsidian_found
  IfFileExists "$LOCALAPPDATA\Obsidian\Obsidian.exe" obsidian_found
    DetailPrint "Obsidian not found. Attempting to install via winget..."
    nsExec::ExecToLog 'winget install Obsidian.Obsidian --accept-package-agreements --accept-source-agreements --silent'
    Pop $0
    ${If} $0 != 0
      DetailPrint "Obsidian could not be installed automatically (optional - install from https://obsidian.md)"
    ${Else}
      DetailPrint "Obsidian installed successfully"
    ${EndIf}
    Goto obsidian_done
  obsidian_found:
    DetailPrint "Obsidian is already installed"
  obsidian_done:
!macroend
