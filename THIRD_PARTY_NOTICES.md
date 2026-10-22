# 第三方来源说明

- Maven Wrapper：由Apache Maven Wrapper Plugin 3.3.4生成，distribution固定Apache Maven3.9.12；保留mvnw/mvnw.cmd中的Apache License2.0许可头和来源链接。来源：[Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/)，许可：[Apache License2.0](https://www.apache.org/licenses/LICENSE-2.0)。
- Maven管理的Spring、Hibernate、Flyway、MySQL驱动、Springdoc等依赖均通过各自官方制品元数据解析，具体解析见本地dependency-tree报告；没有将依赖源码冒充为本项目原创。
- MySQL8.4.7与Redis7.4.2仅安装到被Git忽略/项目专用运行目录，保留官方包内许可证。Redis7.4.2官方源码的许可按其随包内容适用，本仓库不重新分发这些运行包。
- SPMS-Server仅作为只读概念参考，没有复制其业务代码、凭据或配置；分析及未来复用许可要求见docs/reference-analysis.md。

本文件不是为本项目整体指定开源许可证，项目自身许可证由作者另定。

- Asia/Shanghai时区数据：仅重分发tzdata 2025.2（IANA 2025b）wheel中的Shanghai TZif；文件、来源、wheel与文件SHA256及随包Apache许可证见agent/app/timezone_data/。由Python标准库读取，没有安装/升级tzdata或其他依赖。来源：[Python tzdata 2025.2](https://pypi.org/project/tzdata/2025.2/)。
