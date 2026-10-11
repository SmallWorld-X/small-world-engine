# 第三方依赖说明

当前 Java 源码直接依赖下列 JSON 库。启动器按固定版本从当前用户的 Maven 缓存读取它们；本源码仓库不随附这些 JAR。

| 库 | 版本 | 上游许可证 | 官方来源 |
| --- | --- | --- | --- |
| Jackson Databind | 2.19.2 | Apache License 2.0 | [FasterXML/jackson-databind](https://github.com/FasterXML/jackson-databind) |
| Jackson Core | 2.19.2 | Apache License 2.0 | [FasterXML/jackson-core](https://github.com/FasterXML/jackson-core) |
| Jackson Annotations | 2.19.2 | Apache License 2.0 | [FasterXML/jackson-annotations](https://github.com/FasterXML/jackson-annotations) |

本次发布准备已检查本机这三个版本 JAR 内的 `META-INF/LICENSE` 与 `META-INF/NOTICE`，各库继续遵守其原许可证及通知；项目自己的许可不替代这些条款。若以后发布包含依赖的二进制包，需一并保留对应版本的许可证和通知。

README 中的依赖下载方法使用 Apache Maven Dependency Plugin 3.8.1。它属于构建准备工具，不打包进引擎；使用规则见 [Apache Maven 官方文档](https://maven.apache.org/plugins-archives/maven-dependency-plugin-3.8.1/plugin-info.html)。

[设计参考](DESIGN-REFERENCES.md)及[架构借鉴](ARCHITECTURE-REFERENCES.md)记录论文和工程思想来源。当前引用与设计借鉴不代表把 Concordia、Generative Agents 或其他外部框架源码集成进本项目。
