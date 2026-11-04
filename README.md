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
- 支持自定义显示列、最大条数
- 任务 Key 自动链接到 Jira 浏览页
- 可选使用 Scroll Title / Scroll Table Layout 包裹表格（未安装 Scroll 插件时自动回退为普通表格）

## 环境要求

- Confluence 8.9.4
- JDK 17
- Maven 3.8+
- Confluence 与 Jira 之间已配置**应用链接**（Application Link），建议使用 OAuth 或 Trusted App 认证

## 构建与安装

```bash
mvn package
```

产物位于 `target/jira-task-macro-1.0.0.jar`。在 Confluence「管理 → 管理应用（UPM）」中上传该 JAR 即可安装/升级。

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

可用列：`key`、`type`、`summary`、`status`、`assignee`、`reporter`、`priority`、`resolution`、`duedate`、`created`、`updated`、`labels`、`fixversions`、`components`，也支持 `customfield_<id>` 自定义字段。

默认列：`key,type,summary,status,assignee,priority,duedate`

## REST 接口

前端表单通过以下接口访问 Jira（路径前缀 `/rest/jira-task/1.0`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/projects?query=` | 查询 Jira 项目 |
| GET | `/issuetypes?projectKey=` | 查询项目下可创建的任务类型 |
| GET | `/priorities` | 查询优先级 |
| GET | `/users?projectKey=&query=` | 搜索可指派用户 |
| POST | `/issues` | 创建任务 |
| GET | `/issues?pageId=` | 查询本页已创建的任务 |

所有 Jira 调用均通过 Confluence 的应用链接完成，无需在插件中单独配置 Jira 账号密码。

## 项目结构

```
src/main/java/com/tangzhi/confluence/jiratask/
├── JiraClient.java                # 基于应用链接的 Jira REST 客户端
├── JiraTaskMacro.java             # 新建任务宏
├── JiraIssueTableMacro.java       # 任务表格宏（服务端渲染）
└── rest/JiraTaskResource.java     # REST 接口

src/main/resources/
├── atlassian-plugin.xml           # 插件描述文件
├── js/jira-task.js                # 表单前端逻辑
├── css/jira-task.css              # 表单样式
└── templates/
    ├── jira-task-macro.vm         # 新建任务宏模板
    └── jira-issue-table.vm        # 任务表格模板
```

## 常见问题

- **提示「未配置到 Jira 的应用链接」**：请在 Confluence「设置 → 应用 → 应用链接」中创建指向 Jira 的链接。
- **提示「应用链接需要重新授权」**：重新授权该应用链接后重试。
- **表格导出样式异常**：安装 Scroll Table Layout / Scroll Word Exporter 后可获得固定列宽与 Word 模板样式；未安装时表格仍会正常渲染。
