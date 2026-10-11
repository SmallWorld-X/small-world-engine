[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '编译未通过。' }
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'
$runRoot = Join-Path $PSScriptRoot ('runs\cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$worldFile = Join-Path $runRoot 'world.json'
$results = [System.Collections.Generic.List[object]]::new()
$utf8 = [System.Text.UTF8Encoding]::new($false)
function Invoke-World([string[]]$Arguments, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ('stderr-' + [guid]::NewGuid().ToString('N') + '.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期被拒绝的操作竟然成功。' }
        return [pscustomobject]@{ ExitCode = $code; Error = (Get-Content -LiteralPath $errorFile -Raw) }
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw) }
    return (($lines -join [Environment]::NewLine) | ConvertFrom-Json)
}
function Assert-World([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{ name = $Name; passed = $true })
}
function Save-Proposal([string]$Name, [object]$Proposal) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Proposal | ConvertTo-Json -Depth 25), $utf8)
    return $path
}
$initial = Invoke-World @('init', (Join-Path $PSScriptRoot 'examples\river-town.json'), $worldFile)
Assert-World '命令行建立初始世界' ($initial.revision -eq 0)
$before = Invoke-World @('view', $worldFile, 'apprentice')
Assert-World '中文姓名通过命令行输出保持一致' ($before.self.name -eq '阿禾')
Assert-World '人物未提前获知货箱位置或他人私事' (!($before.knowledge | Where-Object factId -eq 'cargo-location') -and !($before | ConvertTo-Json -Depth 20).Contains('家中有人生病'))
$jump = Save-Proposal 'jump.json' @{schemaVersion=1;worldId='river-town';requestId='bad-jump';expectedRevision=0;actor='apprentice';to='forge'}
$hashBefore = (Get-FileHash -LiteralPath $worldFile).Hash
$null = Invoke-World @('step', $worldFile, 'travel', $jump) $true
Assert-World '直接跳到不相连地点被拒绝且存档不变' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $hashBefore)
$askPath = Join-Path $PSScriptRoot 'examples\ask.json'
$first = Invoke-World @('step', $worldFile, 'ask', $askPath)
$repeat = Invoke-World @('step', $worldFile, 'ask', $askPath)
Assert-World '跨进程重复询问返回原结果且只追加一次' ($first.revision -eq 1 -and $repeat.revision -eq 1 -and (Invoke-World @('history', $worldFile)).events.Count -eq 1)
$afterAsk = Invoke-World @('view', $worldFile, 'apprentice')
Assert-World '询问后取得船工来源的消息' (($afterAsk.knowledge | Where-Object factId -eq 'cargo-location').source -eq 'boatman')
$walk = Invoke-World @('step', $worldFile, 'travel', (Join-Path $PSScriptRoot 'examples\travel.json'))
Assert-World '按已建立通路到达货栈' ($walk.revision -eq 2 -and (Invoke-World @('view', $worldFile, 'apprentice')).self.place -eq 'warehouse')
$moved = Invoke-World @('apply', $worldFile, (Join-Path $PSScriptRoot 'examples\cargo-moved.json'))
$author = Invoke-World @('view', $worldFile, '@author')
$actor = Invoke-World @('view', $worldFile, 'apprentice')
Assert-World '当前真实状态改变而人物旧听闻保留' ($moved.revision -eq 3 -and ($author.facts | Where-Object id -eq 'cargo-location').value -eq 'forge' -and ($actor.knowledge | Where-Object factId -eq 'cargo-location').value -eq 'warehouse')
$stale = Save-Proposal 'stale.json' @{schemaVersion=1;worldId='river-town';requestId='stale';expectedRevision=0;actor='apprentice';to='warehouse'}
$hashBefore = (Get-FileHash -LiteralPath $worldFile).Hash
$null = Invoke-World @('step', $worldFile, 'travel', $stale) $true
$null = Invoke-World @('init', (Join-Path $PSScriptRoot 'examples\river-town.json'), $worldFile) $true
Assert-World '过期输入和覆盖已有世界均被拒绝' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $hashBefore)
$badInputPath = Save-Proposal 'input-protected.json' @{schemaVersion=1;worldId='river-town';requestId='protected';expectedRevision=3;actor='apprentice';to='forge'}
$inputHash = (Get-FileHash -LiteralPath $badInputPath).Hash
$null = Invoke-World @('step', $badInputPath, 'travel', $badInputPath) $true
Assert-World '不能把输入文件当作存档覆盖' ((Get-FileHash -LiteralPath $badInputPath).Hash -eq $inputHash)
$restart = Join-Path $runRoot 'new-world.json'
$null = Invoke-World @('init', (Join-Path $PSScriptRoot 'examples\river-town.json'), $restart)
Assert-World '另建世界不会改写旧存档' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $hashBefore -and (Invoke-World @('inspect', $restart)).revision -eq 0)
function Invoke-Concurrent([string]$Store, [string]$Command, [string[]]$Inputs) {
    $jobs = foreach ($inputFile in $Inputs) {
        Start-Job -ScriptBlock {
            param($Java, $Classpath, $StorePath, $Operation, $InputPath, $ErrorPath)
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            $ErrorActionPreference = 'Continue'
            if ($Operation -eq 'step') { $arguments = @('step', $StorePath, 'ask', $InputPath) }
            else { $arguments = @('apply', $StorePath, $InputPath) }
            $output = & $Java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Classpath creativeworld.WorldCli @arguments 2> $ErrorPath
            [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output -join [Environment]::NewLine) }
        } -ArgumentList $javaTool, $classPath, $Store, $Command, $inputFile, (Join-Path $runRoot ('concurrent-error-' + [guid]::NewGuid().ToString('N') + '.txt'))
    }
    return @(Receive-Job -Job $jobs -Wait -AutoRemoveJob)
}
$duplicateWorld = Join-Path $runRoot 'concurrent-duplicate.json'
$null = Invoke-World @('init', (Join-Path $PSScriptRoot 'examples\river-town.json'), $duplicateWorld)
$parallelDuplicates = Invoke-Concurrent $duplicateWorld 'step' @($askPath, $askPath)
Assert-World '两个进程同时提交同一输入只保留一个事件' ($parallelDuplicates.Count -eq 2 -and @($parallelDuplicates | Where-Object ExitCode -ne 0).Count -eq 0 -and (Invoke-World @('history', $duplicateWorld)).events.Count -eq 1)
$conflictWorld = Join-Path $runRoot 'concurrent-conflict.json'
$null = Invoke-World @('init', (Join-Path $PSScriptRoot 'examples\river-town.json'), $conflictWorld)
$parallelInputs = foreach ($label in @('one', 'two')) {
    Save-Proposal "parallel-$label.json" @{schemaVersion=1;worldId='river-town';requestId="parallel-$label";expectedRevision=0;actor='apprentice';reason='并发提交同一版本的两种候选';effects=@(@{type='belief.add';personId='apprentice';text="候选认识-$label"})}
}
$parallelConflicts = Invoke-Concurrent $conflictWorld 'apply' $parallelInputs
Assert-World '两个进程争用同一版本只有一个成功且记录可恢复' ($parallelConflicts.Count -eq 2 -and @($parallelConflicts | Where-Object ExitCode -eq 0).Count -eq 1 -and (Invoke-World @('history', $conflictWorld)).events.Count -eq 1 -and (Invoke-World @('inspect', $conflictWorld)).revision -eq 1)
[System.IO.File]::WriteAllText((Join-Path $runRoot 'author-view.json'), ($author | ConvertTo-Json -Depth 25), $utf8)
[System.IO.File]::WriteAllText((Join-Path $runRoot 'actor-view.json'), ($actor | ConvertTo-Json -Depth 25), $utf8)
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;results=$results;actualModelCalled=$false;naturalLanguageIntentParsed=$false;proseQualityEvaluated=$false;runDirectory=$runRoot}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'cli-check.json'), ($report | ConvertTo-Json -Depth 15), $utf8)
$report | ConvertTo-Json -Depth 15
