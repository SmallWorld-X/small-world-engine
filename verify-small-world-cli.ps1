[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$runRoot = Join-Path $PSScriptRoot ('runs\small-world-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$calls = [System.Collections.Generic.List[object]]::new()
$inputs = [System.Collections.Generic.List[object]]::new()
$sourceHashes = [System.Collections.Generic.List[object]]::new()
$eventPlan = [System.Collections.Generic.List[object]]::new()
$journeys = [System.Collections.Generic.List[object]]::new()
$failure = $null
$actualEventCount = $null
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'

function Save-Input([string]$Name, [object]$Value, [string]$Source = '') {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 90), $utf8)
    $inputs.Add([pscustomobject]@{path=$path;source=$Source;expectedRevision=$Value.expectedRevision;requestId=$Value.requestId})
    return $path
}
function Read-Json([string]$Path) { return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json) }
function Copy-Json([object]$Value) { return ($Value | ConvertTo-Json -Depth 90 | ConvertFrom-Json) }
function Comparable-Json([object]$Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [array]) { return ('[' + (@($Value | ForEach-Object { Comparable-Json $_ }) -join ',') + ']') }
    if ($Value -is [System.Collections.IDictionary]) {
        $pairs = @($Value.Keys | Sort-Object | ForEach-Object { (ConvertTo-Json -InputObject ([string]$_) -Compress) + ':' + (Comparable-Json $Value[$_]) })
        return ('{' + ($pairs -join ',') + '}')
    }
    if ($Value -is [pscustomobject]) {
        $pairs = @($Value.PSObject.Properties.Name | Sort-Object | ForEach-Object { (ConvertTo-Json -InputObject ([string]$_) -Compress) + ':' + (Comparable-Json $Value.$_) })
        return ('{' + ($pairs -join ',') + '}')
    }
    return (ConvertTo-Json -InputObject $Value -Compress)
}
function Same-Json([object]$Left, [object]$Right) { return ((Comparable-Json $Left) -ceq (Comparable-Json $Right)) }
function Hash-File([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Assert-Small([string]$Name, [bool]$Condition) {
    $results.Add([pscustomobject]@{name=$Name;passed=$Condition})
    if (!$Condition) { throw "验证失败：$Name" }
}
function Invoke-Small([string[]]$Arguments, [string]$Name, [string[]]$InputPaths = @(), [bool]$ExpectFailure = $false) {
    $stdout = Join-Path $runRoot ($Name + '.stdout.json')
    $stderr = Join-Path $runRoot ($Name + '.stderr.txt')
    $ErrorActionPreference = 'Continue'
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $stderr
    $code = $LASTEXITCODE
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($stdout, $text, $utf8)
    $calls.Add([pscustomobject]@{name=$Name;command='WorldCli';arguments=$Arguments;inputPaths=$InputPaths;stdout=$stdout;stderr=$stderr;exitCode=$code;expectedFailure=$ExpectFailure})
    if ($ExpectFailure) {
        Assert-Small "$Name 明确以非零退出码拒绝" ($code -ne 0)
        return [pscustomobject]@{exitCode=$code;stderr=$stderr}
    }
    if ($code -ne 0) { throw "独立进程失败：$Name；退出码 $code；错误文件 $stderr" }
    return ($text | ConvertFrom-Json)
}
function Find-Row([object[]]$Rows, [string]$Field, [string]$Value) { return @($Rows | Where-Object { $_.$Field -ceq $Value })[0] }
function Fact([object]$View, [string]$Id) { return (Find-Row @($View.facts) 'id' $Id) }
function Known([object]$View, [string]$Id) { return (Find-Row @($View.knowledge) 'factId' $Id) }
function Requirement([object]$Report, [string]$Id) { return (Find-Row @($Report.requirements) 'id' $Id) }
function Stage([object]$Report, [string]$Id) { return (Find-Row @($Report.stages) 'stage' $Id) }
function Record-Journey([string]$Actor, [string]$Directory, [object]$Journey) {
    $steps = @($Journey.attempts | ForEach-Object { [pscustomobject]@{requestId=$_.requestId;expectedRevision=$_.expectedRevision;status=$_.status;recordPath=(Join-Path $Directory $_.recordFile);receipt=$_.receipt} })
    $journeys.Add([pscustomobject]@{actor=$Actor;directory=$Directory;manifestPath=(Join-Path $Directory 'journey.json');status=$Journey.status;steps=$steps})
}
function Assert-Privacy([object]$View, [object]$Author, [string]$Label) {
    $text = $View | ConvertTo-Json -Depth 90 -Compress
    Assert-Small "$Label 不携带作者事实目录、法则或所有人物资料" (!$View.PSObject.Properties['facts'] -and !$View.PSObject.Properties['entities'] -and !$View.PSObject.Properties['laws'] -and !$View.PSObject.Properties['characters'])
    foreach ($nearby in @($View.nearby)) { Assert-Small "$Label 中 $($nearby.id) 只有公开资料" (!$nearby.PSObject.Properties['privateProfile']) }
    foreach ($person in @($Author.characters | Where-Object { $_.id -ne $View.actor })) {
        foreach ($field in @('goal', 'intent', 'concern')) {
            $privateText = [string]$person.privateProfile.$field
            if ($privateText) { Assert-Small "$Label 不泄漏 $($person.id) 的私有$field" (!$text.Contains($privateText)) }
        }
    }
    $secret = Fact $Author 'registry-copy-origin'
    if ($secret) { Assert-Small "$Label 不泄漏作者未确认给人物的编号差错原因" (!$text.Contains([string]$secret.value)) }
}
function Read-Preparation([string]$WorldFile, [string]$RequestFile, [string]$Name) {
    $before = Hash-File $WorldFile
    $requestHash = Hash-File $RequestFile
    $meta = Invoke-Small @('inspect', $WorldFile) ($Name + '-before') @($WorldFile)
    $report = Invoke-Small @('prepare', $WorldFile, $RequestFile) $Name @($WorldFile, $RequestFile)
    Assert-Small "$Name 绑定实际版本且分析不修改世界或输入" ($report.source.revision -eq $meta.revision -and (Hash-File $WorldFile) -ceq $before -and (Hash-File $RequestFile) -ceq $requestHash)
    Assert-Small "$Name 只检查声明条件，人物报告无世界证据" ($report.authorReport.checksScope -eq 'declared-requirements-only' -and $report.actorReport.checksScope -eq 'declared-requirements-only' -and !($report.actorReport | ConvertTo-Json -Depth 90 -Compress).Contains('"worldEvidence"'))
    return $report
}
function Assert-Trace([object[]]$Events, [object[]]$Plan, [string]$Actor, [object]$InitialAuthor, [string]$WorldFile, [string]$Label) {
    Assert-Small "$Label 正式行动数与声明的核实步骤一致" ($Events.Count -eq $Plan.Count)
    for ($index = 0; $index -lt $Plan.Count; $index++) {
        $entry = $Events[$index]; $expected = $Plan[$index]
        $before = Invoke-Small @('view-at', $WorldFile, $Actor, [string]($entry.revision - 1)) ("$Label-before-$index") @($WorldFile)
        $after = Invoke-Small @('view-at', $WorldFile, $Actor, [string]$entry.revision) ("$Label-after-$index") @($WorldFile)
        Assert-Small "$Label 第$($index + 1)步按原人物及版本提交 $($expected.module)" ($entry.proposal.actor -ceq $Actor -and $entry.submission.module -ceq $expected.module -and $entry.proposal.expectedRevision -eq $before.revision -and $after.revision -eq ($before.revision + 1))
        Assert-Small "$Label 第$($index + 1)步仅产生对应学习或移动效果" (@($entry.proposal.effects).Count -eq 1 -and $entry.proposal.effects[0].type -ceq $(if ($expected.module -eq 'travel') { 'person.move' } else { 'knowledge.learn' }))
        switch ($expected.module) {
            'ask' {
                $knowledge = Known $after $expected.factId
                Assert-Small "$Label 询问陈舟取得位置转述，未直接取得现场状态" ($entry.submission.input.target -ceq $expected.target -and $entry.submission.input.factId -ceq $expected.factId -and $knowledge.source -ceq $expected.target -and $knowledge.revision -eq $entry.revision -and $after.self.place -ceq $before.self.place -and !(Known $after 'cargo-handoff-status'))
            }
            'travel' {
                $from = [string]$before.self.place; $to = [string]$expected.to
                $worldEdge = @($InitialAuthor.routes | Where-Object { $_.from -ceq $from -and $_.to -ceq $to })
                $knownEdges = @($before.self.privateProfile.knownRoutes | Where-Object { $_.from -ceq $from -and $_.to -ceq $to })
                $hasEvidence = $false
                foreach ($edge in $knownEdges) { $proof = Known $before $edge.evidenceFactId; if ($proof -and $proof.value -is [bool] -and $proof.value) { $hasEvidence = $true } }
                Assert-Small "$Label $from 到 $to 有客观直达边和本人已知路线证据" ($worldEdge.Count -eq 1 -and $hasEvidence -and $entry.submission.input.to -ceq $to -and $after.self.place -ceq $to -and $entry.proposal.effects[0].expectedPlace -ceq $from)
            }
            'observe' {
                $knowledge = Known $after $expected.factId
                Assert-Small "$Label 现场观察产生本人的独立来源记录" ($entry.submission.input.factId -ceq $expected.factId -and $knowledge.source -ceq '@world' -and $knowledge.revision -eq $entry.revision -and $after.self.place -ceq 'warehouse')
            }
        }
        Assert-Privacy $before $InitialAuthor ("$Label 行动前版本$($before.revision)")
        Assert-Privacy $after $InitialAuthor ("$Label 行动后版本$($after.revision)")
    }
}

try {
    if (!$SkipBuild) {
        $buildOut = Join-Path $runRoot 'build.stdout.txt'; $buildErr = Join-Path $runRoot 'build.stderr.txt'
        $oldPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        & (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly > $buildOut 2> $buildErr
        $buildCode = $LASTEXITCODE; $ErrorActionPreference = $oldPreference
        $calls.Add([pscustomobject]@{name='build';command='run.ps1';arguments=@('-BuildOnly');inputPaths=@();stdout=$buildOut;stderr=$buildErr;exitCode=$buildCode;expectedFailure=$false})
        Assert-Small '本次 Java 21 编译成功' ($buildCode -eq 0)
    }
    Assert-Small '独立进程使用已存在的当前引擎入口' ((Test-Path -LiteralPath $javaTool) -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build\classes\creativeworld\WorldCli.class')))
    $exampleRoot = Join-Path $PSScriptRoot 'examples\small-world'
    $sourceNames = @('genesis.json', 'preparation.json', 'apprentice-goal.json', 'after-discovery.json', 'smith-goal.json')
    $source = @{}
    foreach ($name in $sourceNames) {
        $path = Join-Path $exampleRoot $name
        Assert-Small "输入 $name 存在" (Test-Path -LiteralPath $path)
        $sourceHashes.Add([pscustomobject]@{path=$path;before=Hash-File $path;after=$null})
        $source[$name] = Read-Json $path
    }
    $blueprintFile = Save-Input 'blueprint.json' $source['genesis.json'] (Join-Path $exampleRoot 'genesis.json')
    $preparationFile = Save-Input 'preparation.json' $source['preparation.json'] (Join-Path $exampleRoot 'preparation.json')
    $draftFile = Join-Path $runRoot 'draft.json'; $worldFile = Join-Path $runRoot 'world.json'
    $null = Invoke-Small @('construct', $blueprintFile, $draftFile) 'construct' @($blueprintFile)
    $draftHash = Hash-File $draftFile
    $draftPreparation = Invoke-Small @('prepare-draft', $draftFile, $preparationFile) 'prepare-draft' @($draftFile, $preparationFile)
    Assert-Small '草案分析不建立正式世界且保持草案不变' (!(Test-Path -LiteralPath $worldFile) -and $draftPreparation.source.kind -eq 'draft' -and $draftPreparation.source.revision -eq 0 -and (Hash-File $draftFile) -ceq $draftHash)
    $initial = Invoke-Small @('confirm', $draftFile, $worldFile) 'confirm' @($draftFile)
    $initialAuthor = Invoke-Small @('view', $worldFile, '@author') 'initial-author' @($worldFile)
    $initialApprentice = Invoke-Small @('view', $worldFile, 'apprentice') 'initial-apprentice' @($worldFile)
    $initialSmith = Invoke-Small @('view', $worldFile, 'smith') 'initial-smith' @($worldFile)
    $initialReader = Invoke-Small @('view', $worldFile, '@reader') 'initial-reader' @($worldFile)
    $initialHistory = Invoke-Small @('history', $worldFile) 'initial-history' @($worldFile)
    Assert-Small '确认得到6地点6人物与零事件起点' ($initial.counts.places -eq 6 -and $initial.counts.characters -eq 6 -and $initial.revision -eq 0 -and @($initialHistory.events).Count -eq 0)
    Assert-Small '双方目标有各自起点，师傅已知位置而学徒尚不知道' ($initialApprentice.self.place -ceq 'wharf' -and $initialSmith.self.place -ceq 'forge' -and !(Known $initialApprentice 'cargo-location') -and (Known $initialSmith 'cargo-location').value -ceq 'warehouse' -and !(Known $initialSmith 'cargo-handoff-status') -and !(Known $initialApprentice 'cargo-handoff-status'))
    Assert-Privacy $initialApprentice $initialAuthor '初始学徒视图'
    Assert-Privacy $initialSmith $initialAuthor '初始师傅视图'
    Assert-Small '读者不自动取得作者或人物的全部资料' (!$initialReader.PSObject.Properties['characters'] -and !$initialReader.PSObject.Properties['self'] -and @($initialReader.readerKnowledge).Count -eq 0)
    $factions = @($initialAuthor.entities | Where-Object kind -eq 'faction'); $groups = @($initialAuthor.entities | Where-Object kind -eq 'group')
    $resources = @($initialAuthor.entities | Where-Object kind -eq 'resource')
    Assert-Small '两势力两群体三资源十法则有实际资料目录' ($factions.Count -eq 2 -and $groups.Count -eq 2 -and $resources.Count -eq 3 -and @($initialAuthor.laws).Count -eq 10 -and @($initialAuthor.relationships).Count -ge 1)
    $allIds = @($initialAuthor.places.id) + @($initialAuthor.characters.id) + @($initialAuthor.entities.id)
    foreach ($entity in @($initialAuthor.entities)) {
        Assert-Small "实体 $($entity.id) 引用已确认的地点人物或实体" (@($entity.refs).Count -ge 1 -and @($entity.refs | Where-Object { $_ -notin $allIds }).Count -eq 0)
        if ($entity.kind -eq 'group') {
            $population = Fact $initialAuthor $entity.attributes.populationFactId
            $namedMembers = @($entity.refs | Where-Object { $_ -in @($initialAuthor.characters.id) })
            Assert-Small "群体 $($entity.id) 人数有事实依据且容纳已展开成员" ($population -and $population.mode -eq 'state' -and $population.value -ge $namedMembers.Count)
        }
        if ($entity.kind -eq 'resource' -and $entity.attributes.quantityFactId) { Assert-Small "资源 $($entity.id) 数量与法则指向同一事实" ((Fact $initialAuthor $entity.attributes.quantityFactId) -and @($initialAuthor.laws | Where-Object factId -eq $entity.attributes.quantityFactId).Count -ge 1) }
    }
    foreach ($law in @($initialAuthor.laws)) { Assert-Small "法则 $($law.id) 绑定存在的事实" ($null -ne (Fact $initialAuthor $law.factId)) }
    foreach ($relation in @($initialAuthor.relationships)) { Assert-Small "关系 $($relation.from) 到 $($relation.to) 有人物端点及作者给定依据" ($relation.from -in @($initialAuthor.characters.id) -and $relation.to -in @($initialAuthor.characters.id) -and ![string]::IsNullOrWhiteSpace([string]$relation.basis)) }
    $initialPreparation = Read-Preparation $worldFile $preparationFile 'initial-preparation'
    Assert-Small '起点定位缺本人知识，取货仍未放行且没有数量依据' ((Requirement $initialPreparation.actorReport 'location-message').status -eq 'unknown' -and (Requirement $initialPreparation.authorReport 'world-permission').status -eq 'conditionUnmet' -and (Requirement $initialPreparation.actorReport 'actor-permission').status -eq 'unknown' -and (Requirement $initialPreparation.authorReport 'transport-quantity').status -eq 'worldMissing' -and (Stage $initialPreparation.authorReport 'pickup').status -eq 'needsInput' -and (Stage $initialPreparation.authorReport 'transport').status -eq 'needsInput')

    $apprenticePlan = @(@{module='ask';target='boatman';factId='cargo-location'}, @{module='travel';to='warehouse'}, @{module='observe';factId='cargo-handoff-status'})
    $smithPlan = @(@{module='travel';to='market'}, @{module='travel';to='warehouse'}, @{module='observe';factId='cargo-handoff-status'})
    foreach ($step in $apprenticePlan) { $eventPlan.Add([pscustomobject]@{actor='apprentice';module=$step.module;to=$step.to;factId=$step.factId}) }
    $eventPlan.Add([pscustomobject]@{actor='@author';module=$null;kind='author-supplied-after-discovery'})
    foreach ($step in $smithPlan) { $eventPlan.Add([pscustomobject]@{actor='smith';module=$step.module;to=$step.to;factId=$step.factId}) }
    $apprenticeRequest = Copy-Json $source['apprentice-goal.json']
    $apprenticeStart = Invoke-Small @('inspect', $worldFile) 'apprentice-start' @($worldFile)
    $apprenticeRequest.expectedRevision = $apprenticeStart.revision
    Assert-Small '学徒目标声明查证，没有手写候选序列' ($apprenticeRequest.actor -ceq 'apprentice' -and $apprenticeRequest.scenario.goal.factId -ceq 'cargo-handoff-status' -and !$apprenticeRequest.scenario.PSObject.Properties['candidates'])
    Assert-Small '本例学徒输入不携带尚未获知的登记副本编号疑点' (!(($apprenticeRequest.scenario.remainingGaps -join '')).Contains('副本编号') -and !(($apprenticeRequest.scenario.remainingGaps -join '')).Contains('H-16'))
    $apprenticeFile = Save-Input 'apprentice-goal.json' $apprenticeRequest (Join-Path $exampleRoot 'apprentice-goal.json')
    $apprenticeDirectory = Join-Path $runRoot 'apprentice-journey'
    $apprenticeJourney = Invoke-Small @('pursue', $worldFile, $apprenticeFile, $apprenticeDirectory, '8') 'apprentice-pursue' @($worldFile, $apprenticeFile)
    Record-Journey 'apprentice' $apprenticeDirectory $apprenticeJourney
    $apprenticeHistory = Invoke-Small @('history', $worldFile) 'apprentice-history' @($worldFile)
    $apprenticeEvents = @($apprenticeHistory.events | Where-Object { $_.proposal.requestId.StartsWith($apprenticeRequest.requestId + '.step-') })
    Assert-Small '学徒目标正常满足且正式版本来自实际三个行动' ($apprenticeJourney.status -eq 'satisfied' -and $apprenticeHistory.revision -eq ($apprenticeStart.revision + $apprenticePlan.Count))
    Assert-Trace $apprenticeEvents $apprenticePlan 'apprentice' $initialAuthor $worldFile 'apprentice-trace'
    $afterApprentice = Invoke-Small @('view', $worldFile, 'apprentice') 'after-apprentice' @($worldFile)
    $smithBeforeContinuation = Invoke-Small @('view', $worldFile, 'smith') 'smith-before-continuation' @($worldFile)
    $firstObservation = Known $afterApprentice 'cargo-handoff-status'
    Assert-Small '学徒取得现场状态不会自动给远处师傅共享认识' ($firstObservation.source -ceq '@world' -and $firstObservation.value -ceq 'held-awaiting-registration' -and !(Known $smithBeforeContinuation 'cargo-handoff-status'))
    $repeatHash = Hash-File $worldFile
    $again = Invoke-Small @('pursue', $worldFile, $apprenticeFile, $apprenticeDirectory, '8') 'apprentice-repeat' @($worldFile, $apprenticeFile)
    Assert-Small '复用学徒完成过程不新增正式事件' ($again.status -eq 'satisfied' -and (Hash-File $worldFile) -ceq $repeatHash)

    $continuation = Copy-Json $source['after-discovery.json']
    $continuationStart = Invoke-Small @('inspect', $worldFile) 'author-continuation-start' @($worldFile)
    Assert-Small '作者后续从实际历史版本接续' ($continuationStart.revision -eq $apprenticeHistory.revision -and $continuation.actor -ceq '@author')
    Assert-Small '登记回应提交前学徒确实已在货栈现场核实本版本状态' ($afterApprentice.self.place -ceq 'warehouse' -and $firstObservation.source -ceq '@world' -and $firstObservation.revision -eq $continuationStart.revision -and $firstObservation.value -ceq (Fact $initialAuthor 'cargo-handoff-status').value)
    $continuation.expectedRevision = $continuationStart.revision
    $continuationFile = Save-Input 'after-discovery.json' $continuation (Join-Path $exampleRoot 'after-discovery.json')
    $authorReceipt = Invoke-Small @('apply', $worldFile, $continuationFile) 'author-supplied-after-discovery' @($worldFile, $continuationFile)
    $continuedHistory = Invoke-Small @('history', $worldFile) 'after-author-history' @($worldFile)
    $continuedAuthor = Invoke-Small @('view', $worldFile, '@author') 'after-author-view' @($worldFile)
    $continuedApprentice = Invoke-Small @('view', $worldFile, 'apprentice') 'after-author-apprentice' @($worldFile)
    $authorEvent = @($continuedHistory.events | Where-Object { $_.proposal.requestId -ceq $continuation.requestId })[0]
    Assert-Small '登记及关系回应确为作者提供的单次事务，未伪装自主心理变化' ($authorReceipt.revision -eq ($continuationStart.revision + 1) -and $authorEvent.proposal.actor -ceq '@author' -and !$authorEvent.PSObject.Properties['submission'] -and (Same-Json $authorEvent.proposal.effects $continuation.effects) -and @($continuation.effects | Where-Object type -eq 'relationship.change').Count -ge 1)
    Assert-Small '作者登记变更保持货物位置数量并且仍未给予放行' ((Fact $continuedAuthor 'cargo-registration-state').value -ceq 'registered' -and (Fact $continuedAuthor 'cargo-handoff-status').value -ceq 'registered-awaiting-permission' -and (Same-Json (Fact $continuedAuthor 'cargo-location').value (Fact $initialAuthor 'cargo-location').value) -and (Same-Json (Fact $continuedAuthor 'iron-quantity').value (Fact $initialAuthor 'iron-quantity').value) -and (Fact $continuedAuthor 'cargo-pickup-permission').value -eq $false -and !(Fact $continuedAuthor 'cargo-transport-quantity'))
    $keeperView = Invoke-Small @('view', $worldFile, 'keeper') 'after-author-keeper' @($worldFile)
    $clerkView = Invoke-Small @('view', $worldFile, 'clerk') 'after-author-clerk' @($worldFile)
    $response = @($continuedAuthor.relationships | Where-Object { $_.from -ceq 'keeper' -and $_.to -ceq 'apprentice' })[0]
    $declaredResponse = @($continuation.effects | Where-Object type -eq 'relationship.change')[0]
    Assert-Small '保管员现场获得两项新认识，登记员仍保留其旧快照' ((Known $keeperView 'cargo-registration-state').source -ceq '@world' -and (Known $keeperView 'cargo-registration-state').value -ceq 'registered' -and (Known $keeperView 'cargo-handoff-status').revision -eq $authorReceipt.revision -and (Known $clerkView 'cargo-registration-state').value -ceq 'pending' -and (Known $clerkView 'cargo-handoff-status').value -ceq 'held-awaiting-registration')
    Assert-Small '关系回应保存作者输入的具体依据与共同年份' ($response.value -ceq $declaredResponse.value -and $response.basis -ceq $declaredResponse.basis -and $response.occurredAtYear -eq $continuedAuthor.worldTime.year -and $response.revision -eq $authorReceipt.revision)
    Assert-Small '新登记不偷偷修改学徒之前的现场认识' (Same-Json (Known $continuedApprentice 'cargo-handoff-status') $firstObservation)
    $null = Read-Preparation $worldFile $preparationFile 'after-author-preparation'

    $smithRequest = Copy-Json $source['smith-goal.json']
    $smithStart = Invoke-Small @('inspect', $worldFile) 'smith-start' @($worldFile)
    $smithRequest.expectedRevision = $smithStart.revision
    Assert-Small '师傅核实目标从作者提交后的真实版本开始' ($smithStart.revision -eq $authorReceipt.revision -and $smithRequest.actor -ceq 'smith' -and $smithRequest.scenario.goal.factId -ceq 'cargo-handoff-status' -and !$smithRequest.scenario.PSObject.Properties['candidates'])
    $smithFile = Save-Input 'smith-goal.json' $smithRequest (Join-Path $exampleRoot 'smith-goal.json')
    $smithDirectory = Join-Path $runRoot 'smith-journey'
    $smithJourney = Invoke-Small @('pursue', $worldFile, $smithFile, $smithDirectory, '8') 'smith-pursue' @($worldFile, $smithFile)
    Record-Journey 'smith' $smithDirectory $smithJourney
    $finalHistory = Invoke-Small @('history', $worldFile) 'final-history' @($worldFile)
    $smithEvents = @($finalHistory.events | Where-Object { $_.proposal.requestId.StartsWith($smithRequest.requestId + '.step-') })
    Assert-Small '师傅目标正常满足且只接续自己的三步' ($smithJourney.status -eq 'satisfied' -and $finalHistory.revision -eq ($smithStart.revision + $smithPlan.Count))
    Assert-Trace $smithEvents $smithPlan 'smith' $initialAuthor $worldFile 'smith-trace'
    $finalAuthor = Invoke-Small @('view', $worldFile, '@author') 'reopened-author' @($worldFile)
    $finalApprentice = Invoke-Small @('view', $worldFile, 'apprentice') 'reopened-apprentice' @($worldFile)
    $finalSmith = Invoke-Small @('view', $worldFile, 'smith') 'reopened-smith' @($worldFile)
    $finalInspect = Invoke-Small @('inspect', $worldFile) 'reopened-inspect' @($worldFile)
    $actualEventCount = @($finalHistory.events).Count
    Assert-Small '正式历史为两组实际行动与作者提供事件之和，不把停止记录当事件' ($actualEventCount -eq $eventPlan.Count -and $finalInspect.revision -eq ($initial.revision + $eventPlan.Count) -and ($finalHistory.events.proposal.actor -join ',') -ceq ($eventPlan.actor -join ','))
    Assert-Small '重新开进程仍保留两人的不同观察结果和获取版本' ((Known $finalSmith 'cargo-handoff-status').source -ceq '@world' -and (Known $finalSmith 'cargo-handoff-status').value -ceq 'registered-awaiting-permission' -and (Known $finalSmith 'cargo-handoff-status').revision -eq $finalHistory.revision -and (Same-Json (Known $finalApprentice 'cargo-handoff-status') $firstObservation))
    Assert-Small '地点身份及固定背景事实保持，只有两个人按路线来到货栈' ((Same-Json $finalAuthor.places $initialAuthor.places) -and $finalInspect.counts.characters -eq $initial.counts.characters -and $finalApprentice.self.place -ceq 'warehouse' -and $finalSmith.self.place -ceq 'warehouse')
    Assert-Small '共同时间仍为年100，各笔版本前进不伪装成过了七年' ($finalInspect.worldTime.year -eq $initial.worldTime.year -and @($finalHistory.events | Where-Object { $_.worldTime.year -ne $initial.worldTime.year }).Count -eq 0)
    foreach ($person in @($initialAuthor.characters)) {
        $current = Find-Row @($finalAuthor.characters) 'id' $person.id
        Assert-Small "人物 $($person.id) 姓名和私密资料未被行动改写" ($current.name -ceq $person.name -and (Same-Json $current.privateProfile $person.privateProfile))
        if ($person.id -notin @('apprentice', 'smith')) { Assert-Small "人物 $($person.id) 没有被未声明的移动带走" ($current.place -ceq $person.place) }
    }
    foreach ($fact in @($initialAuthor.facts | Where-Object mode -eq 'fixed')) { Assert-Small "固定事实 $($fact.id) 未被改写" (Same-Json (Fact $finalAuthor $fact.id).value $fact.value) }
    Assert-Privacy $finalApprentice $finalAuthor '重新打开的学徒视图'
    Assert-Privacy $finalSmith $finalAuthor '重新打开的师傅视图'
    $beforeRepeat = Hash-File $worldFile
    $null = Invoke-Small @('pursue', $worldFile, $apprenticeFile, $apprenticeDirectory, '8') 'final-repeat-apprentice' @($worldFile, $apprenticeFile)
    $null = Invoke-Small @('pursue', $worldFile, $smithFile, $smithDirectory, '8') 'final-repeat-smith' @($worldFile, $smithFile)
    Assert-Small '两条完成过程在新进程恢复不新增或重写事件' ((Hash-File $worldFile) -ceq $beforeRepeat)
    $invalidQuantity = [pscustomobject]@{schemaVersion=1;worldId=$finalInspect.worldId;requestId='small-world-reject-negative-resource';expectedRevision=$finalInspect.revision;actor='@author';reason='验收资源数量非负声明的实际拒绝，不改变正式世界';effects=@([pscustomobject]@{type='fact.change';factId='iron-quantity';expectedValue=(Fact $finalAuthor 'iron-quantity').value;value=-1})}
    $invalidQuantityFile = Save-Input 'reject-negative-resource.json' $invalidQuantity
    $null = Invoke-Small @('apply', $worldFile, $invalidQuantityFile) 'reject-negative-resource' @($worldFile, $invalidQuantityFile) $true
    $invalidRegistration = [pscustomobject]@{schemaVersion=1;worldId=$finalInspect.worldId;requestId='small-world-reject-registration-domain';expectedRevision=$finalInspect.revision;actor='@author';reason='验收已声明登记枚举的实际拒绝，不补未存在状态';effects=@([pscustomobject]@{type='fact.change';factId='cargo-registration-state';expectedValue=(Fact $finalAuthor 'cargo-registration-state').value;value='not-in-declared-registration-domain'})}
    $invalidRegistrationFile = Save-Input 'reject-registration-domain.json' $invalidRegistration
    $null = Invoke-Small @('apply', $worldFile, $invalidRegistrationFile) 'reject-registration-domain' @($worldFile, $invalidRegistrationFile) $true
    $afterRefusals = Invoke-Small @('history', $worldFile) 'history-after-law-refusals' @($worldFile)
    Assert-Small '资源非负与登记枚举确实约束非法后果，拒绝后全部世界字节和历史保持' ((Hash-File $worldFile) -ceq $beforeRepeat -and (Same-Json $afterRefusals $finalHistory))
    $finalPreparation = Read-Preparation $worldFile $preparationFile 'reopened-preparation'
    Assert-Small '最终声明检查仍指出未获得放行与运输数量依据，不宣称送达' ((Requirement $finalPreparation.authorReport 'world-permission').status -eq 'conditionUnmet' -and (Requirement $finalPreparation.actorReport 'actor-permission').status -eq 'unknown' -and (Requirement $finalPreparation.authorReport 'transport-quantity').status -eq 'worldMissing' -and !(Requirement $finalPreparation.actorReport 'transport-quantity') -and (Stage $finalPreparation.authorReport 'pickup').status -eq 'needsInput' -and (Stage $finalPreparation.authorReport 'transport').status -eq 'needsInput')
    Assert-Small '后续世界变化不会把学徒之前的现场认识重新标成当前核实' ((Requirement $finalPreparation.authorReport 'onsite-handoff').status -eq 'knowledgeConflict' -and (Requirement $finalPreparation.actorReport 'onsite-handoff').status -eq 'needsRecheck' -and (Requirement $finalPreparation.actorReport 'onsite-handoff').actorEvidence.value -ceq $firstObservation.value)
    $repeatedPreparation = Read-Preparation $worldFile $preparationFile 'repeated-preparation'
    Assert-Small '重复只读准备结果稳定且不产生正式事件' (Same-Json $finalPreparation $repeatedPreparation)

    foreach ($sourceHash in $sourceHashes) { $sourceHash.after = Hash-File $sourceHash.path; Assert-Small "源输入 $([System.IO.Path]::GetFileName($sourceHash.path)) 字节保持不变" ($sourceHash.before -ceq $sourceHash.after) }
} catch {
    $failure = $_.Exception.Message
    if (@($results | Where-Object { !$_.passed }).Count -eq 0) { $results.Add([pscustomobject]@{name='独立进程闭环执行';passed=$false;error=$failure}) }
} finally {
    foreach ($sourceHash in $sourceHashes) { if (Test-Path -LiteralPath $sourceHash.path) { $sourceHash.after = Hash-File $sourceHash.path } }
    $report = [pscustomobject]@{schemaVersion=1;kind='small-world-cli-check';checks=$results.Count;passed=@($results | Where-Object passed).Count;failed=@($results | Where-Object { !$_.passed }).Count;runDirectory=$runRoot;results=$results;calls=$calls;inputs=$inputs;sourceInputs=$sourceHashes;journeys=$journeys;expectedEventPlan=$eventPlan;expectedCommittedEvents=$eventPlan.Count;actualCommittedEvents=$actualEventCount;buildSkipped=[bool]$SkipBuild;authorSuppliedContinuation=$true;autonomousPsychologyEvaluated=$false;actualModelCalled=$false;proseQualityEvaluated=$false;cargoDelivered=$false;checksScope='declared-requirements-and-actual-world-events';error=$failure}
    [System.IO.File]::WriteAllText((Join-Path $runRoot 'small-world-cli-check.json'), ($report | ConvertTo-Json -Depth 90), $utf8)
    [pscustomobject]@{passed=$report.passed;failed=$report.failed;actualCommittedEvents=$actualEventCount;runDirectory=$runRoot;reportFile=(Join-Path $runRoot 'small-world-cli-check.json');error=$failure} | ConvertTo-Json -Depth 5
}
if ($failure) { exit 1 }
exit 0
