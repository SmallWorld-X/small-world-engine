# Small World Engine · 小世界引擎

**一个可运行、可扩展的结构化小世界内核。当前为 0.1.1 开发版候选。**

我们希望让创作者逐步建立一个有地点、人物、组织、资源、关系和共同历史的小世界，再让人物在自己的认识范围内行动。程序守住世界的一致性，组件提出候选，创作者决定方向和关键设定。

当前输入是 JSON，输出是结构化结果和事件历史。项目以中文字段说明、资料和文档为主，欢迎中文或英文交流。AI 辅助构造与小说正文生成是后续方向，当前没有模型调用。

**English overview.** Small World Engine is an experimental Java 21 kernel for building and running structured fictional worlds. It separates shared world facts, each character's knowledge, and reader-visible information, and records validated changes in a persistent event history. The current prototype uses JSON inputs and rule-based components; it does not call language models or generate novel prose. Most examples and documentation are currently in Chinese. Contributions and discussion in either Chinese or English are welcome.

## 能做什么

| 能力 | 当前实现 | 使用边界 |
| --- | --- | --- |
| 建立世界 | 组合明确提供的地点、人物、组织、群体、资源、关系、初始情感与法则；草案经重建核对后确认 | 构造成功不表示资料已经足够，创作想法不会自动解释成完整世界 |
| 保持共同规则 | 校验身份、固定事实、声明的通路、数值范围、变化前提和世界版本 | 法则和前提须在资料中明确声明；程序不证明文字理由真实合理 |
| 区分认识范围 | 分开生成作者、人物和读者视图；询问与分享保留来源人的认识及时间依据 | 听说、个人推断和现场观察不自动成为同一种证据 |
| 推进有限目标 | 根据人物本人认识提出询问、沿已知路线移动与现场观察；支持预算暂停与恢复 | 只支持已有结构化目标类型，尚非通用规划器或心理模型 |
| 产生消息回应 | 按本人消息、作者声明的策略与优先级选择可用规则，提出关联状态变化 | 规则与目标由作者提供，程序筛选和内核裁决不等于自主理解人性 |
| 衔接多个人物 | 实际获知唤起声明的活动，连续执行目标、申请、办理回应和现场核实；支持累计预算与恢复 | 活动和策略由作者提供，最多32次尝试；无待运行活动不表示业务全部完成 |
| 管理共同时间 | 推进世界纪年，按有依据的出生年推导人物年龄，保留关系与认识的历史时间 | 未出场人物同样受时间约束；职业、心情和经历不会被自动补写 |
| 补充与改变身份 | 受控补定尚未记录的身份资料、揭示信息；有前提的任命与撤职影响受限制行动 | 不自动生成社会权力体系，也不追溯改写已确认历史 |
| 保存与继续 | 追加事件、重新读取时重放校验；重复请求去重，组件版本固定并检查恢复条件 | 当前是本地文件和可信 Java 组件；未验证断电耐久与远程付费调用恢复 |

## 怎样运行

```mermaid
flowchart LR
    A[作者提供世界资料] --> B[构造草案]
    B --> C[校验并确认起点]
    C --> D[人物视图与目标]
    D --> E[组件提出候选行动]
    E --> F[共同内核校验整笔变化]
    F --> G[提交事件与新状态]
    G --> D
    G --> H[作者、人物与读者视图]
```

身份、地点和事实使用稳定标识。固定设定与已发生事件保持；状态变化通过新事件记录。人物只按自己获得的消息行动，世界已存在的事实不会自动进入所有人物的认识。

创作者的干预也使用正式入口，可以设置起点、补充未确定资料和提出有前提的变化。已经确认的背景和历史仍受同一内核约束。

## 快速开始

### 1. 准备环境

当前运行入口验证范围是 **Windows + PowerShell 7 + JDK 21**。需要完整 JDK，只有 Java 运行环境不够。Git 用于克隆仓库；也可以从仓库页面下载源码压缩包。Maven 3.6.3 或更新版本用于下载 JSON 依赖；本项目没有 Maven 构建文件，也不需要数据库、Spring 或模型密钥。

在 **PowerShell 7** 中执行。先将下面的 JDK 路径替换成你的实际安装目录：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:JAVA_HOME\bin\javac.exe" -version
mvn.cmd -version

git clone https://github.com/SmallWorld-X/small-world-engine.git
Set-Location .\small-world-engine
```

如果已经下载了源码，直接进入源码根目录。后面的命令都从这个目录运行。

### 2. 下载三个固定依赖

启动器从当前用户的 Maven 缓存读取 Jackson **2.19.2**。下面的命令明确使用该位置，即使 Maven 已另配缓存目录也能与启动器一致。首次下载需要联网；依赖齐全后，引擎本身不调用远程服务。

```powershell
$dependencyCache = Join-Path $env:USERPROFILE '.m2\repository'
foreach ($library in 'jackson-databind', 'jackson-core', 'jackson-annotations') {
    mvn.cmd org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get `
        "-Dartifact=com.fasterxml.jackson.core:${library}:2.19.2" `
        '-Dtransitive=false' "-Dmaven.repo.local=$dependencyCache"
    if ($LASTEXITCODE -ne 0) { throw "依赖下载失败：$library" }
}
```

三个库保留各自的第三方许可证，见[第三方依赖说明](THIRD-PARTY-NOTICES.md)。启动脚本不会自动安装 JDK、Maven 或下载依赖。

### 3. 运行完整案例

```powershell
.\verify-identity-cli.ps1
```

这条命令编译源码，为每次运行创建新存档，并通过独立 Java 进程执行两段经历：

- **河港镇，七个事件**：作者任命有前提的核验者；保管员移动并分享本人消息；核验者按策略纠正副本并回应；两人返回货栈后，保管员完成登记。
- **社区工坊，两个事件**：修缮者分享已有记录；管理者复用同一个回应组件，更新设备可用性并分享结果。

程序还检查非法提交是否整体拒绝、人物的信息差是否保留、重新打开是否一致，以及同一存档重复提交相同请求时是否增加事件。再次运行整个验证脚本会创建新的世界和目录。

成功时输出应显示 `failed: 0`；从输出的 `runDirectory` 可以找到本次存档和详细报告。失败返回非零退出状态。

**应当保留的结果边界**：河港镇完成的是登记，货物仍在货栈，领取许可仍为否；工坊设备可用，但公众使用许可仍为否。示例明确安排部分移动、任命和消息输入，其他回应由程序按配置选择。没有自动补全此前维修、完整人生经历或小说正文。

也可以运行基础小世界案例：

```powershell
.\verify-small-world-cli.ps1
```

基础案例包含六处地点、六个人物、两个组织和两个群体。学徒与师傅各自核实同一批货物，分享同一份世界历史；中间登记回应由作者输入。完整说明见[河港镇基础小世界](SMALL-WORLD-DEMO.md)和[身份与行动后果](IDENTITY-DEMO.md)。

### 4. 运行连续演绎

```powershell
.\verify-continuation-cli.ps1
```

新增的[连续演绎案例](CONTINUATION-DEMO.md)在河港镇和社区工坊复用同一机制。只声明起点、目标、策略与消息关注项，程序连续产生询问、申请、办理回应、移动和观察：各五笔正式事件，再保存一次目标满足记录。完整入口包含编译、暂停、独立进程恢复与重复执行检查，成功为32项通过、0失败。

登记或设备可用状态不表示领取、运输、实际维修或公众使用许可已经完成。无待运行活动时仍分别保留各活动缺口，尚未接入模型和复杂心理。

### 5. 手动建立自己的起点

下面使用已提供的构造入门蓝图。每次创建独立输出目录，保留已有存档：

```powershell
$trialDirectory = Join-Path '.\runs' ('my-world-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $trialDirectory | Out-Null
$draftFile = Join-Path $trialDirectory 'draft.json'
$worldFile = Join-Path $trialDirectory 'world.json'

.\run.ps1 construct .\examples\genesis-river-town.json $draftFile
.\run.ps1 confirm $draftFile $worldFile
.\run.ps1 view $worldFile '@author'
.\run.ps1 view $worldFile apprentice
.\run.ps1 step $worldFile ask .\examples\genesis-ask.json
.\run.ps1 step $worldFile travel .\examples\genesis-travel.json
.\run.ps1 history $worldFile
```

这里的询问和移动是明确提供的行动输入；构造起点仍保留待补资料，不代表已经领取货物。输出文件不能覆盖已有世界。输入格式和效果约定见[协议说明](PROTOCOL.md)。

### 常见启动问题

- **找不到 JDK**：检查当前 PowerShell 会话的 `JAVA_HOME` 是否指向 JDK 21 根目录，其中应有 `bin\javac.exe`。
- **缺少 JSON 依赖**：重新执行依赖下载步骤，确认使用当前用户的默认 Maven 缓存位置。
- **脚本无法执行**：使用 PowerShell 7。若仅当前会话受到执行策略限制，在确认源码可信后可执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned`，再重试；组织管理策略仍以本机策略为准。
- **输出文件已经存在**：使用新的输出文件或运行目录。引擎不会用初始化替你重置旧存档。
- **资料缺失、版本冲突或行动不成立**：检查结构化返回与实际历史。补齐依据或从当前版本继续，不把失败结果当作已经完成。

## 验证与证据

发布准备的复核范围、实际结果和未确认项汇总在[开发版验证记录](RELEASE-CHECKS.md)。完整报告由命令在本机生成，编译输出和运行存档不纳入发布仓库。

专项检查一次选择一项：

```powershell
.\run.ps1 -Check
.\run.ps1 -GenesisCheck
.\run.ps1 -ExperienceCheck
.\run.ps1 -GoalCheck
.\run.ps1 -TimeCheck
.\run.ps1 -ModuleVersionCheck
.\run.ps1 -ConstructorVersionCheck
.\run.ps1 -PreparationCheck
.\run.ps1 -ReactionCheck
.\run.ps1 -SkeletonCheck
.\run.ps1 -IdentityCheck
.\run.ps1 -ContinuationCheck
```

每项检查输出 JSON 和退出状态；失败返回非零。独立命令行案例另有验证脚本。历史设计材料中标出的 `build/`、`runs/` 路径对应开发时的本地原始报告，公开仓库不包含这些文件，也不以旧报告替代本次运行。请从上述入口自行生成当前结果。

## 怎样扩展

- **世界资料**：先增加蓝图中的内容、声明法则和变化前提，沿用稳定标识与统一时间线。
- **构造组件**：可注册 Java 构造器及前后校验，依赖和版本应明确；旧草案不能被新实现悄悄重新解释。
- **人物组件**：通过准备、候选、选择、提案和反馈阶段使用人物视图。新的实现必须固定标识与版本，并让提交结果回到共同历史。
- **行为和后果**：组件提出变化，内核重新检查身份、旧值、位置、法则和版本，整笔成立才提交。扩展组件是可信本地代码，目前没有任意插件加载器或安全沙箱。

新增能力应先通过一个可复现的小案例，再使用不同人物和场景检查能否复用，同时核对旧历史。具体姓名和剧情条件放在世界资料里，通用规则保持集中。

## 当前局限与后续方向

- 世界输入仍由创作者明确提供；尚未接入 AI 辅助补全、自然语言目标解释或正文生成。
- 人物选择目前依据结构化认识与声明策略；长期欲望、情感变化、社会权力和财富分配尚未形成完整机制。
- 已有按实际获知衔接的有限多人物活动；尚未实现完整场景生活调度、长期事件传播和所有题材的行为约束。
- 当前保存方式每次读取重放历史、写入整个世界文件；未验证大规模世界的性能或断电后的耐久恢复。
- 跨机器、Linux、macOS、付费远程调用和恶意扩展隔离尚未验证。
- 当前没有发布 JAR、安装器或图形界面。开发版用于理解机制、运行案例和协作迭代。

## 设计与研究入口

| 想了解的问题 | 文档 |
| --- | --- |
| 内核、构造与人物组件怎样分工 | [架构说明](ARCHITECTURE.md) |
| 扩展时怎样保持共同规则和旧历史 | [内核维护约定](KERNEL-MAINTENANCE.md) |
| 时间、空间、认识与关系有哪些共同约束 | [持续世界合同](WORLD-KERNEL-CONTRACTS.md) |
| 怎样借鉴编译器、事务和组件合同 | [架构借鉴](ARCHITECTURE-REFERENCES.md) |
| 人物互动、个人记忆与世界执行有哪些研究参考 | [设计参考](DESIGN-REFERENCES.md) |
| 准备、提案、事件和反馈使用什么格式 | [协议说明](PROTOCOL.md) |

引用研究用于理解问题和设计机制，不表示已经移植或运行了那些框架。

## 参与建设

欢迎提交可复现问题、文档改进、世界资料和小范围组件改进。问题报告请包含运行环境、最小输入、预期结果、实际结果及运行命令；提交前删除私人资料和密钥。

改动请说明解决了哪个缺口、影响哪些共同规则，并附上相关检查结果。涉及认识、时间、身份、持久化或版本的变化，还应核对恢复后的历史。不要把特定人物姓名或场景条件写进通用内核。

文档以中文为主，英文问题和贡献同样欢迎。首次参与可以从跑通案例、修正文档或增加一个能复用的小世界资料开始。

## 许可

本项目采用 [PolyForm Noncommercial License 1.0.0](LICENSE)，完整条款来自 [PolyForm 官方](https://polyformproject.org/licenses/noncommercial/1.0.0)，未另加或删改。归属通知见 [NOTICE](NOTICE)。

该标准许可允许条款规定的非商业使用、学习研究、修改和分享，也包括条款列明的教育、公益及政府等机构用途。**超出该标准许可允许范围的用途，需要另行取得相关权利人的授权**；有商业应用预期时，请先核对完整条款并联系维护者确认。分发时须向接收者提供许可条款或其网址，以及规定的通知。

项目属于**源码公开、用途受限**的软件，不按允许不受限制商用的开源许可发布。上述说明只是便于阅读的摘要，具体范围以 LICENSE 完整文本为准；第三方依赖继续使用各自的许可。

**License:** [PolyForm Noncommercial 1.0.0](LICENSE). This is source-available software with restricted commercial use. The standard license permits its specified noncommercial uses, modifications and distribution, including the organizations listed in the license. Uses not covered by it require separate authorization from the relevant rights holders. Preserve the license and required notices when distributing the software. Third-party dependencies retain their own licenses.
