[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '准备分析验收编译未通过。' }
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'
$runRoot = Join-Path $PSScriptRoot ('runs\preparation-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
function Invoke-Preparation([string[]]$Arguments, [string]$Name, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ($Name + '.stderr.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw "预期拒绝的准备输入竟然成功：$Name" }
        return [pscustomobject]@{exitCode=$code;error=(Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8)}
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $runRoot ($Name + '.json')), $text, $utf8)
    return ($text | ConvertFrom-Json)
}
function Assert-Preparation([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{name=$Name;passed=$true})
}
function Save-Preparation([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 80), $utf8)
    return $path
}
function Copy-Preparation([object]$Value) { return ($Value | ConvertTo-Json -Depth 80 | ConvertFrom-Json) }
function Requirement([object]$Report, [string]$Id) { return @($Report.requirements | Where-Object { $_.id -eq $Id })[0] }
function Stage([object]$Report, [string]$Name) { return @($Report.stages | Where-Object { $_.stage -eq $Name })[0] }
$blueprintFile = Join-Path $PSScriptRoot 'examples\genesis-river-town-versioned.json'
$requirementsFile = Join-Path $PSScriptRoot 'examples\preparation-pickup.json'
$requirementsHash = (Get-FileHash -LiteralPath $requirementsFile).Hash
$request = Get-Content -LiteralPath $requirementsFile -Raw -Encoding UTF8 | ConvertFrom-Json
$draftFile = Join-Path $runRoot 'draft.json'
$worldFile = Join-Path $runRoot 'world.json'
$null = Invoke-Preparation @('construct', $blueprintFile, $draftFile) 'constructed'
$draftHash = (Get-FileHash -LiteralPath $draftFile).Hash
$draftReport = Invoke-Preparation @('prepare-draft', $draftFile, $requirementsFile) 'draft-preparation'
Assert-Preparation '草案分析不建立正式世界、不修改草案或要求输入' ($draftReport.source.kind -eq 'draft' -and $draftReport.source.revision -eq 0 -and !(Test-Path -LiteralPath $worldFile) -and (Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash -and (Get-FileHash -LiteralPath $requirementsFile).Hash -eq $requirementsHash)
Assert-Preparation '同一草案分清世界缺资料与人物尚不知道' ((Requirement $draftReport.authorReport 'locate-cargo').status -eq 'actorUnknown' -and (Requirement $draftReport.authorReport 'weight').status -eq 'worldMissing' -and (Requirement $draftReport.authorReport 'permission').status -eq 'worldMissing')
Assert-Preparation '数量40不当重量，查问已有依据不被取货缺口阻塞' ((Requirement $draftReport.authorReport 'weight').status -eq 'worldMissing' -and (Stage $draftReport.authorReport 'inquire').status -eq 'ready' -and (Stage $draftReport.authorReport 'pickup').status -eq 'needsInput')
$actorText = $draftReport.actorReport | ConvertTo-Json -Depth 80 -Compress
Assert-Preparation '人物报告不泄漏世界真值、隐藏事实存在性或船工私事' ((Requirement $draftReport.actorReport 'locate-cargo').status -eq 'unknown' -and (Requirement $draftReport.actorReport 'weight').status -eq 'unknown' -and !$actorText.Contains('worldEvidence') -and !$actorText.Contains('worldMissing') -and !$actorText.Contains('家中有人生病') -and !$actorText.Contains('"value":"warehouse"'))
Assert-Preparation '草案未决项只原样保留在作者报告，不冒充已解决' ($draftReport.authorReport.unresolved.Count -eq 6 -and !$draftReport.actorReport.PSObject.Properties['unresolved'] -and $draftReport.authorReport.checksScope -eq 'declared-requirements-only')
$null = Invoke-Preparation @('confirm', $draftFile, $worldFile) 'confirmed'
$worldHash = (Get-FileHash -LiteralPath $worldFile).Hash
$initial = Invoke-Preparation @('prepare', $worldFile, $requirementsFile) 'initial-preparation'
Assert-Preparation '正式起点分析使用版本0，仍是零事件且世界字节未变' ($initial.source.kind -eq 'world' -and $initial.source.revision -eq 0 -and (Invoke-Preparation @('history', $worldFile) 'initial-history').events.Count -eq 0 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$null = Invoke-Preparation @('step', $worldFile, 'ask', (Join-Path $PSScriptRoot 'examples\genesis-ask.json')) 'asked'
$askedHash = (Get-FileHash -LiteralPath $worldFile).Hash
$asked = Invoke-Preparation @('prepare', $worldFile, $requirementsFile) 'after-ask-preparation'
Assert-Preparation '实际询问后位置依据就绪，取货仍待补；分析不新增事件' ($asked.source.revision -eq 1 -and (Stage $asked.authorReport 'locate').status -eq 'ready' -and (Stage $asked.authorReport 'pickup').status -eq 'needsInput' -and (Requirement $asked.actorReport 'locate-cargo').actorEvidence.source -eq 'boatman' -and (Invoke-Preparation @('history', $worldFile) 'after-ask-history').events.Count -eq 1 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $askedHash)
$null = Invoke-Preparation @('apply', $worldFile, (Join-Path $PSScriptRoot 'examples\preparation-fill.json')) 'author-supplied-basis'
$filledHash = (Get-FileHash -LiteralPath $worldFile).Hash
$filled = Invoke-Preparation @('prepare', $worldFile, $requirementsFile) 'filled-preparation'
$filledHistory = Invoke-Preparation @('history', $worldFile) 'filled-history'
Assert-Preparation '明确补入六项事实后声明要求通过，仍未领取或运输' ($filled.source.revision -eq 2 -and $filled.authorReport.status -eq 'ready' -and $filled.actorReport.status -eq 'ready' -and $filledHistory.events.Count -eq 2 -and $filledHistory.events[1].proposal.effects.Count -eq 6 -and @($filledHistory.events[1].proposal.effects | Where-Object { $_.type -ne 'fact.define' }).Count -eq 0 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $filledHash)
$denied = [pscustomobject]@{schemaVersion=1;worldId='genesis-river-town';requestId='permission-now-denied';expectedRevision=2;actor='@author';reason='示例明确撤回许可，人物在码头直接观察该项已声明可观察事实';effects=@(@{type='fact.change';factId='cargo-pickup-permission';expectedValue=$true;value=$false},@{type='knowledge.learn';personId='apprentice';factId='cargo-pickup-permission';sourcePersonId='@world'})}
$null = Invoke-Preparation @('apply', $worldFile, (Save-Preparation 'permission-denied.json' $denied)) 'permission-denied-committed'
$deniedHash = (Get-FileHash -LiteralPath $worldFile).Hash
$deniedReport = Invoke-Preparation @('prepare', $worldFile, $requirementsFile) 'denied-preparation'
Assert-Preparation '许可明确false时条件未满足，不能靠有记录就放行' ((Requirement $deniedReport.authorReport 'permission').status -eq 'conditionUnmet' -and (Requirement $deniedReport.actorReport 'permission').status -eq 'conditionUnmet' -and (Stage $deniedReport.authorReport 'pickup').status -eq 'needsInput' -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $deniedHash)
$change = [pscustomobject]@{schemaVersion=1;worldId='genesis-river-town';requestId='cargo-now-at-forge';expectedRevision=3;actor='@author';reason='世界记录明确发生位置变化，阿禾尚未获知';effects=@(@{type='fact.change';factId='cargo-location';expectedValue='warehouse';value='forge'})}
$null = Invoke-Preparation @('apply', $worldFile, (Save-Preparation 'cargo-changed.json' $change)) 'cargo-changed-committed'
$changedHash = (Get-FileHash -LiteralPath $worldFile).Hash
$conflict = Invoke-Preparation @('prepare', $worldFile, $requirementsFile) 'knowledge-conflict-preparation'
Assert-Preparation '记录支持的认识冲突仅作者可见，人物仍保留原值且不猜消息年龄' ((Requirement $conflict.authorReport 'locate-cargo').status -eq 'knowledgeConflict' -and (Requirement $conflict.actorReport 'locate-cargo').status -eq 'ready' -and (Requirement $conflict.actorReport 'locate-cargo').actorEvidence.value -eq 'warehouse' -and !$conflict.actorReport.PSObject.Properties['worldEvidence'])
$fresh = Copy-Preparation $request
@($fresh.requirements | Where-Object { $_.id -eq 'locate-cargo' })[0] | Add-Member -NotePropertyName 'freshness' -NotePropertyValue 'currentRevision'
$freshFile = Save-Preparation 'fresh-requirements.json' $fresh
$freshReport = Invoke-Preparation @('prepare', $worldFile, $freshFile) 'needs-direct-check'
Assert-Preparation '要求当前版本直接核实时，转述快照待核实而非被判成旧或假' ((Requirement $freshReport.actorReport 'locate-cargo').status -eq 'needsRecheck' -and (Requirement $freshReport.authorReport 'locate-cargo').status -eq 'knowledgeConflict' -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $changedHash)
$repeat = Invoke-Preparation @('prepare', $worldFile, $requirementsFile) 'repeated-preparation'
Assert-Preparation '独立进程重开再分析结果一致，正式历史不增长' (($repeat | ConvertTo-Json -Depth 80 -Compress) -eq ($conflict | ConvertTo-Json -Depth 80 -Compress) -and (Invoke-Preparation @('history', $worldFile) 'final-history').events.Count -eq 4 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $changedHash)
$invalid = Copy-Preparation $request
$invalid.requirements = @()
$null = Invoke-Preparation @('prepare', $worldFile, (Save-Preparation 'empty-requirements.json' $invalid)) 'rejected-empty' $true
$invalid = Copy-Preparation $request
$invalid.worldId = 'another-world'
$null = Invoke-Preparation @('prepare', $worldFile, (Save-Preparation 'wrong-world-requirements.json' $invalid)) 'rejected-world' $true
Assert-Preparation '空要求和错误世界身份明确拒绝，正式世界不变' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $changedHash)
$tampered = Get-Content -LiteralPath $draftFile -Raw -Encoding UTF8 | ConvertFrom-Json
$tampered.seed.characters[0].name = '未经确认的新名字'
$null = Invoke-Preparation @('prepare-draft', (Save-Preparation 'tampered-draft.json' $tampered), $requirementsFile) 'rejected-draft' $true
Assert-Preparation '准备分析也核对完整草案，篡改资料不能当成可用起点' ((Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $changedHash -and (Get-FileHash -LiteralPath $requirementsFile).Hash -eq $requirementsHash)
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;runDirectory=$runRoot;results=$results;actualModel=$false;proseQuality=$false;cargoTransferred=$false;checksScope='declared-requirements-only'}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'preparation-cli-check.json'), ($report | ConvertTo-Json -Depth 20), $utf8)
$report | ConvertTo-Json -Depth 20
exit 0
