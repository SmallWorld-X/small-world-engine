[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

if ($env:JAVA_HOME) {
    $javaBin = Join-Path $env:JAVA_HOME 'bin'
    $jdkSource = 'JAVA_HOME'
} else {
    $compiler = Get-Command -Name javac.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$compiler) { throw '需要 Java 21 JDK。请设置 JAVA_HOME，或将 JDK 21 的 bin 目录加入 PATH。' }
    $javaBin = Split-Path -Parent $compiler.Source
    $jdkSource = 'PATH'
}
$javaTool = Join-Path $javaBin 'java.exe'
$compilerTool = Join-Path $javaBin 'javac.exe'
foreach ($tool in @($javaTool, $compilerTool)) {
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "$jdkSource 未指向完整的 Java 21 JDK：缺少 $tool。请修正配置后重试。"
    }
    $versionOutput = & $tool --version 2>&1
    $versionExitCode = $LASTEXITCODE
    $versionText = $versionOutput -join [Environment]::NewLine
    if ($versionExitCode -ne 0 -or $versionText -notmatch '(?m)^(?:javac|java|openjdk) 21(?:[. +\-]|$)') {
        throw "$jdkSource 需要 Java 21 的 java 和 javac：$tool 返回 $versionText。"
    }
}

$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object {
    $jarFile = Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar"
    if (!(Test-Path -LiteralPath $jarFile -PathType Leaf)) { throw "缺少本地 JSON 依赖：$jarFile。请按 README 下载 Jackson 2.19.2。" }
    $jarFile
}
$classesRoot = Join-Path $PSScriptRoot 'build\classes'
[pscustomobject]@{
    javaTool = $javaTool
    compilerTool = $compilerTool
    classesRoot = $classesRoot
    temporaryRoot = Join-Path $PSScriptRoot 'build\tmp'
    classPath = (@($classesRoot) + $dependencies) -join ';'
    jdkSource = $jdkSource
}
