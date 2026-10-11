[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$runRoot = Join-Path $PSScriptRoot ('runs\identity-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$checks = [System.Collections.Generic.List[object]]::new()
$calls = [System.Collections.Generic.List[object]]::new()
$sourceHashes = @{}
$failure = $null
$runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
$javaTool = $runtime.javaTool
$classPath = $runtime.classPath
function Read-Json([string]$Path) { Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
function Check([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed})
    if (!$Passed) { throw "检查失败：$Name" }
}
function Fact([object]$View, [string]$Id) { @($View.facts | Where-Object id -EQ $Id)[0] }
function Known([object]$View, [string]$Id) { @($View.knowledge | Where-Object factId -EQ $Id)[0] }
function Save-Input([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 90), $utf8)
    return $path
}
function Invoke-Engine([string]$Name, [string[]]$Arguments, [switch]$Reject) {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $javaTool
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $utf8
    $start.StandardErrorEncoding = $utf8
    foreach ($argument in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $classPath, 'creativeworld.WorldCli') + $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $out = $stdout.GetAwaiter().GetResult(); $err = $stderr.GetAwaiter().GetResult()
    $outPath = Join-Path $runRoot "$Name.stdout.json"; $errPath = Join-Path $runRoot "$Name.stderr.txt"
    [System.IO.File]::WriteAllText($outPath, $out, $utf8); [System.IO.File]::WriteAllText($errPath, $err, $utf8)
    $calls.Add([pscustomobject]@{name=$Name;arguments=$Arguments;exitCode=$process.ExitCode;expectedRejection=[bool]$Reject;stdout=$outPath;stderr=$errPath})
    if ($Reject) {
        Check "$Name 明确拒绝" ($process.ExitCode -ne 0 -and ($err | ConvertFrom-Json).error.message.Length -gt 0)
        return
    }
    if ($process.ExitCode -ne 0) { throw "命令失败：$Name；诊断已保存" }
    return ($out | ConvertFrom-Json)
}
try {
    if (!$SkipBuild) {
        $buildOutput = & (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
        [System.IO.File]::WriteAllText((Join-Path $runRoot 'compile.stdout.txt'), ($buildOutput -join [Environment]::NewLine), $utf8)
        Check '独立Java编译完成' ($LASTEXITCODE -eq 0)
    }
    $sourceRoot = Join-Path $PSScriptRoot 'examples\identity'
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Filter '*.json' -File) { $sourceHashes[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
    $draft = Join-Path $runRoot 'draft.json'; $world = Join-Path $runRoot 'world.json'
    $null = Invoke-Engine 'construct' @('construct', (Join-Path $sourceRoot 'genesis.json'), $draft)
    $null = Invoke-Engine 'confirm' @('confirm', $draft, $world)
    $initial = Invoke-Engine 'initial-author' @('view', $world, '@author')
    $hash = (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash
    $unauthorized = Save-Input 'before-role.json' ([ordered]@{schemaVersion=1;worldId=$initial.worldId;requestId='before-role';expectedRevision=0;actor='clerk';reason='只有说明文字不能代替任命';effects=@(@{type='fact.change';factId='registry-copy-code';expectedValue='H-16';value='H-17'})})
    Invoke-Engine 'before-role-rejected' @('apply', $world, $unauthorized) -Reject
    Check '未任命操作拒绝且世界保持' ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
    $appoint = Join-Path $sourceRoot 'appoint.json'
    $receipt = Invoke-Engine 'appoint' @('apply', $world, $appoint)
    $afterRole = Invoke-Engine 'after-role-author' @('view', $world, '@author')
    Check '角色由正式事件赋予同一人物' ((Fact $afterRole 'clerk-verification-role').value -eq 'registrar' -and @($afterRole.characters | Where-Object id -EQ 'clerk')[0].name -eq @($initial.characters | Where-Object id -EQ 'clerk')[0].name)
    $null = Invoke-Engine 'keeper-travel' @('step', $world, 'travel', (Join-Path $sourceRoot 'keeper-travel.json'))
    $null = Invoke-Engine 'keeper-share-slip' @('apply', $world, (Join-Path $sourceRoot 'keeper-share-slip.json'))
    $clerkInput = Join-Path $sourceRoot 'clerk-response.json'
    $clerkReceipt = Invoke-Engine 'clerk-response' @('step', $world, 'message-state-response', $clerkInput, '1')
    $clerk = Invoke-Engine 'after-correction-clerk' @('view', $world, 'clerk')
    $keeper = Invoke-Engine 'after-correction-keeper' @('view', $world, 'keeper')
    Check '许瑶实际纠正副本并现场复核' ((Known $clerk 'registry-copy-code').value -eq 'H-17' -and (Known $clerk 'registry-copy-code').source -eq '@world')
    Check '沈砚收到的是许瑶转述的新结果' ((Known $keeper 'registry-copy-code').value -eq 'H-17' -and (Known $keeper 'registry-copy-code').source -eq 'clerk' -and (Known $keeper 'registry-copy-code').revision -eq $clerkReceipt.revision)
    $hash = (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash
    $early = Read-Json (Join-Path $sourceRoot 'keeper-response.json')
    $early.expectedRevision = 4
    $early.requestId = 'keeper-cannot-observe-handoff-in-office'
    $earlyPath = Save-Input 'early-keeper-response.json' $early
    Invoke-Engine 'wrong-observation-place-rejected' @('step', $world, 'message-state-response', $earlyPath, '1') -Reject
    Check '无法核查交接地点时关联状态全部回滚' ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
    $null = Invoke-Engine 'clerk-return' @('step', $world, 'travel', (Join-Path $sourceRoot 'clerk-return-warehouse.json'))
    $null = Invoke-Engine 'keeper-return' @('step', $world, 'travel', (Join-Path $sourceRoot 'keeper-return-warehouse.json'))
    $keeperInput = Join-Path $sourceRoot 'keeper-response.json'
    $null = Invoke-Engine 'keeper-response' @('step', $world, 'message-state-response', $keeperInput, '1')
    $author = Invoke-Engine 'reopened-author' @('view', $world, '@author')
    $clerk = Invoke-Engine 'reopened-clerk' @('view', $world, 'clerk')
    $reader = Invoke-Engine 'reopened-reader' @('view', $world, '@reader')
    $old = Invoke-Engine 'old-author' @('view-at', $world, '@author', '0')
    $history = Invoke-Engine 'reopened-history' @('history', $world)
    Check '登记与交接阶段原子同步' ((Fact $author 'cargo-registration-state').value -eq 'registered' -and (Fact $author 'cargo-handoff-status').value -eq 'registered-awaiting-permission')
    Check '纠正结果促成另一人物实际决定' (@($history.events).Count -eq 7 -and $history.events[3].proposal.actor -eq 'clerk' -and $history.events[6].proposal.actor -eq 'keeper' -and $history.events[6].submission.module -eq 'message-state-response')
    Check '许瑶获知登记完成且来源为沈砚' ((Known $clerk 'cargo-registration-state').value -eq 'registered' -and (Known $clerk 'cargo-registration-state').source -eq 'keeper')
    Check '旧身份和旧副本保留原历史' ((Fact $old 'clerk-verification-role').value -eq 'unassigned' -and (Fact $old 'registry-copy-code').value -eq 'H-16')
    Check '登记不冒充许可或运送' ((Fact $author 'cargo-pickup-permission').value -eq $false -and (Fact $author 'cargo-location').value -eq 'warehouse' -and (Fact $author 'iron-quantity').value -eq 40)
    Check '人物投影不泄漏作者全部世界' ($null -eq $clerk.facts -and $null -eq $clerk.laws -and @($reader.readerKnowledge).Count -eq 0)
    $hash = (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash
    $again = Invoke-Engine 'repeat-appoint' @('apply', $world, $appoint)
    $againClerk = Invoke-Engine 'repeat-clerk' @('step', $world, 'message-state-response', $clerkInput, '1')
    $null = Invoke-Engine 'repeat-keeper' @('step', $world, 'message-state-response', $keeperInput, '1')
    Check '重复请求返回原回执' ($again.revision -eq $receipt.revision -and $againClerk.revision -eq $clerkReceipt.revision)
    Check '重开重复不改世界字节' ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
    Check '原地点和人物数量保持' (@($author.places).Count -eq @($initial.places).Count -and @($author.characters).Count -eq @($initial.characters).Count)
    $workshopWorld = Join-Path $runRoot 'workshop-world.json'
    $null = Invoke-Engine 'workshop-init' @('init', (Join-Path $sourceRoot 'workshop-seed.json'), $workshopWorld)
    $workshopInput = Join-Path $sourceRoot 'workshop-response.json'
    $earlyWorkshop = Read-Json $workshopInput
    $earlyWorkshop.expectedRevision = 0
    $earlyWorkshop.requestId = 'workshop-no-message'
    $earlyWorkshopPath = Save-Input 'workshop-before-message.json' $earlyWorkshop
    $hash = (Get-FileHash -LiteralPath $workshopWorld -Algorithm SHA256).Hash
    Invoke-Engine 'workshop-no-message-rejected' @('step', $workshopWorld, 'message-state-response', $earlyWorkshopPath, '1') -Reject
    Check '跨业务缺消息不改世界' ($hash -eq (Get-FileHash -LiteralPath $workshopWorld -Algorithm SHA256).Hash)
    $workshopMessage = Save-Input 'workshop-message.json' ([ordered]@{schemaVersion=1;worldId='community-repair-workshop';requestId='volunteer-shares-repair-record';expectedRevision=0;actor='volunteer';reason='分享本人已持有的修缮记录，交由管理者决定设备开放';effects=@(@{type='knowledge.share';from='volunteer';to='steward';factId='repair-record'})})
    $null = Invoke-Engine 'workshop-share' @('apply', $workshopWorld, $workshopMessage)
    $null = Invoke-Engine 'workshop-response' @('step', $workshopWorld, 'message-state-response', $workshopInput, '1')
    $workshopAuthor = Invoke-Engine 'workshop-reopened-author' @('view', $workshopWorld, '@author')
    $workshopVolunteer = Invoke-Engine 'workshop-reopened-volunteer' @('view', $workshopWorld, 'volunteer')
    $workshopHistory = Invoke-Engine 'workshop-reopened-history' @('history', $workshopWorld)
    Check '同一组件跨业务形成设备开放后果' ((Fact $workshopAuthor 'equipment-available').value -eq $true -and (Fact $workshopAuthor 'inspection-state').value -eq 'passed' -and @($workshopHistory.events).Count -eq 2 -and $workshopHistory.events[1].submission.module -eq 'message-state-response')
    Check '工坊其他许可和资源保持' ((Fact $workshopAuthor 'public-use-permission').value -eq $false -and (Fact $workshopAuthor 'spare-planks').value -eq 4)
    Check '工坊只传播声明分享的认识' ((Known $workshopVolunteer 'equipment-available').value -eq $true -and (Known $workshopVolunteer 'equipment-available').source -eq 'steward' -and $null -eq (Known $workshopVolunteer 'inspection-state'))
    $hash = (Get-FileHash -LiteralPath $workshopWorld -Algorithm SHA256).Hash
    $null = Invoke-Engine 'workshop-repeat-response' @('step', $workshopWorld, 'message-state-response', $workshopInput, '1')
    Check '工坊重开重复不改世界字节' ($hash -eq (Get-FileHash -LiteralPath $workshopWorld -Algorithm SHA256).Hash)
    foreach ($source in $sourceHashes.Keys) { Check "源输入保持：$([System.IO.Path]::GetFileName($source))" ($sourceHashes[$source] -eq (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) }
} catch {
    $failure = $_.Exception.Message
    if (@($checks | Where-Object { !$_.passed }).Count -eq 0) {
        $checks.Add([pscustomobject]@{name='运行闭环未完成';passed=$false})
    }
}
$report = [ordered]@{name='身份与后果独立命令行验收';passed=@($checks | Where-Object passed).Count;failed=@($checks | Where-Object { !$_.passed }).Count;runDirectory=$runRoot;checks=@($checks);calls=@($calls);failure=$failure;evidenceLimit='结构化作者前提与本人明确策略；非语义真实性、复杂心理、自动调度、送达或模型生成证明'}
$reportPath = Join-Path $runRoot 'identity-cli-check.json'
[System.IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 90), $utf8)
Write-Output ($report | ConvertTo-Json -Depth 90)
if ($failure) { exit 1 }
exit 0
