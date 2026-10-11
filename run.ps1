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
    [switch]$BuildOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$EngineArgs
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$projectRoot = $PSScriptRoot
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$compilerTool = Join-Path $javaRoot 'bin\javac.exe'
if (!(Test-Path -LiteralPath $compilerTool)) { throw '需要 Java 21 JDK。请设置 JAVA_HOME 后重试。' }
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object {
    $jarFile = Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar"
    if (!(Test-Path -LiteralPath $jarFile)) { throw "缺少本地 JSON 依赖：$jarFile" }
    $jarFile
}
$classesRoot = Join-Path $projectRoot 'build\classes'
$temporaryRoot = Join-Path $projectRoot 'build\tmp'
New-Item -ItemType Directory -Path $classesRoot, $temporaryRoot -Force | Out-Null
$classPath = (@($classesRoot) + $dependencies) -join ';'
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
if ($Check -or $GenesisCheck -or $ExperienceCheck -or $GoalCheck -or $TimeCheck -or $ModuleVersionCheck -or $ConstructorVersionCheck -or $PreparationCheck -or $ReactionCheck -or $SkeletonCheck -or $IdentityCheck) { $sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\test\java') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName }) }
& $compilerTool '-J-Dfile.encoding=UTF-8' '-J-Dstdout.encoding=UTF-8' '-J-Dstderr.encoding=UTF-8' --release 21 -encoding UTF-8 -Xlint:all -cp $classPath -d $classesRoot @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败。' }
if ($BuildOnly) { Write-Output 'Java 21 编译完成。'; exit 0 }
$javaOptions = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', "-Djava.io.tmpdir=$temporaryRoot", '-cp', $classPath)
if ($Check -or $GenesisCheck -or $ExperienceCheck -or $GoalCheck -or $TimeCheck -or $ModuleVersionCheck -or $ConstructorVersionCheck -or $PreparationCheck -or $ReactionCheck -or $SkeletonCheck -or $IdentityCheck) {
    if (@(@($Check, $GenesisCheck, $ExperienceCheck, $GoalCheck, $TimeCheck, $ModuleVersionCheck, $ConstructorVersionCheck, $PreparationCheck, $ReactionCheck, $SkeletonCheck, $IdentityCheck) | Where-Object { $_ }).Count -ne 1) { throw '一次选择一套检查，分别保存原始结果。' }
    $checkClass = if ($IdentityCheck) { 'creativeworld.IdentityCheck' } elseif ($SkeletonCheck) { 'creativeworld.SkeletonCheck' } elseif ($ReactionCheck) { 'creativeworld.MessageReactionCheck' } elseif ($PreparationCheck) { 'creativeworld.PreparationCheck' } elseif ($ConstructorVersionCheck) { 'creativeworld.ConstructorVersionCheck' } elseif ($ModuleVersionCheck) { 'creativeworld.ModuleVersionCheck' } elseif ($TimeCheck) { 'creativeworld.TimeCheck' } elseif ($GoalCheck) { 'creativeworld.GoalCheck' } elseif ($ExperienceCheck) { 'creativeworld.ExperienceCheck' } elseif ($GenesisCheck) { 'creativeworld.GenesisCheck' } else { 'creativeworld.KernelCheck' }
    $reportName = if ($IdentityCheck) { 'identity-check.json' } elseif ($SkeletonCheck) { 'skeleton-check.json' } elseif ($ReactionCheck) { 'message-reaction-check.json' } elseif ($PreparationCheck) { 'preparation-check.json' } elseif ($ConstructorVersionCheck) { 'constructor-version-check.json' } elseif ($ModuleVersionCheck) { 'module-version-check.json' } elseif ($TimeCheck) { 'time-check.json' } elseif ($GoalCheck) { 'goal-check.json' } elseif ($ExperienceCheck) { 'experience-check.json' } elseif ($GenesisCheck) { 'genesis-check.json' } else { 'kernel-check.json' }
    $checkArguments = if ($GenesisCheck -or $ExperienceCheck -or $GoalCheck -or $TimeCheck -or $ModuleVersionCheck -or $ConstructorVersionCheck -or $PreparationCheck -or $ReactionCheck -or $SkeletonCheck -or $IdentityCheck) { @((Join-Path $projectRoot "build\$reportName"), (Join-Path $projectRoot 'examples')) } else { @() }
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
