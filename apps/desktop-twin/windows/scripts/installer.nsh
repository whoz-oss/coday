; Custom NSIS installer script for Coday Twin Desktop
; Runs after the main installation to set up required dependencies

!macro customInstall
  ; === Node.js ===
  ; Check standard install locations first (covers per-user installs too)
  IfFileExists "$PROGRAMFILES64\nodejs\node.exe" node_found
  IfFileExists "$PROGRAMFILES\nodejs\node.exe" node_found
  IfFileExists "$LOCALAPPDATA\Programs\nodejs\node.exe" node_found
  
  ; Try PATH-based lookup
  nsExec::ExecToStack 'where node'
  Pop $0
  Pop $1
  ${If} $0 == 0
    Goto node_found
  ${EndIf}
  
  ; Node.js not found — attempt to install
  DetailPrint "Node.js not found. Attempting to install..."
  
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
  
  MessageBox MB_OK|MB_ICONINFORMATION "Node.js is required but could not be installed automatically.$\n$\nPlease install Node.js LTS from https://nodejs.org/$\n$\nAfter installing, restart Coday Twin."
  Goto node_done
  
  node_found:
    DetailPrint "Node.js is already installed"
  node_done:

  ; === ripgrep (optional) ===
  nsExec::ExecToStack 'where rg'
  Pop $0
  Pop $1
  ${If} $0 != 0
    ; Check if winget is available before trying
    nsExec::ExecToStack 'where winget'
    Pop $0
    Pop $1
    ${If} $0 == 0
      DetailPrint "Installing ripgrep via winget..."
      nsExec::ExecToStack 'winget install BurntSushi.ripgrep.MSVC --accept-package-agreements --accept-source-agreements --silent'
      Pop $0
      Pop $1
      ${If} $0 == 0
        DetailPrint "ripgrep installed successfully"
      ${Else}
        DetailPrint "ripgrep install failed (optional - search features may be limited)"
      ${EndIf}
    ${Else}
      DetailPrint "winget not available, skipping ripgrep install (optional)"
    ${EndIf}
  ${Else}
    DetailPrint "ripgrep is already installed"
  ${EndIf}

  ; === Obsidian (optional) ===
  IfFileExists "$PROGRAMFILES64\Obsidian\Obsidian.exe" obsidian_found
  IfFileExists "$LOCALAPPDATA\Obsidian\Obsidian.exe" obsidian_found
  IfFileExists "$LOCALAPPDATA\Programs\obsidian\Obsidian.exe" obsidian_found
  
  ; Check if winget is available before trying
  nsExec::ExecToStack 'where winget'
  Pop $0
  Pop $1
  ${If} $0 == 0
    DetailPrint "Installing Obsidian via winget..."
    nsExec::ExecToStack 'winget install Obsidian.Obsidian --accept-package-agreements --accept-source-agreements --silent'
    Pop $0
    Pop $1
    ${If} $0 == 0
      DetailPrint "Obsidian installed successfully"
    ${Else}
      DetailPrint "Obsidian install failed (optional - install from https://obsidian.md)"
    ${EndIf}
  ${Else}
    DetailPrint "winget not available, skipping Obsidian install (optional)"
  ${EndIf}
  Goto obsidian_done
  
  obsidian_found:
    DetailPrint "Obsidian is already installed"
  obsidian_done:
!macroend
