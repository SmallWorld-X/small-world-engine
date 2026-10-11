# 同一个世界里的十年：共同时间小闭环

> 发布阅读说明：下文的本地历史报告位于忽略的 build 或 runs 目录，不随源码仓库上传。它们记录开发时的验证范围；运行对应脚本会生成你自己的新目录和报告。本轮复核见[开发版验证记录](RELEASE-CHECKS.md)。

## 本轮实际范围

这一轮将共同时间接入结构化世界起点，用三个人验证年龄、旧资料和关系历史的分工。第一版只使用整数纪年，不包含月份、日历换算、活动调度、十年生活推导或小说正文。

时间推进是整座世界的变化。尚未展开的陈川也属于十年后的世界；他的出生年可用于计算年龄，未提供的生活和经历继续保留未知。不能让他因未登场而停在十年前，也不能为了补齐时间自动编造履历。

三个新文件组成一条连续案例：

- [time-town.json](examples/time-town.json)：独立新蓝图。纪年100年，周宁和许澄出生于84年，陈川出生于78年；初始年龄分别为16、16、22。
- [time-advance.json](examples/time-advance.json)：第一笔提案以版本0为预期起点，只把整个世界从100年推进到110年；成功回执为版本1。
- [time-relations.json](examples/time-relations.json)：第二笔提案以版本1为预期起点，在110年由作者明确确认双向同学关系，并保存变化依据；成功回执为版本2。

这些输入不是旧存档补丁。请构造新起点、确认到新的世界文件，不给已有无时间的存档直接补时钟。

## 连续案例应该怎样理解

起点时，周宁在许家做杂务；在他看来，许澄是主人家的晚辈。许澄认识周宁，并把他视为家中仆人。两人的私密记忆和顾虑各自保存，陈川则在另一地点，生活细节尚未展开。

第一次提交只推进共同时间。到110年，三个人的年龄依出生年变为26、26、32；城市位置、通路、已确认背景及旧关系保留。时间不会自行把周宁变成学员，也不会替陈川选择职业或心情。

第二次提交另行确认：作者设定周宁在110年结束许家旧职，与许澄共同入学，因此两人的当前角色关系成为同学。这是明确的作者输入，不是模型自主推导，也不证明引擎已经演出了离职、入学或完整十年生活。先前的仆人关系仍可从起点和事件历史回查；“同学”也不自动变成“朋友”或“信任”。

资料仍要看记录时间。出生年是年龄的依据；100年的职业、外貌和私密资料是当年快照，110年应标明为过去的资料。第二笔只改关系，没有更新职业资料或产生人物移动，不能把旧职业文字与新关系混读成已核实的当前生活。

## 第一版协议

### 蓝图与起点

共同时间放在蓝图 `sections.world.worldTime`，构造后进入 `seed.worldTime`：

```json
{"unit": "year", "year": 100}
```

目前只接受 `unit: "year"` 和非负整数 `year`。构造贡献只有一份共同时钟；两个构造器即使给出相同的年份也应拒绝，避免多个权威来源。

人物出生年是人物顶层的可选字段：

```json
{"birthYear": 84}
```

`people` 和 `extras` 都保留该字段；龙套的群体关联继续进入人物公开资料及群体引用，不能因添加出生年而丢失。出生年必须为非负整数，且不能晚于当前年；没有共同时间时不能提供出生年。

有时间的世界禁止在公开或私有资料顶层另写 `age` 或 `birthYear`，年龄只从共同时间与人物出生年派生。没有出生年则年龄为 `null`，不通过外貌或名字猜年龄。旧无时间世界继续按原协议读取，不自动升级为有时间世界。

### 推进时间与改变关系

示例把 `time.advance` 放在这笔提案的第一条效果，校验原年份后向前推进：

```json
{"type": "time.advance", "expectedYear": 100, "toYear": 110}
```

第一份示例不附带其他效果，明确分开“时间过去”和“关系改变”。年份与 `revision` 不互相代替：从100年到110年是一次提交；同在110年仍可以有后续提交。

有时间世界里的 `relationship.change` 必须提供非空 `basis`。内核在初始关系及变化关系上附加 `occurredAtYear`；正式历史中的事件外层记录 `worldTime`。实际回执继续保持原来的五个字段：`schemaVersion`、`worldId`、`requestId`、`revision`、`status`。

人物投影保留 `publicProfileAsOfYear`、`privateProfileAsOfYear`，并区分 `current` / `historical`，用于说明资料快照来自何时。它们不表示职业、情绪已经自动更新，也不冻结人物年龄。具体投影结构以本轮内核实现及实测输出为准。

## 命令行复现

本轮已通过独立命令行进程验证下面这条构造、确认、推进、查询历史与重复提交的链路。每次手动复现创建不同目录，以保护已有草案与正式世界。

```powershell
Set-Location F:\myplan\xuying-studio\creative-world-engine
$timeRun = Join-Path 'runs' ('time-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $timeRun | Out-Null

.\run.ps1 construct examples\time-town.json "$timeRun\draft.json"
.\run.ps1 confirm "$timeRun\draft.json" "$timeRun\world.json"
.\run.ps1 inspect "$timeRun\world.json"
.\run.ps1 view "$timeRun\world.json" '@author'
.\run.ps1 view "$timeRun\world.json" 'zhou-ning'

.\run.ps1 apply "$timeRun\world.json" examples\time-advance.json
.\run.ps1 inspect "$timeRun\world.json"
.\run.ps1 view "$timeRun\world.json" '@author'
.\run.ps1 view "$timeRun\world.json" 'chen-chuan'

.\run.ps1 apply "$timeRun\world.json" examples\time-relations.json
.\run.ps1 history "$timeRun\world.json"
.\run.ps1 view "$timeRun\world.json" 'zhou-ning'
.\run.ps1 view "$timeRun\world.json" '@reader'
.\run.ps1 view-at "$timeRun\world.json" 'zhou-ning' 0
.\run.ps1 view-at "$timeRun\world.json" 'zhou-ning' 1

.\run.ps1 apply "$timeRun\world.json" examples\time-advance.json
.\run.ps1 apply "$timeRun\world.json" examples\time-relations.json
.\run.ps1 inspect "$timeRun\world.json"
```

最后两次复用完整请求，应恢复原回执，不能再推进十年或追加关系变化。若要改输入，使用新的请求标识与实际当前版本；不要静默替换旧请求。

`view-at` 只查询历史版本：0版应显示100年的起点，1版应显示110年且关系尚未改变。它不把当前世界退回过去；对照这些投影与2版的当前关系，可以分别查看时间变化和关系变化。

## 实际结果与证据

2026-10-11，在本轮最后一次生产代码修改后，时间专项 **22/22 组**与独立进程时间命令行 **18/18 项**通过。原内核、构造、演绎恢复、目标推进的四套 Java 检查及四套命令行回归也通过；各套报告单列于 [验收记录](ACCEPTANCE.md)，不把重叠检查数量当成能力数量。

| 人物 | 起点100年 | 推进至110年 | 关系事件后仍为110年 |
|---|---|---|---|
| 周宁 | 16岁 | 26岁 | 26岁 |
| 许澄 | 16岁 | 26岁 | 26岁 |
| 远处尚未展开的陈川 | 22岁 | 32岁 | 32岁 |

| 核查点 | 实际成立的结果 |
|---|---|
| 共同时间 | 构造草案与正式起点保留同一份年份；第二份时钟贡献即使相同也被拒绝。 |
| 固定世界与旧资料 | 推进十年不改姓名、地点、道路或固定背景；职业与私密资料仍标明100年的快照，状态为历史资料。 |
| 关系变化与历史 | 另一次作者事件在110年确认双向同学关系；历史0版仍有旧关系，1版显示已推进时间但未改变关系。 |
| 信息隔离 | 本人投影不自动包含远处角色，附近人物也不泄漏私密资料、私密快照年份或出生年。 |
| 恢复与重复请求 | 新进程重读恢复年龄与年份；再次提交两份相同输入返回原回执，正式历史仍为两笔，世界文件字节不变。 |
| 事务失败 | 推进时间后尝试改变固定地理，整笔拒绝，时间也不部分保存；倒退年份同样拒绝。 |
| 认识的新旧 | 旧消息转述保留原证据年份；十年前的现场观察不能满足十年后的新核实目标。 |
| 同年变化反例 | 同年观察后货箱已移动，新核实目标须重新观察，不能因证据年份相同而直接满足。 |
| 未知与兼容 | 未提供出生年时年龄为未知；未来出生年拒绝；旧无时钟世界不自动增加年代和年龄字段。 |

可直接检查的本轮产物：

- 时间专项原始报告（本地历史文件：`build/time-check.json`）
- 命令行原始报告（本地历史文件：`runs/time-cli-1709257902704969af58ab3cecbdc0db/time-cli-check.json`）
- 起点作者视图（本地历史文件：`runs/time-cli-1709257902704969af58ab3cecbdc0db/before-author.json`）
- 推进十年后的作者视图（本地历史文件：`runs/time-cli-1709257902704969af58ab3cecbdc0db/after-time-author.json`）
- 当前人物与同学关系（本地历史文件：`runs/time-cli-1709257902704969af58ab3cecbdc0db/current-person.json`）
- 历史起点人物与旧关系（本地历史文件：`runs/time-cli-1709257902704969af58ab3cecbdc0db/historical-start.json`）
- 两笔正式事件（本地历史文件：`runs/time-cli-1709257902704969af58ab3cecbdc0db/history.json`）

交叉审查最初发现同年旧观察可能直接满足新目标，修正后补入专项反例。当前年度目标同时核对观察年份和正式版本，只读取本人认识，不偷读作者真值。这个检查较保守：任一后续正式提交都会要求新核实目标重新取得现场证据，包括与货箱无关的提交；尚未实现按具体事实追踪变化的细粒度规则。

## 未确认项与下一步

此闭环只证明共同年度时间、年龄派生、资料时间依据、关系历史与提交恢复的相关合同。它未调用模型，也不验证自动心理变化、完整十年经历、细分日历与生活调度、按需展开城市或小说阅读质量。关系变为同学仍是明确作者输入，依据文本不自动证明离职入学过程已经演绎成立。

评审时先比较同一人物的历史与当前视图，再检查两笔事件，确认“自然长大”和“有依据地改变关系”分开。后续能力须复用同一时钟与提交机制，不能为另一个组件另建世界时间。
