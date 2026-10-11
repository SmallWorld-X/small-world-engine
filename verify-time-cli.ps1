[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '共同时间编译未通过。' }
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'
$runRoot = Join-Path $PSScriptRoot ('runs\time-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
function Invoke-Time([string[]]$Arguments, [string]$OutputName, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ($OutputName + '.stderr.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期拒绝的时间输入竟然成功。' }
        return [pscustomobject]@{exitCode=$code;error=(Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8)}
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $runRoot $OutputName), $text, $utf8)
    return ($text | ConvertFrom-Json)
}
function Assert-Time([string]$Name, [bool]$Condition) {
    $results.Add([pscustomobject]@{name=$Name;passed=$Condition})
    if (!$Condition) { throw "验证失败：$Name" }
}
function Save-Time([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 80), $utf8)
    return $path
}
function Person-Time([object]$View, [string]$Id) { return @($View.characters | Where-Object id -eq $Id)[0] }
$draftFile = Join-Path $runRoot 'draft.json'
$worldFile = Join-Path $runRoot 'world.json'
$advanceFile = Join-Path $PSScriptRoot 'examples\time-advance.json'
$relationsFile = Join-Path $PSScriptRoot 'examples\time-relations.json'
try {
    $null = Invoke-Time @('construct', (Join-Path $PSScriptRoot 'examples\time-town.json'), $draftFile) 'construct-result.json'
    $draft = Get-Content -LiteralPath $draftFile -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Time '草案实际保留共同年度时钟和三个出生年份' ($draft.seed.worldTime.year -eq 100 -and $draft.seed.characters.Count -eq 3 -and $draft.seed.characters.birthYear.Count -eq 3)
    $null = Invoke-Time @('confirm', $draftFile, $worldFile) 'initial.json'
    $before = Invoke-Time @('view', $worldFile, '@author') 'before-author.json'
    Assert-Time '初始同一世界中两人十六岁、远处陈川二十二岁' ((Person-Time $before 'zhou-ning').age -eq 16 -and (Person-Time $before 'xu-cheng').age -eq 16 -and (Person-Time $before 'chen-chuan').age -eq 22)
    $personal = Invoke-Time @('view', $worldFile, 'zhou-ning') 'before-person.json'
    Assert-Time '本人投影不包含远处角色或附近人的私密资料' ($personal.nearby.Count -eq 1 -and $personal.nearby[0].id -eq 'xu-cheng' -and !$personal.nearby[0].PSObject.Properties['privateProfile'] -and !$personal.PSObject.Properties['characters'])
    $firstReceipt = Invoke-Time @('apply', $worldFile, $advanceFile) 'advance-receipt.json'
    $after = Invoke-Time @('view', $worldFile, '@author') 'after-time-author.json'
    Assert-Time '世界推进十年，未出场人物也与主角同时长十岁' ($after.worldTime.year -eq 110 -and $after.revision -eq 1 -and (Person-Time $after 'zhou-ning').age -eq 26 -and (Person-Time $after 'xu-cheng').age -eq 26 -and (Person-Time $after 'chen-chuan').age -eq 32)
    $zhou = Person-Time $after 'zhou-ning'
    Assert-Time '旧职业与心情保留原年份，不能冒充当前快照' ($zhou.publicProfileAsOfYear -eq 100 -and $zhou.privateProfileAsOfYear -eq 100 -and $zhou.publicProfileStatus -eq 'historical' -and $zhou.privateProfileStatus -eq 'historical')
    Assert-Time '时间流逝没有凭空改变名字、地点、道路和固定背景' (($before.places | ConvertTo-Json -Depth 20 -Compress) -eq ($after.places | ConvertTo-Json -Depth 20 -Compress) -and ($before.routes | ConvertTo-Json -Depth 20 -Compress) -eq ($after.routes | ConvertTo-Json -Depth 20 -Compress) -and ($before.facts | ConvertTo-Json -Depth 20 -Compress) -eq ($after.facts | ConvertTo-Json -Depth 20 -Compress) -and $zhou.name -eq '周宁' -and $zhou.place -eq 'xu-house')
    Assert-Time '仅推进时间并不会自行使两人成为同学' (($before.relationships | ConvertTo-Json -Depth 20 -Compress) -eq ($after.relationships | ConvertTo-Json -Depth 20 -Compress))
    $old = Invoke-Time @('view-at', $worldFile, 'zhou-ning', '0') 'historical-start.json'
    Assert-Time '命令行历史投影恢复十六岁、百年起点与旧关系' ($old.worldTime.year -eq 100 -and $old.self.age -eq 16 -and $old.relationships[0].value -eq '主人家的晚辈' -and $old.relationships[0].occurredAtYear -eq 100)
    $secondReceipt = Invoke-Time @('apply', $worldFile, $relationsFile) 'relations-receipt.json'
    $current = Invoke-Time @('view', $worldFile, 'zhou-ning') 'current-person.json'
    Assert-Time '新事件有依据地改变关系，同年多提交不增年龄' ($current.worldTime.year -eq 110 -and $current.revision -eq 2 -and $current.self.age -eq 26 -and $current.relationships[0].value -eq '同学' -and $current.relationships[0].occurredAtYear -eq 110 -and $current.relationships[0].basis.Length -gt 0)
    $atOne = Invoke-Time @('view-at', $worldFile, 'zhou-ning', '1') 'historical-after-time.json'
    Assert-Time '关系变化前的历史仍为原关系，不被新关系覆盖' ($atOne.worldTime.year -eq 110 -and $atOne.relationships[0].value -eq '主人家的晚辈' -and $atOne.relationships[0].occurredAtYear -eq 100)
    $history = Invoke-Time @('history', $worldFile) 'history.json'
    Assert-Time '正式历史两条事件各有一致的共同时间' ($history.events.Count -eq 2 -and $history.events[0].worldTime.year -eq 110 -and $history.events[1].worldTime.year -eq 110)
    $worldHash = (Get-FileHash -LiteralPath $worldFile).Hash
    $againFirst = Invoke-Time @('apply', $worldFile, $advanceFile) 'advance-repeated.json'
    $againSecond = Invoke-Time @('apply', $worldFile, $relationsFile) 'relations-repeated.json'
    Assert-Time '重开进程重复两次提交返回原回执，世界字节不变' ($againFirst.revision -eq $firstReceipt.revision -and $againSecond.revision -eq $secondReceipt.revision -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
    $backward = Get-Content -LiteralPath $advanceFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $backward.requestId = 'time-backward'
    $backward.expectedRevision = 2
    $backward.effects[0].expectedYear = 110
    $backward.effects[0].toYear = 109
    $backwardFile = Save-Time 'backward.json' $backward
    $null = Invoke-Time @('apply', $worldFile, $backwardFile) 'backward' $true
    Assert-Time '倒退世界时间被拒绝，正式世界不变' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
    $mixed = Get-Content -LiteralPath $backwardFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $mixed.requestId = 'time-mixed-invalid'
    $mixed.effects[0].toYear = 111
    $mixed.effects += [pscustomobject]@{type='fact.change';factId='north-city-position';expectedValue=@($before.facts | Where-Object id -eq 'north-city-position')[0].value;value='南方海岛'}
    $mixedFile = Save-Time 'mixed-invalid.json' $mixed
    $null = Invoke-Time @('apply', $worldFile, $mixedFile) 'mixed-invalid' $true
    Assert-Time '先推进再改固定地理失败，整个事务包括时间均不保存' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
    $corrupt = Get-Content -LiteralPath $worldFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $corrupt.events[0].worldTime.year = 109
    $corruptFile = Save-Time 'wrong-event-time-world.json' $corrupt
    $null = Invoke-Time @('view', $corruptFile, '@author') 'wrong-event-time' $true
    Assert-Time '副本历史的伪造事件时间在恢复时被拒绝' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
    $unknown = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'examples\time-town.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $unknown.worldId = 'unknown-birth-world'
    $null = $unknown.sections.people[2].PSObject.Properties.Remove('birthYear')
    $unknownInput = Save-Time 'unknown-birth-blueprint.json' $unknown
    $unknownDraft = Invoke-Time @('construct', $unknownInput) 'unknown-birth-draft.json'
    $unknownDraftFile = Join-Path $runRoot 'unknown-birth-draft.json'
    $unknownWorld = Join-Path $runRoot 'unknown-birth-world.json'
    $null = Invoke-Time @('confirm', $unknownDraftFile, $unknownWorld) 'unknown-birth-initial.json'
    $unknownView = Invoke-Time @('view', $unknownWorld, '@author') 'unknown-birth-view.json'
    Assert-Time '未提供出生年份时年龄保持未知，不由外貌猜测' ($null -eq (Person-Time $unknownView 'chen-chuan').age)
    $future = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'examples\time-town.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $future.sections.people[2].birthYear = 111
    $futureFile = Save-Time 'future-birth-blueprint.json' $future
    $futureDraftFile = Join-Path $runRoot 'future-birth-draft.json'
    $null = Invoke-Time @('construct', $futureFile, $futureDraftFile) 'future-birth' $true
    Assert-Time '未来出生人物被拒绝，不留下不完整草案' (!(Test-Path -LiteralPath $futureDraftFile))
    $legacyWorld = Join-Path $runRoot 'legacy-world.json'
    $null = Invoke-Time @('init', (Join-Path $PSScriptRoot 'examples\river-town.json'), $legacyWorld) 'legacy-initial.json'
    $legacyView = Invoke-Time @('view', $legacyWorld, 'apprentice') 'legacy-view.json'
    Assert-Time '无时钟旧世界保持原投影，不静默补年代' (!$legacyView.PSObject.Properties['worldTime'] -and !$legacyView.self.PSObject.Properties['publicProfileAsOfYear'])
}
finally {
    $passed = @($results | Where-Object passed -eq $true).Count
    $failed = @($results | Where-Object passed -eq $false).Count
    $report = [pscustomobject]@{checks=$results.Count;passed=$passed;failed=$failed;runDirectory=$runRoot;results=$results;actualModelCalled=$false;lifeExperiencesAutomaticallyDerived=$false}
    $null = Save-Time 'time-cli-check.json' $report
    $report | ConvertTo-Json -Depth 8
}
