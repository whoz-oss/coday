; Custom NSIS installer script for Coday Desktop
; Runs after the main installation to set up required dependencies

!macro customInstall
  ; Check if Node.js is installed by looking in common locations AND via PATH
  ; Note: NSIS runs as SYSTEM, so per-user PATH entries may not be visible.
  ; We check both well-known install paths and the PATH-based lookup.
  
  StrCpy $1 ""
  
  ; Check standard install locations first (covers per-user installs too)
  IfFileExists "$PROGRAMFILES64\nodejs\node.exe" node_found
  IfFileExists "$PROGRAMFILES\nodejs\node.exe" node_found
  IfFileExists "$LOCALAPPDATA\Programs\nodejs\node.exe" node_found
  
  ; Try PATH-based lookup (works for system-wide installs)
  nsExec::ExecToStack 'where node'
  Pop $0
  Pop $1
  ${If} $0 == 0
    Goto node_found
  ${EndIf}
  
  ; Node.js not found — attempt to install
  DetailPrint "Node.js not found. Attempting to install..."
  
  ; Try winget first (available on Windows 10 1709+ and Windows 11)
  nsExec::ExecToStack 'where winget'
  Pop $0
  Pop $1
  ${If} $0 == 0
    DetailPrint "Installing Node.js LTS via winget..."
    nsExec::ExecToStack 'winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements --silent'
    Pop $0
    Pop $1
    ${If} $0 == 0
      DetailPrint "Node.js installed successfully via winget"
      Goto node_done
    ${Else}
      DetailPrint "winget install failed (exit code: $0)"
    ${EndIf}
  ${Else}
    DetailPrint "winget not available on this system"
  ${EndIf}
  
  ; winget not available or failed — show manual install message
  MessageBox MB_OK|MB_ICONINFORMATION "Node.js is required but could not be installed automatically.$\n$\nPlease install Node.js LTS from https://nodejs.org/$\n$\nAfter installing, restart Coday Desktop."
  Goto node_done
  
  node_found:
    DetailPrint "Node.js is already installed"
  
  node_done:
!macroend
