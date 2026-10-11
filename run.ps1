[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$GenesisCheck,
    [switch]$ExperienceCheck,
    [switch]$GoalCheck,
    [switch]$TimeCheck,
    [switch]$ModuleVersionCheck,
    [switch]$ConstructorVersionCheck,
    [switch]$PreparationCheck,
    [switch]$ReactionCheck,
    [switch]$SkeletonCheck,
    [switch]$IdentityCheck,
    [switch]$ContinuationCheck,
    [switch]$BuildOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$EngineArgs
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$projectRoot = $PSScriptRoot
$runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
$javaTool = $runtime.javaTool
$compilerTool = $runtime.compilerTool
$classesRoot = $runtime.classesRoot
$temporaryRoot = $runtime.temporaryRoot
New-Item -ItemType Directory -Path $classesRoot, $temporaryRoot -Force | Out-Null
$classPath = $runtime.classPath
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
if ($Check -or $GenesisCheck -or $ExperienceCheck -or $GoalCheck -or $TimeCheck -or $ModuleVersionCheck -or $ConstructorVersionCheck -or $PreparationCheck -or $ReactionCheck -or $SkeletonCheck -or $IdentityCheck -or $ContinuationCheck) { $sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\test\java') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName }) }
& $compilerTool '-J-Dfile.encoding=UTF-8' '-J-Dstdout.encoding=UTF-8' '-J-Dstderr.encoding=UTF-8' --release 21 -encoding UTF-8 -Xlint:all -cp $classPath -d $classesRoot @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败。' }
if ($BuildOnly) { Write-Output 'Java 21 编译完成。'; exit 0 }
$javaOptions = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', "-Djava.io.tmpdir=$temporaryRoot", '-cp', $classPath)
if ($Check -or $GenesisCheck -or $ExperienceCheck -or $GoalCheck -or $TimeCheck -or $ModuleVersionCheck -or $ConstructorVersionCheck -or $PreparationCheck -or $ReactionCheck -or $SkeletonCheck -or $IdentityCheck -or $ContinuationCheck) {
    if (@(@($Check, $GenesisCheck, $ExperienceCheck, $GoalCheck, $TimeCheck, $ModuleVersionCheck, $ConstructorVersionCheck, $PreparationCheck, $ReactionCheck, $SkeletonCheck, $IdentityCheck, $ContinuationCheck) | Where-Object { $_ }).Count -ne 1) { throw '一次选择一套检查，分别保存原始结果。' }
    $checkClass = if ($ContinuationCheck) { 'creativeworld.ContinuationCheck' } elseif ($IdentityCheck) { 'creativeworld.IdentityCheck' } elseif ($SkeletonCheck) { 'creativeworld.SkeletonCheck' } elseif ($ReactionCheck) { 'creativeworld.MessageReactionCheck' } elseif ($PreparationCheck) { 'creativeworld.PreparationCheck' } elseif ($ConstructorVersionCheck) { 'creativeworld.ConstructorVersionCheck' } elseif ($ModuleVersionCheck) { 'creativeworld.ModuleVersionCheck' } elseif ($TimeCheck) { 'creativeworld.TimeCheck' } elseif ($GoalCheck) { 'creativeworld.GoalCheck' } elseif ($ExperienceCheck) { 'creativeworld.ExperienceCheck' } elseif ($GenesisCheck) { 'creativeworld.GenesisCheck' } else { 'creativeworld.KernelCheck' }
    $reportName = if ($ContinuationCheck) { 'continuation-check.json' } elseif ($IdentityCheck) { 'identity-check.json' } elseif ($SkeletonCheck) { 'skeleton-check.json' } elseif ($ReactionCheck) { 'message-reaction-check.json' } elseif ($PreparationCheck) { 'preparation-check.json' } elseif ($ConstructorVersionCheck) { 'constructor-version-check.json' } elseif ($ModuleVersionCheck) { 'module-version-check.json' } elseif ($TimeCheck) { 'time-check.json' } elseif ($GoalCheck) { 'goal-check.json' } elseif ($ExperienceCheck) { 'experience-check.json' } elseif ($GenesisCheck) { 'genesis-check.json' } else { 'kernel-check.json' }
    $checkArguments = if ($GenesisCheck -or $ExperienceCheck -or $GoalCheck -or $TimeCheck -or $ModuleVersionCheck -or $ConstructorVersionCheck -or $PreparationCheck -or $ReactionCheck -or $SkeletonCheck -or $IdentityCheck -or $ContinuationCheck) { @((Join-Path $projectRoot "build\$reportName"), (Join-Path $projectRoot 'examples')) } else { @() }
    $checkLines = & $javaTool @javaOptions $checkClass @checkArguments
    $checkExitCode = $LASTEXITCODE
    $checkText = $checkLines -join [Environment]::NewLine
    $null = $checkText | ConvertFrom-Json
    [System.IO.File]::WriteAllText((Join-Path $projectRoot "build\$reportName"), $checkText, [System.Text.UTF8Encoding]::new($false))
    Write-Output $checkText
    exit $checkExitCode
}
else { & $javaTool @javaOptions creativeworld.WorldCli @EngineArgs }
exit $LASTEXITCODE
