[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '构造版本验收编译未通过。' }
$runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
$javaTool = $runtime.javaTool
$classPath = $runtime.classPath
$runRoot = Join-Path $PSScriptRoot ('runs\constructor-version-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
function Invoke-Constructor([string[]]$Arguments, [string]$OutputName, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ($OutputName + '.stderr.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期拒绝的构造版本输入竟然成功。' }
        return [pscustomobject]@{exitCode=$code;error=(Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8)}
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $runRoot $OutputName), $text, $utf8)
    return ($text | ConvertFrom-Json)
}
function Assert-Constructor([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{name=$Name;passed=$true})
}
function Save-Constructor([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 80), $utf8)
    return $path
}
function Copy-Constructor([object]$Value) { return ($Value | ConvertTo-Json -Depth 80 | ConvertFrom-Json) }
$legacyFile = Join-Path $PSScriptRoot 'examples\genesis-river-town.json'
$explicitFile = Join-Path $PSScriptRoot 'examples\genesis-river-town-versioned.json'
$fixtureFile = Join-Path $PSScriptRoot 'examples\legacy-river-draft.json'
$fixtureHash = (Get-FileHash -LiteralPath $fixtureFile).Hash
$fixture = Get-Content -LiteralPath $fixtureFile -Raw -Encoding UTF8 | ConvertFrom-Json
$fixtureWorld = Join-Path $runRoot 'legacy-fixture-world.json'
$old = Invoke-Constructor @('confirm', $fixtureFile, $fixtureWorld) 'legacy-fixture-confirmed.json'
Assert-Constructor '读取改版前保存的旧草案，确认零事件起点且不补写版本' ($old.revision -eq 0 -and $old.counts.characters -eq 4 -and !$fixture.blueprint.PSObject.Properties['constructors'] -and !$fixture.construction.modules[0].PSObject.Properties['version'] -and (Invoke-Constructor @('history', $fixtureWorld) 'legacy-fixture-history.json').events.Count -eq 0 -and (Get-FileHash -LiteralPath $fixtureFile).Hash -eq $fixtureHash)
$legacyDraftFile = Join-Path $runRoot 'legacy-draft.json'
$null = Invoke-Constructor @('construct', $legacyFile, $legacyDraftFile) 'legacy-construct.json'
$legacyDraft = Get-Content -LiteralPath $legacyDraftFile -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Constructor '新的缺省蓝图仍输出旧草案格式及相同内容' (($legacyDraft | ConvertTo-Json -Depth 80 -Compress) -eq ($fixture | ConvertTo-Json -Depth 80 -Compress))
$draftFile = Join-Path $runRoot 'explicit-draft.json'
$worldFile = Join-Path $runRoot 'explicit-world.json'
$created = Invoke-Constructor @('construct', $explicitFile, $draftFile) 'explicit-construct.json'
$draft = Get-Content -LiteralPath $draftFile -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Constructor '显式选择八个构造器，保存版本资料名称和依赖且尚无正式世界' ($created.status -eq 'draft-created' -and $draft.construction.modules.Count -eq 8 -and @($draft.construction.modules | Where-Object { $_.version -ne '1' -or !$_.PSObject.Properties['section'] }).Count -eq 0 -and $draft.construction.modules[3].dependencies.Count -eq 3 -and $draft.construction.evidence[0].moduleVersion -eq '1' -and !(Test-Path -LiteralPath $worldFile))
Assert-Constructor '显式版本1与缺省版本1产生同一个世界起点' (($draft.seed | ConvertTo-Json -Depth 80 -Compress) -eq ($legacyDraft.seed | ConvertTo-Json -Depth 80 -Compress))
$draftHash = (Get-FileHash -LiteralPath $draftFile).Hash
$initial = Invoke-Constructor @('confirm', $draftFile, $worldFile) 'explicit-confirmed.json'
Assert-Constructor '完整草案核对后建立正式世界，保持草案字节不变' ($initial.revision -eq 0 -and $initial.counts.characters -eq 4 -and (Invoke-Constructor @('history', $worldFile) 'initial-history.json').events.Count -eq 0 -and (Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash)
$worldHash = (Get-FileHash -LiteralPath $worldFile).Hash
$null = Invoke-Constructor @('confirm', $draftFile, $worldFile) 'duplicate-confirm' $true
Assert-Constructor '重复确认不能重置已建立的世界' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$null = Invoke-Constructor @('construct', $explicitFile, $draftFile) 'duplicate-construct' $true
Assert-Constructor '重复构造不能覆盖原显式草案' ((Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash)
$blueprint = Get-Content -LiteralPath $explicitFile -Raw -Encoding UTF8 | ConvertFrom-Json
$missing = Copy-Constructor $blueprint
$missing.constructors[0].version = '2'
$missingOutput = Join-Path $runRoot 'missing-version-draft.json'
$rejected = Invoke-Constructor @('construct', (Save-Constructor 'missing-version-blueprint.json' $missing), $missingOutput) 'missing-version' $true
Assert-Constructor '指定版本2未注册时明确拒绝，不回退到版本1且不保存草案' ($rejected.error -match '未注册' -and !(Test-Path -LiteralPath $missingOutput))
$duplicate = Copy-Constructor $blueprint
$duplicate.constructors = @($duplicate.constructors) + @([pscustomobject]@{id='world';version='1'})
$duplicateOutput = Join-Path $runRoot 'duplicate-selection-draft.json'
$null = Invoke-Constructor @('construct', (Save-Constructor 'duplicate-selection.json' $duplicate), $duplicateOutput) 'duplicate-selection' $true
Assert-Constructor '一次管线重复选择相同标识被拒绝，无残缺草案' (!(Test-Path -LiteralPath $duplicateOutput))
foreach ($badVersion in @($null, 1, '', ' ', "1`r`n2")) {
    $bad = Copy-Constructor $blueprint
    $bad.constructors[0].version = $badVersion
    $caseName = 'invalid-version-' + [guid]::NewGuid().ToString('N')
    $output = Join-Path $runRoot ($caseName + '-draft.json')
    $null = Invoke-Constructor @('construct', (Save-Constructor ($caseName + '.json') $bad), $output) $caseName $true
    if (Test-Path -LiteralPath $output) { throw '非法版本留下草案。' }
}
Assert-Constructor '显式null、数字、空值、空白和换行版本全部拒绝' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$excluded = Copy-Constructor $blueprint
$excluded.constructors = @([pscustomobject]@{id='world';version='1'}, [pscustomobject]@{id='group';version='1'})
$excludedOutput = Join-Path $runRoot 'excluded-dependency-draft.json'
$dependencyError = Invoke-Constructor @('construct', (Save-Constructor 'excluded-dependency.json' $excluded), $excludedOutput) 'excluded-dependency' $true
Assert-Constructor '依赖未选入管线时停止，不自动加入实现' ($dependencyError.error -match '未选择构造器依赖' -and !(Test-Path -LiteralPath $excludedOutput))
$unhandled = Copy-Constructor $blueprint
$unhandled.constructors = @([pscustomobject]@{id='world';version='1'})
$unhandledOutput = Join-Path $runRoot 'unhandled-section-draft.json'
$null = Invoke-Constructor @('construct', (Save-Constructor 'unhandled-sections.json' $unhandled), $unhandledOutput) 'unhandled-section' $true
Assert-Constructor '已有资料没有选定处理器时拒绝，不静默跳过' (!(Test-Path -LiteralPath $unhandledOutput))
$minimal = Copy-Constructor $unhandled
$minimal.sections = @{world=@{places=$blueprint.sections.world.places}}
$minimalDraft = Invoke-Constructor @('construct', (Save-Constructor 'world-only-blueprint.json' $minimal)) 'world-only-draft.json'
Assert-Constructor '显式只选世界构造器时仅运行该组件，未选组件不产生输出' ($minimalDraft.construction.modules.Count -eq 1 -and $minimalDraft.construction.modules[0].id -eq 'world' -and $minimalDraft.seed.characters.Count -eq 0)
foreach ($caseName in @('seed','module-version','dependencies','evidence-version','blueprint-version','kind','schema','extra-field')) {
    $tampered = Copy-Constructor $draft
    switch ($caseName) {
        'seed' { $tampered.seed.characters[0].name = '偷改人物' }
        'module-version' { $tampered.construction.modules[0].version = '2' }
        'dependencies' { $tampered.construction.modules[3].dependencies = @() }
        'evidence-version' { $tampered.construction.evidence[0].moduleVersion = '2' }
        'blueprint-version' { $tampered.blueprint.constructors[0].version = '2' }
        'kind' { $tampered.kind = 'world-store' }
        'schema' { $tampered.schemaVersion = 2 }
        'extra-field' { $tampered | Add-Member -NotePropertyName unexpected -NotePropertyValue $true }
    }
    $tamperedFile = Save-Constructor ('tampered-' + $caseName + '.json') $tampered
    $tamperedHash = (Get-FileHash -LiteralPath $tamperedFile).Hash
    $blockedWorld = Join-Path $runRoot ('blocked-' + $caseName + '-world.json')
    $null = Invoke-Constructor @('confirm', $tamperedFile, $blockedWorld) ('tampered-' + $caseName) $true
    Assert-Constructor ('拒绝草案篡改：' + $caseName) (!(Test-Path -LiteralPath $blockedWorld) -and (Get-FileHash -LiteralPath $tamperedFile).Hash -eq $tamperedHash -and (Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
}
$ask = Invoke-Constructor @('step', $worldFile, 'ask', (Join-Path $PSScriptRoot 'examples\genesis-ask.json')) 'ask.json'
$travel = Invoke-Constructor @('step', $worldFile, 'travel', (Join-Path $PSScriptRoot 'examples\genesis-travel.json')) 'travel.json'
$view = Invoke-Constructor @('view', $worldFile, 'apprentice') 'actor-after-travel.json'
$history = Invoke-Constructor @('history', $worldFile) 'final-history.json'
Assert-Constructor '显式构造的世界能继续询问及移动，多进程重开保留两个事件' ($ask.revision -eq 1 -and $travel.revision -eq 2 -and $history.events.Count -eq 2 -and $view.self.place -eq 'warehouse')
$hash = (Get-FileHash -LiteralPath $worldFile).Hash
$again = Invoke-Constructor @('step', $worldFile, 'travel', (Join-Path $PSScriptRoot 'examples\genesis-travel.json')) 'travel-repeated.json'
Assert-Constructor '后续行动重试仍复用原回执，构造版本不改变提交规则' ($again.revision -eq 2 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
$null = Invoke-Constructor @('confirm', $draftFile, $draftFile) 'overwrite-input' $true
Assert-Constructor '确认输入不能被世界文件覆盖，旧草案和显式草案都保持原字节' ((Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash -and (Get-FileHash -LiteralPath $fixtureFile).Hash -eq $fixtureHash)
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;runDirectory=$runRoot;results=$results;legacyFixture=$fixtureFile;actualModelCalled=$false;proseQualityEvaluated=$false;evidenceLimit='CLI仅内置构造版本1；同标识两版本并存与身份漂移另由独立Java专项验证。完整草案核对不是代码签名或断电试验。'}
$null = Save-Constructor 'constructor-version-cli-check.json' $report
$report | ConvertTo-Json -Depth 8
exit 0
