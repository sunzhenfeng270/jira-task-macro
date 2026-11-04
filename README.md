# Jira Task Macro

Confluence 插件：在页面中直接创建 Jira 任务，并以表格形式展示 Jira 任务列表。适用于会议纪要、行动项跟踪等场景。

- 插件 Key：`com.tangzhi.confluence.jira-task-macro`
- 包名：`com.tangzhi.confluence.jiratask`
- 支持版本：Confluence 8.9.4（JDK 17）

## 功能

### 1. 新建 Jira 任务宏（`jira-task-creator`）

在页面内嵌一个表单，无需跳转 Jira 即可创建任务：

- 选择项目、任务类型（按项目动态加载）
- 填写标题、描述、截止日期、优先级
- 搜索并选择经办人（按姓名或邮箱）
- 创建成功后自动生成本页已创建任务列表
- 自动为任务写入 `confluence-page-<pageId>` 标签，用于反查本页关联任务

### 2. Jira 任务表格宏（`jira-issue-table`）

服务端渲染任务表格，兼容 Scroll Word Exporter 导出：

- 支持完整 JQL，或用项目、状态、经办人、标签等简化参数自动拼装 JQL
- 支持自定义显示列、最大条数（超过 100 条自动分页请求）
- 任务 Key 自动链接到 Jira 浏览页（使用应用链接的显示地址，反向代理/内网部署也可点击）
- 日期列显示 Jira 返回值所在时区的日期，与 Jira 界面一致，不受 Confluence 服务器时区影响
- 未登录（匿名）访客不会看到 Jira 数据，表格位置显示登录提示
- 可选使用 Scroll Title / Scroll Table Layout 包裹表格（未安装 Scroll 插件时自动回退为普通表格，且保留标题）

## 权限与安全

- `jira-task` 的表单宏通过 REST 接口访问 Jira，所有接口都要求 Confluence 用户已登录（匿名用户返回 401）。
- `/projects`、`/priorities`、`/users` 只校验登录，不针对具体页面；`/issues`（创建与查询）额外校验 `pageId` 必须是数字且当前用户拥有该页面的查看权限，否则统一返回 404（不区分"无权限"与"不存在"，避免枚举页面 ID）。
- 任务表格宏在页面渲染时直接以应用链接身份查询 Jira：匿名访问页面时只显示登录提示；已登录用户看到的数据范围取决于**应用链接账号**在 Jira 中的可见范围，请按需收紧该账号的 Jira 权限。
- 创建任务前会通过 Jira `mypermissions` 校验应用链接身份在目标项目上的 `CREATE_ISSUES` 权限；接口不可用时放行，由 Jira 在创建时做最终校验。
- `pageId` 仅接受数字，避免拼接进 JQL 时产生注入。
- `/users` 只返回 `name`、`accountId`、`displayName`，不返回邮箱，避免登录用户枚举 Jira 用户联系方式。
- Jira 返回的错误原因（`errorMessages` / `errors`）会原样透出到界面；非 Jira 调用的异常只返回通用提示，并写入 Confluence 日志。

## 国际化

界面文案通过插件 i18n 资源加载：

- `i18n/jira-task.properties`：默认英文
- `i18n/jira-task_zh_CN.properties`：简体中文

新增语言只需增加对应的 `jira-task_<locale>.properties`。

## 环境要求

- Confluence 8.9.4
- JDK 17
- Maven 3.8+
- Confluence 与 Jira 之间已配置**应用链接**（Application Link），建议使用 OAuth 或 Trusted App 认证

## 构建与安装

```bash
mvn clean package
```

构建过程会自动执行 `atlassian-spring-scanner-maven-plugin`，生成 `META-INF/plugin-components` 组件与依赖导入声明，保证 `@Named` / `@ComponentImport` 在运行时生效。

产物位于 `target/jira-task-macro-1.0.01.261104.jar`。在 Confluence「管理 → 管理应用（UPM）」中上传该 JAR 即可安装/升级。

> 说明：`javax.transaction:jta:1.0.1B` 只存在于已下线的 java.sun.com 仓库，POM 中已通过 `dependencyManagement` 统一为中央仓库可用的 `1.1`，无需额外配置仓库。

本地调试（需 Atlassian SDK / AMPS）：

```bash
mvn confluence:debug
```

## 使用方式

在页面编辑器中插入宏：

- 插入「新建 Jira 任务」宏即可使用任务创建表单
- 插入「Jira 任务表格」宏并按需填写参数

### 新建任务宏参数

| 参数 | 说明 |
| --- | --- |
| `projectKey` | 默认 Jira 项目 Key，例如 `ABC` |
| `issueTypeName` | 默认任务类型名称，例如 `任务` |
| `summaryPrefix` | 标题前缀，同时作为页面标题的默认拼接前缀，例如 `【会议行动项】` |
| `labels` | 创建任务时附加的标签，英文逗号分隔 |

### 任务表格宏参数

| 参数 | 说明 |
| --- | --- |
| `title` | 表格标题，留空显示「Jira 任务」 |
| `jql` | 完整 JQL；填写后忽略下面的简化过滤参数 |
| `projectKey` | 项目 Key |
| `statuses` | 状态列表，英文逗号分隔 |
| `assignee` | 经办人用户名；`@me` 表示当前登录用户 |
| `labels` | 标签列表，英文逗号分隔 |
| `columns` | 显示列，英文逗号分隔，留空使用默认列 |
| `maxResults` | 最多显示条数，默认 50，上限 200 |

可用列：`key`、`type`、`summary`、`status`、`assignee`、`reporter`、`priority`、`resolution`、`duedate`、`created`、`updated`、`labels`、`fixversions`、`components`，也支持 `customfield_<id>`，或直接写 Jira 字段名（如 `enddate`、`End date`，不区分大小写并忽略空格、下划线、连字符）。字段名会通过 Jira `/rest/api/2/field` 自动解析为字段 ID 并缓存 10 分钟。

默认列：`key,type,summary,status,assignee,priority,duedate`

## REST 接口

前端表单通过以下接口访问 Jira（路径前缀 `/rest/jira-task/1.0`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/projects?query=` | 查询 Jira 项目 |
| GET | `/issuetypes?projectKey=` | 查询项目下可创建的任务类型（需有创建权限） |
| GET | `/priorities` | 查询优先级 |
| GET | `/users?projectKey=&query=` | 搜索可指派用户（不返回邮箱） |
| POST | `/issues` | 创建任务（`pageId` 必填） |
| GET | `/issues?pageId=` | 查询本页已创建的任务，返回 `{issues, total, shown}` |
| GET | `/diagnose?projectKey=` | 离线自检（仅站点管理员），返回应用链接、Jira 身份、版本、字段数与创建权限 |

所有 Jira 调用均通过 Confluence 的应用链接完成，无需在插件中单独配置 Jira 账号密码。创建/查询本页任务的接口需要登录并对目标页面有查看权限。

## 项目结构

```
src/main/java/com/tangzhi/confluence/jiratask/
├── JiraClient.java                # 基于应用链接的 Jira REST 客户端
├── JiraTaskMacro.java             # 新建任务宏
├── JiraIssueTableMacro.java       # 任务表格宏（服务端渲染）
├── TextUtils.java                 # 共用的 HTML 转义与 URL 编码
└── rest/JiraTaskResource.java     # REST 接口

src/main/resources/
├── atlassian-plugin.xml           # 插件描述文件
├── i18n/                          # 界面文案（默认英文 + zh_CN）
├── js/jira-task.js                # 表单前端逻辑
├── css/jira-task.css              # 表单样式
└── templates/
    ├── jira-task-macro.vm         # 新建任务宏模板
    └── jira-issue-table.vm        # 任务表格模板
```

## Jira 兼容性

- 任务类型查询同时兼容 Jira 8（返回数组）与 Jira 9（返回 `values`/`total`）。
- 用户搜索优先调用 `/rest/api/3/user/assignable/search`，接口不存在或参数不被接受（404/405/400）时回退到 `/rest/api/2/user/assignable/search`，并同时支持 `name` 与 `accountId`（Jira Cloud / Server）。
- 创建任务时如果返回 `accountId` 则优先使用，否则使用 `name` 指派经办人。
- 表格查询使用 `/rest/api/2/search`（每次最多 100 条，超出自动翻页），适用于 Jira 9；Jira 10 移除该接口后需要迁移到新的 JQL 搜索接口。
- 表格的 `assignee=@me`、创建任务的报告人、`mypermissions` 校验都以**应用链接身份**在 Jira 中的用户为准。只有在应用链接使用 OAuth 且开启用户模拟（impersonation）时，才等同于当前 Confluence 登录用户；否则请在宏里直接填写具体经办人。

## 离线环境排查

插件不依赖任何外网服务，以下排查全部可在内网完成。

### 1. 一键自检（优先使用）

站点管理员登录后访问：

```
https://<confluence>/rest/jira-task/1.0/diagnose
https://<confluence>/rest/jira-task/1.0/diagnose?projectKey=ABC
```

返回 JSON，按层判读：

| 字段 | 正常表现 | 异常含义 |
| --- | --- | --- |
| `applicationLink.ok` | `true` | `false` 表示未配置 Jira 应用链接或类型不对，`error` 给出异常 |
| `applicationLink.rpcUrl` / `displayUrl` | 内网可达地址 | `rpcUrl` 指向不可达域名/端口，就是"表格空白"的常见根因 |
| `myself.ok` | `true`，含 `displayName` | 401/403 表示应用链接认证失效或账号无 Jira 访问权 |
| `serverInfo.version` | 例如 `9.4.0` | 连接失败时看 `error` 与 `elapsedMs` |
| `fields.count` | 大于 0 | 0 或失败表示 `/rest/api/2/field` 不可用，自定义列无法解析 |
| `myPermissions.createIssues` | `true` | `false` 表示应用链接账号在该项目没有 Create Issues 权限 |

`elapsedMs` 可区分"连不上"（接近 5000，即超时）与"Jira 返回业务错误"（通常几十毫秒）。

### 2. 日志

- 文件：`<confluence-home>/logs/atlassian-confluence.log`
- 默认 INFO 级别即可看到 WARN：记录请求方法、**完整 URL**、状态码、耗时、Jira 响应体（前 300 字符）与异常堆栈。
- 需要每次成功请求的耗时：管理 → 日志与性能分析 → 添加 `com.tangzhi.confluence.jiratask` = DEBUG，无需重启。
- 关键字：`Jira `、`issue table`、`field list`、`Scroll`、`Unexpected error while calling Jira`。

### 3. 网络连通（在 Confluence 服务器上执行）

```powershell
Test-NetConnection <jira-host> -Port 443
curl.exe -k -I https://<jira-host>/rest/api/2/serverInfo
```

`curl` 只能验证网络与证书，无法复现应用链接使用的 OAuth 凭据；认证问题以 `myself` 段为准。

### 4. 前端

- 浏览器 DevTools → Network：`/rest/jira-task/1.0/*` 的响应体含 Jira 原始错误文案。
- 表格宏出错会直接在页面渲染红色错误框（含 Jira 原文），无需登录服务器即可看到。

### 5. 常见现象对照

| 现象 | 优先查看 | 常见原因 |
| --- | --- | --- |
| 表格显示"请先登录后查看 Jira 任务" | 页面匿名访问权限 | 页面开启了匿名查看，属预期行为 |
| 表格显示"未配置到 Jira 的应用链接" | `applicationLink.ok` | 未创建应用链接，或创建成了非 Jira 类型 |
| 表格显示"应用链接需要重新授权" | 管理 → 应用链接状态 | OAuth 凭据过期或被撤销 |
| 表格显示"Jira 字段列表暂时不可用" | `fields` 段 | `/rest/api/2/field` 超时或无权限 |
| 表格空白且无报错 | 日志 `issue table`、表格下方计数 | JQL 没有匹配结果 |
| 创建任务返回 403 | `myPermissions` | 应用链接账号缺少 Create Issues 权限 |
| 创建任务返回 400 并提示字段 | 页面错误框 | 必填字段缺失或字段值不合法（如日期格式） |
| 返回 502 与通用提示 | 日志 `Unexpected error while calling Jira` | 非 Jira 异常（JSON 解析、编码等），堆栈在日志中 |

### 6. 构建期已知告警

`mvn package` 会输出 `Could not generate REST documentation ... invalid doclet`。这是 AMPS 的 REST 文档生成器依赖 JDK 13 之前的 `com.sun.javadoc` API，在 JDK 17 下不可用，**不影响 jar 产物**，可忽略。

## 常见问题

- **提示「未配置到 Jira 的应用链接」**：请在 Confluence「设置 → 应用 → 应用链接」中创建指向 Jira 的链接。
- **提示「应用链接需要重新授权」**：重新授权该应用链接后重试。
- **提示「当前账号在项目 X 中没有创建任务的权限」**：应用链接身份或当前账号在 Jira 项目中没有 Create Issues 权限，请在 Jira 项目权限方案中授予。
- **创建失败并显示 Jira 原始错误**：插件会把 Jira 的 `errorMessages` / `errors` 透出，按提示补齐必填字段或权限即可。
- **表格导出样式异常**：安装 Scroll Table Layout / Scroll Word Exporter 后可获得固定列宽与 Word 模板样式；未安装时表格仍会正常渲染，且标题不会丢失。
