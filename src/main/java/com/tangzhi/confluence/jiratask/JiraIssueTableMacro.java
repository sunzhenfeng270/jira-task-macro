package com.tangzhi.confluence.jiratask;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.macro.xhtml.MacroManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.util.i18n.I18NBean;
import com.atlassian.confluence.util.i18n.I18NBeanFactory;
import com.atlassian.confluence.util.velocity.VelocityUtils;
import com.atlassian.confluence.xhtml.api.XhtmlContent;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Named
public class JiraIssueTableMacro implements Macro {

    private static final Logger log = LoggerFactory.getLogger(JiraIssueTableMacro.class);
    private static final String TEMPLATE = "templates/jira-issue-table.vm";
    private static final String DEFAULT_COLUMNS = "key,type,summary,status,assignee,priority,duedate";
    private static final int DEFAULT_MAX = 50;
    private static final int MAX_LIMIT = 200;
    private static final int JIRA_PAGE_SIZE = 100;
    private static final int TABLE_TIMEOUT_MS = 15_000;
    private static final long FIELD_CACHE_MS = 10 * 60 * 1000L;
    private static final long FIELD_CACHE_FAILURE_MS = 60 * 1000L;
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\border\\s+by\\b");
    private static final DateTimeFormatter JIRA_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static final Map<String, String> COLUMN_FIELD = new LinkedHashMap<>();

    static {
        COLUMN_FIELD.put("key", null);
        COLUMN_FIELD.put("type", "issuetype");
        COLUMN_FIELD.put("summary", "summary");
        COLUMN_FIELD.put("status", "status");
        COLUMN_FIELD.put("assignee", "assignee");
        COLUMN_FIELD.put("reporter", "reporter");
        COLUMN_FIELD.put("priority", "priority");
        COLUMN_FIELD.put("resolution", "resolution");
        COLUMN_FIELD.put("duedate", "duedate");
        COLUMN_FIELD.put("created", "created");
        COLUMN_FIELD.put("updated", "updated");
        COLUMN_FIELD.put("labels", "labels");
        COLUMN_FIELD.put("fixversions", "fixVersions");
        COLUMN_FIELD.put("components", "components");
    }

    private final JiraClient jira;
    private final XhtmlContent xhtmlContent;
    private final MacroManager macroManager;
    private final I18NBeanFactory i18nBeanFactory;

    private volatile Map<String, JiraField> fieldIndex;
    private volatile long fieldIndexExpiry;
    private volatile boolean fieldIndexAvailable;

    @Inject
    public JiraIssueTableMacro(JiraClient jira,
                               @ComponentImport XhtmlContent xhtmlContent,
                               @ComponentImport MacroManager macroManager,
                               @ComponentImport I18NBeanFactory i18nBeanFactory) {
        this.jira = jira;
        this.xhtmlContent = xhtmlContent;
        this.macroManager = macroManager;
        this.i18nBeanFactory = i18nBeanFactory;
    }

    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context)
            throws MacroExecutionException {
        I18NBean i18n = i18nBeanFactory.getI18NBean();
        String title = TextUtils.trim(parameters.get("title"));
        if (title.isEmpty()) {
            title = i18n.getText("jira.task.table.title");
        }

        Map<String, Object> model = new HashMap<>();
        model.put("title", TextUtils.escapeHtml(title));
        model.put("columns", new ArrayList<Map<String, String>>());
        model.put("rows", new ArrayList<Map<String, Object>>());
        model.put("error", "");
        model.put("caption", "");
        model.put("warning", "");
        model.put("emptyText", TextUtils.escapeHtml(i18n.getText("jira.task.table.empty")));
        model.put("showTitle", true);

        // 表格以应用链接身份查询 Jira，匿名访客不应借此看到 Jira 数据
        if (AuthenticatedUserThreadLocal.isAnonymousUser()) {
            model.put("error", TextUtils.escapeHtml(i18n.getText("jira.task.table.login")));
            return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        }

        String jql = buildJql(parameters);
        if (jql.isEmpty()) {
            model.put("error", TextUtils.escapeHtml(i18n.getText("jira.task.error.no.filter")));
            return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        }

        List<String> unresolved = new ArrayList<>();
        List<Map<String, String>> columns = resolveColumns(parameters.get("columns"), i18n, unresolved);
        model.put("columns", columns);
        if (!unresolved.isEmpty()) {
            model.put("warning", TextUtils.escapeHtml(i18n.getText(
                    fieldIndexAvailable
                            ? "jira.task.table.unresolved"
                            : "jira.task.table.fields.unavailable",
                    new Object[]{String.join(", ", unresolved)})));
        }

        Set<String> fields = new LinkedHashSet<>();
        for (Map<String, String> column : columns) {
            String field = column.get("field");
            if (field != null && !field.isEmpty()) {
                fields.add(field);
            }
        }
        if (fields.isEmpty()) {
            // 只展示 key 列时也要显式请求最小字段集，避免 Jira 返回全部字段
            fields.add("summary");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        try {
            int max = parseMax(parameters.get("maxResults"));
            int startAt = 0;
            while (rows.size() < max) {
                int pageSize = Math.min(JIRA_PAGE_SIZE, max - rows.size());
                String url = "/rest/api/2/search?jql=" + TextUtils.encode(jql)
                        + "&startAt=" + startAt
                        + "&maxResults=" + pageSize
                        + "&fields=" + TextUtils.encode(String.join(",", fields));
                // 页面渲染是同步的，使用较短超时避免 Jira 异常时长时间占用请求线程
                JsonNode data = jira.get(url, TABLE_TIMEOUT_MS);
                JsonNode issues = data.path("issues");
                total = data.path("total").asInt(total);
                for (JsonNode issue : issues) {
                    if (rows.size() >= max) {
                        break;
                    }
                    rows.add(buildRow(issue, columns));
                }
                startAt += issues.size();
                if (issues.size() == 0 || startAt >= total) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query Jira for the issue table (pageId={}, jql={})",
                    pageId(context), abbreviate(jql), e);
            String message = e.getMessage();
            if (message == null || message.isEmpty()) {
                message = i18n.getText("jira.task.error.query");
            }
            model.put("error", TextUtils.escapeHtml(message));
        }

        model.put("rows", rows);
        total = Math.max(total, rows.size());
        if (total > 0) {
            String caption = i18n.getText("jira.task.table.caption",
                    new Object[]{total, rows.size()});
            model.put("caption", TextUtils.escapeHtml(caption.trim()));
        }

        if (rows.isEmpty() || !((String) model.get("error")).isEmpty()) {
            return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        }

        // 有数据时用 Scroll Title / Scroll Table Layout 包裹，供 Scroll Word 导出应用样式
        model.put("showTitle", false);
        String tableHtml = VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        String wrapped = wrapInScrollMacros(tableHtml, title, columns.size(), context);
        if (wrapped != null) {
            return wrapped;
        }
        // Scroll 宏不可用时回退为普通表格，并恢复标题，避免标题丢失
        model.put("showTitle", true);
        return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
    }

    /**
     * 用 Scroll Title 包裹表格，并在表格前插入 Scroll Table Layout，
     * 使 Scroll Word 导出时应用固定列宽、固定宽度、Word 模板样式等设置。
     * 返回 null 表示 Scroll 宏不可用，由调用方回退为普通表格。
     */
    private String wrapInScrollMacros(String tableHtml, String title, int columnCount,
                                      ConversionContext context) {
        // 未安装 Scroll 时，存储格式转换不会抛异常，而是渲染成「未知宏」占位，
        // 因此必须先确认宏确实可用，否则直接回退为普通表格。
        if (macroManager.getMacroByName("scroll-title") == null
                || macroManager.getMacroByName("scroll-table-layout") == null) {
            return null;
        }

        StringBuilder widths = new StringBuilder();
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                widths.append(',');
            }
            widths.append("1*");
        }

        String storage = "<ac:structured-macro ac:name=\"scroll-title\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"title\">" + TextUtils.escapeHtml(title) + "</ac:parameter>"
                + "<ac:parameter ac:name=\"title-position\">top</ac:parameter>"
                + "<ac:parameter ac:name=\"title-alignment\">center</ac:parameter>"
                + "<ac:rich-text-body>"
                + "<ac:structured-macro ac:name=\"scroll-table-layout\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"column-widths\">" + widths + "</ac:parameter>"
                + "<ac:parameter ac:name=\"table-sorting\">disabled</ac:parameter>"
                + "<ac:parameter ac:name=\"cell-highlighting\">disabled</ac:parameter>"
                + "<ac:parameter ac:name=\"page-orientation\">current</ac:parameter>"
                + "<ac:parameter ac:name=\"table-cell-styling\">word</ac:parameter>"
                + "<ac:parameter ac:name=\"table-fitting\">fixed</ac:parameter>"
                + "</ac:structured-macro>"
                + tableHtml
                + "</ac:rich-text-body>"
                + "</ac:structured-macro>";

        try {
            return xhtmlContent.convertStorageToView(storage, context);
        } catch (Exception e) {
            log.debug("Unable to render the Scroll wrapper, falling back to a plain table", e);
            return null;
        }
    }

    @Override
    public BodyType getBodyType() {
        return BodyType.NONE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.BLOCK;
    }

    private List<Map<String, String>> resolveColumns(String raw, I18NBean i18n,
                                                     List<String> unresolved) {
        String source = (raw == null || raw.trim().isEmpty()) ? DEFAULT_COLUMNS : raw;
        List<Map<String, String>> columns = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        Set<String> seenFields = new LinkedHashSet<>();

        for (String token : source.split(",")) {
            String original = token.trim();
            String key = original.toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !seenKeys.add(key)) {
                continue;
            }
            if (COLUMN_FIELD.containsKey(key)) {
                String field = COLUMN_FIELD.get(key);
                if (field != null && !seenFields.add(field)) {
                    continue;
                }
                columns.add(column(key, field, i18n.getText("jira.task.col." + key)));
                continue;
            }

            // 非内置列：支持 customfield_<id>，也支持 Jira 字段名（如 End date、enddate）
            JiraField resolved = resolveField(key);
            if (resolved == null) {
                if (key.matches("customfield_\\d+")) {
                    // 字段列表不可用时仍按原样请求，保持旧行为
                    if (seenFields.add(key)) {
                        columns.add(column(key, key,
                                i18n.getText("jira.task.col.custom", new Object[]{key})));
                    }
                } else {
                    // 保留用户输入的原始写法，便于在提示中定位
                    unresolved.add(original);
                }
                continue;
            }
            if (!seenFields.add(resolved.id)) {
                // 同一字段的多种写法（如 enddate 与 End date）只保留一列
                continue;
            }
            Map<String, String> column = column(key, resolved.id,
                    resolved.name.isEmpty() ? key : resolved.name);
            column.put("type", resolved.type);
            columns.add(column);
        }

        if (columns.isEmpty()) {
            return resolveColumns(DEFAULT_COLUMNS, i18n, unresolved);
        }
        return columns;
    }

    private static Map<String, String> column(String key, String field, String label) {
        Map<String, String> column = new LinkedHashMap<>();
        column.put("key", key);
        column.put("field", field == null ? "" : field);
        column.put("label", TextUtils.escapeHtml(label));
        return column;
    }

    /**
     * 按字段 id、显示名称、以及去掉空格/下划线/连字符的名称建立索引，
     * 从 Jira 字段定义接口一次读取并缓存，避免每次渲染都请求。
     */
    private JiraField resolveField(String token) {
        Map<String, JiraField> index = fieldIndex();
        JiraField field = index.get(token);
        if (field == null) {
            field = index.get(normalizeFieldName(token));
        }
        return field;
    }

    private synchronized Map<String, JiraField> fieldIndex() {
        long now = System.currentTimeMillis();
        Map<String, JiraField> cached = fieldIndex;
        if (cached != null && now < fieldIndexExpiry) {
            return cached;
        }
        Map<String, JiraField> index = new HashMap<>();
        long ttl = FIELD_CACHE_FAILURE_MS;
        boolean available = false;
        try {
            // 字段列表探测使用短超时，避免 Jira 不可用时长时间阻塞页面渲染
            JsonNode data = jira.get("/rest/api/2/field", 5000);
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                String name = node.path("name").asText("");
                JiraField field = new JiraField(id, name,
                        node.path("schema").path("type").asText(""));
                index.put(id.toLowerCase(Locale.ROOT), field);
                if (!name.isEmpty()) {
                    index.put(name.toLowerCase(Locale.ROOT), field);
                    index.putIfAbsent(normalizeFieldName(name), field);
                }
            }
            ttl = FIELD_CACHE_MS;
            available = true;
        } catch (Exception e) {
            // 字段列表读取失败时不影响表格渲染，短时间内重试
            log.warn("Unable to load the Jira field list, column names cannot be resolved", e);
        }
        fieldIndexAvailable = available;
        fieldIndex = index;
        fieldIndexExpiry = now + ttl;
        return index;
    }

    private static String normalizeFieldName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }

    private static final class JiraField {
        private final String id;
        private final String name;
        private final String type;

        private JiraField(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    private String buildJql(Map<String, String> parameters) {
        String explicit = TextUtils.trim(parameters.get("jql"));
        if (!explicit.isEmpty()) {
            // 先去掉字符串字面量（双引号与单引号），避免 JQL 文本中出现 "order by" 时误判
            String searchable = explicit.replaceAll("\"[^\"]*\"", "\"\"")
                    .replaceAll("'[^']*'", "''");
            return ORDER_BY.matcher(searchable).find()
                    ? explicit : explicit + " ORDER BY updated DESC";
        }

        List<String> clauses = new ArrayList<>();

        String projectKey = TextUtils.trim(parameters.get("projectKey"));
        if (!projectKey.isEmpty()) {
            clauses.add("project = \"" + jqlQuote(projectKey) + "\"");
        }

        String statuses = TextUtils.trim(parameters.get("statuses"));
        if (!statuses.isEmpty()) {
            List<String> quoted = new ArrayList<>();
            for (String status : statuses.split(",")) {
                if (!status.trim().isEmpty()) {
                    quoted.add("\"" + jqlQuote(status.trim()) + "\"");
                }
            }
            if (!quoted.isEmpty()) {
                clauses.add("status in (" + String.join(",", quoted) + ")");
            }
        }

        String assignee = TextUtils.trim(parameters.get("assignee"));
        if (!assignee.isEmpty()) {
            if (assignee.equals("@me") || assignee.equalsIgnoreCase("currentUser()")) {
                clauses.add("assignee = currentUser()");
            } else {
                clauses.add("assignee = \"" + jqlQuote(assignee) + "\"");
            }
        }

        String labels = TextUtils.trim(parameters.get("labels"));
        if (!labels.isEmpty()) {
            for (String label : labels.split(",")) {
                if (!label.trim().isEmpty()) {
                    clauses.add("labels = \"" + jqlQuote(label.trim()) + "\"");
                }
            }
        }

        if (clauses.isEmpty()) {
            return "";
        }
        return String.join(" AND ", clauses) + " ORDER BY updated DESC";
    }

    private Map<String, Object> buildRow(JsonNode issue, List<Map<String, String>> columns) {
        String key = issue.path("key").asText("");
        String baseUrl = issue.path("self").asText("").replaceAll("/rest/api/.*$", "");

        List<Map<String, String>> cells = new ArrayList<>();
        for (Map<String, String> column : columns) {
            Map<String, String> cell = new LinkedHashMap<>();
            cell.put("text", TextUtils.escapeHtml(cellText(issue, column)));
            cell.put("link", "key".equals(column.get("key")) && !baseUrl.isEmpty()
                    ? TextUtils.escapeHtml(baseUrl + "/browse/" + key) : "");
            cells.add(cell);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cells", cells);
        return row;
    }

    private String cellText(JsonNode issue, Map<String, String> column) {
        String columnKey = column.get("key");
        String field = column.get("field");
        JsonNode fields = issue.path("fields");
        switch (columnKey) {
            case "key":
                return issue.path("key").asText("");
            case "summary":
                return fields.path("summary").asText("");
            case "status":
                return fields.path("status").path("name").asText("");
            case "type":
                return fields.path("issuetype").path("name").asText("");
            case "assignee":
                return fields.path("assignee").path("displayName").asText("");
            case "reporter":
                return fields.path("reporter").path("displayName").asText("");
            case "priority":
                return fields.path("priority").path("name").asText("");
            case "resolution":
                return fields.path("resolution").path("name").asText("");
            case "labels":
                return stringify(fields.path("labels"));
            case "fixversions":
                return stringify(fields.path("fixVersions"));
            case "components":
                return stringify(fields.path("components"));
            case "duedate":
            case "created":
            case "updated":
                return formatDate(fields.path(field).asText(""));
            default:
                String value = stringify(fields.path(field));
                String type = column.get("type");
                // 自定义日期字段也按日期展示，避免显示完整时间戳
                return "date".equals(type) || "datetime".equals(type)
                        ? formatColumnDate(value) : value;
        }
    }

    /**
     * Jira 返回的时间带有时区偏移（如 2024-01-02T03:04:05.000+0000），
     * 直接取该偏移下的日期，与 Jira 界面显示一致，也不受 Confluence 服务器时区影响。
     */
    private static String formatDate(String value) {
        LocalDate date = parseDate(value);
        if (date != null) {
            return date.toString();
        }
        return value == null ? "" : (value.length() >= 10 ? value.substring(0, 10) : value);
    }

    /**
     * 自定义日期字段同样只取日期部分，不做时区转换，
     * 避免 Confluence 与 Jira 服务器时区不同时日期偏移一天。
     */
    private static String formatColumnDate(String value) {
        LocalDate date = parseDate(value);
        if (date != null) {
            return date.toString();
        }
        return value == null ? "" : (value.length() >= 10 ? value.substring(0, 10) : value);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // 兼容 ISO（+08:00 / Z）与 Jira 传统的 +0000 写法
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (Exception ignored) {
            // 继续尝试 Jira 格式
        }
        try {
            return OffsetDateTime.parse(value, JIRA_DATE).toLocalDate();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stringify(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                String part = stringify(child);
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            return String.join(", ", parts);
        }
        if (node.isObject()) {
            if (node.hasNonNull("name")) {
                return node.get("name").asText("");
            }
            if (node.hasNonNull("value")) {
                return node.get("value").asText("");
            }
            if (node.hasNonNull("displayName")) {
                return node.get("displayName").asText("");
            }
            return "";
        }
        return node.asText("");
    }

    private static int parseMax(String raw) {
        try {
            int value = Integer.parseInt(TextUtils.trim(raw));
            return Math.max(1, Math.min(value, MAX_LIMIT));
        } catch (Exception e) {
            return DEFAULT_MAX;
        }
    }

    private static String jqlQuote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 从渲染上下文取页面 ID，便于把宏报错与具体页面关联起来。
     */
    private static String pageId(ConversionContext context) {
        if (context == null || context.getPageContext() == null) {
            return "";
        }
        Object entity = context.getPageContext().getEntity();
        if (entity instanceof ContentEntityObject) {
            return String.valueOf(((ContentEntityObject) entity).getId());
        }
        return "";
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }
}
