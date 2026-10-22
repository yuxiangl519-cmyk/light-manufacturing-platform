# 版本锁定与官方兼容依据

核实日期2026-10-17。后端已按此基线实际安装、构建并验证，pom和Wrapper已生成；dependency:tree核对托管版本一致。Wrapper发行包SHA-256已固定。第5阶段已创建前端package.json/package-lock.json，npm ci、类型检查、5项单元测试和构建均已实测；版本未擅自升级。

## 后端与运行时

| 项目 | 锁定版本 | 策略 |
| --- | --- | --- |
| JDK | Eclipse Temurin 17.0.20.1+1 | 本机已执行 java/javac 验证；编译 release=17，UTF-8 |
| Maven | 3.9.12 | Wrapper distribution 固定该版本并记录校验和 |
| Maven Wrapper | 3.3.4 | 下一阶段从 Apache 官方工具生成，不拷参考项目 |
| Spring Boot parent/plugin | 3.5.16 | 官方 3.5 分支，Maven Central POM 已成功读取 |
| Spring MVC/Framework | 6.2.19 | Boot BOM 管理 |
| Spring Security | 6.5.11 | Boot BOM 管理 |
| Spring Session | 3.5.7 | spring-session-data-redis，Boot BOM |
| Spring Data BOM | 2025.0.13 | JPA/Redis 由其管理，避免单独覆盖 |
| Hibernate ORM | 6.6.53.Final | Boot BOM |
| Jakarta Validation API / Hibernate Validator | 3.0.2 / 8.0.3.Final | Boot BOM，使用 jakarta 包 |
| MySQL Server | 8.4.7 | MySQL 8 LTS 基线；本机现有 9.6 不充当验收环境 |
| Connector/J | 9.7.0 | Boot BOM；驱动版本与服务端主版本无须相同 |
| Flyway core + flyway-mysql | 11.7.2 | 两者均由 Boot BOM 管理，MySQL 模块不可遗漏 |
| Redis Server | 7.4.2 | 目标基线；本机服务端版本因认证未核实 |
| Lettuce | 6.6.0.RELEASE | Boot BOM，使用 Spring Data Redis 默认客户端 |
| Springdoc webmvc-ui | 2.8.17 | 独立显式固定；与 Boot 3.5 兼容矩阵一致 |
| Logback | 1.5.34 | Boot 默认 logging starter/BOM，不额外日志框架 |
| Tomcat | 10.1.55 | Boot 内嵌容器/BOM |
| JUnit Jupiter | 5.12.2 | starter-test/BOM；另加 spring-security-test 同 Security 版本 |
| compiler / surefire / failsafe plugin | 3.14.1 / 3.5.6 / 3.5.6 | 固定，集成测试由 integration profile 控制 |

Boot 固定父 POM + BOM 会锁定传递依赖；不得将表中每项再任意覆盖。未来 `dependency:tree` 保存实际解析报告，发现差异先修订本表并说明原因。

官方依据：

- [Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)：Java 17 起、Maven 3.6.3 起。
- [Boot 依赖坐标](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)及[固定 3.5.16 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom)：本表托管版本从固定 POM 读取。
- [Springdoc v2 兼容矩阵 §13.80](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)：Boot 3.5.x 对应 2.8.x；[2.8.17 Maven 制品](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/2.8.17/)。
- [Connector/J 9.7 发行说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html)：支持 MySQL Server 8.0 及以上；[MySQL 8.4.7 发行说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-7.html)。
- [Flyway MySQL 官方说明](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)：Java 使用需要独立 flyway-mysql 模块；最终 MySQL8.4.7/Flyway11.7.2 组合仍须集成测试验证，不把文档兼容等同运行验收。
- [Redis 7.4.2 官方发行](https://github.com/redis/redis/releases/tag/7.4.2)、[Maven Wrapper](https://maven.apache.org/tools/wrapper/)。

## 前端

| 包/工具 | 固定版本 |
| --- | --- |
| Node.js / npm | 24.13.0 / 11.6.2 |
| vue | 3.5.22 |
| typescript | 5.9.3 |
| vite | 7.1.12 |
| @vitejs/plugin-vue | 6.0.1 |
| vue-router | 4.5.1 |
| pinia | 3.0.3 |
| axios | 1.12.2 |
| element-plus | 2.11.5 |
| echarts | 6.0.0 |
| vue-tsc | 3.1.1 |
| @types/node | 24.10.0 |
| vitest | 3.2.4 |
| @playwright/test | 1.56.1 |

以上版本均读取 npm 官方注册表相应版本的元数据确认存在及 engines/peerDependencies。依赖字段写精确版本，不使用 ^/~/*/latest；传递版本由提交的 package-lock.json 固定，安装使用 npm ci。官方工具自身 peer 范围是兼容声明，不是本项目动态版本配置。

兼容检查：Vite7.1.12 和 plugin-vue6.0.1 要求 Node ^20.19.0 或 >=22.12.0，Node24.13.0 满足；plugin-vue 接受 Vite7 与 Vue>=3.2.25；Pinia3.0.3 接受 Vue^3.5.11 和 TS>=4.4.4；Router/Element Plus 接受 Vue^3.2.0；vue-tsc 接受 TS>=5。所选 Vue3.5.22/TS5.9.3 满足。其余插件的可选 peer 不使用则不引入。

依据：[Vite7 官方运行时要求](https://vite.dev/blog/announcing-vite7)，以及 npm 官方版本元数据，例如 [Vite7.1.12](https://registry.npmjs.org/vite/7.1.12)、[plugin-vue6.0.1](https://registry.npmjs.org/@vitejs%2fplugin-vue/6.0.1)、[Pinia3.0.3](https://registry.npmjs.org/pinia/3.0.3)、[Element Plus2.11.5](https://registry.npmjs.org/element-plus/2.11.5)。

这是兼容性基线，不是安全扫描结果；P1 安装后执行 npm audit 和依赖检查。若发现所选版本存在影响本项目的漏洞，先更新具体版本与此文档再锁定，不盲目 npm audit fix --force。第5阶段已执行npm ci、TypeScript检查、单元测试和生产构建；后端JPA/Flyway与MySQL启动已验证。浏览器与依赖审计的实际结果见stage5-verification.md。

## AI增量（2026-10-19）

未升级现有Java/Vue依赖。Python实测3.13.5；FastAPI0.115.12、Uvicorn0.34.2、HTTPX0.28.1、LangGraph1.0.10、pytest8.3.5、pytest-asyncio0.26.0，直接版本见agent/requirements.txt，完整传递锁定见agent/requirements.lock.txt。pip check无冲突、21测试通过。依据及API链接见agent/README.md。未进行新增Python依赖完整漏洞审计，不把兼容性检查称安全验收。
