# Installs the MateClaw Native Messaging host for the current user across
# Chrome, Edge and Brave (all Chromium-family browsers share the manifest contract).
# Run from PowerShell; if scripts are blocked, use: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
param(
    [Parameter(Mandatory=$true)]
    [string]$BridgePath,

    [Parameter(Mandatory=$true)]
    [string]$ExtensionId
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($BridgePath)) {
    throw "BridgePath is required. Usage: .\install-windows.ps1 -BridgePath C:\path\to\bridge.exe -ExtensionId EXTENSION_ID"
}

if ([string]::IsNullOrWhiteSpace($ExtensionId)) {
    throw "ExtensionId is required. Usage: .\install-windows.ps1 -BridgePath C:\path\to\bridge.exe -ExtensionId EXTENSION_ID"
}

$HostName = 'com.mateclaw.browser_bridge'
$ManifestName = "$HostName.json"
$TemplatePath = Join-Path $PSScriptRoot "manifest\$ManifestName"
$InstallDir = Join-Path $env:LOCALAPPDATA 'MateClaw'
$ManifestPath = Join-Path $InstallDir $ManifestName

# Chromium-family browsers that share the same NativeMessagingHosts contract. They all
# point to the SAME manifest path; a per-branch failure must not abort the others.
$RegistryBranches = @(
    @{ Label = 'Chrome'; Path = "HKCU:\Software\Google\Chrome\NativeMessagingHosts\$HostName" }
    @{ Label = 'Edge';   Path = "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\$HostName" }
    @{ Label = 'Brave';  Path = "HKCU:\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\$HostName" }
)

if (-not (Test-Path -LiteralPath $TemplatePath)) {
    throw "Manifest template not found: $TemplatePath"
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

# PowerShell regex replacements treat backslash as literal here; this writes JSON-safe \\ path separators.
$EscapedBridgePath = $BridgePath -replace '\\', '\\'
$Content = Get-Content -LiteralPath $TemplatePath -Raw
$Content = $Content -replace '__BRIDGE_BINARY_PATH__', $EscapedBridgePath
$Content = $Content -replace '__EXTENSION_ID__', $ExtensionId
Set-Content -LiteralPath $ManifestPath -Value $Content -Encoding UTF8

$registered = @()
foreach ($branch in $RegistryBranches) {
    # Each branch is isolated: a failure (e.g. a browser whose registry root is locked)
    # is reported but never stops the remaining browsers from being registered.
    try {
        New-Item -Path $branch.Path -Force | Out-Null
        Set-Item -Path $branch.Path -Value $ManifestPath
        $registered += $branch.Label
        Write-Host "Registered $($branch.Label): $($branch.Path)"
    } catch {
        Write-Warning "Failed to register $($branch.Label) ($($branch.Path)): $($_.Exception.Message)"
    }
}

Write-Host "MateClaw Native Messaging host installed for current user."
Write-Host "Browsers:      $([string]::Join(', ', $registered))"
Write-Host "Manifest:      $ManifestPath"
Write-Host "Bridge binary: $BridgePath"
Write-Host "Extension ID:  $ExtensionId"
Write-Host "Restart the browser(s) for the Native Messaging host registration to take effect."
