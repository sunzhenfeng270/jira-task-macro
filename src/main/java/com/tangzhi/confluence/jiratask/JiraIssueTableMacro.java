package com.tangzhi.confluence.jiratask;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.util.velocity.VelocityUtils;
import com.atlassian.confluence.xhtml.api.XhtmlContent;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.JsonNode;

import javax.inject.Inject;
import javax.inject.Named;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Named
public class JiraIssueTableMacro implements Macro {

    private static final String TEMPLATE = "templates/jira-issue-table.vm";
    private static final String DEFAULT_COLUMNS = "key,type,summary,status,assignee,priority,duedate";
    private static final int DEFAULT_MAX = 50;
    private static final int MAX_LIMIT = 200;

    private static final Map<String, String> COLUMN_FIELD = new LinkedHashMap<>();
    private static final Map<String, String> COLUMN_LABEL = new LinkedHashMap<>();

    static {
        put("key", null, "任务");
        put("type", "issuetype", "类型");
        put("summary", "summary", "摘要");
        put("status", "status", "状态");
        put("assignee", "assignee", "经办人");
        put("reporter", "reporter", "报告人");
        put("priority", "priority", "优先级");
        put("resolution", "resolution", "解决结果");
        put("duedate", "duedate", "截止日期");
        put("created", "created", "创建时间");
        put("updated", "updated", "更新时间");
        put("labels", "labels", "标签");
        put("fixversions", "fixVersions", "修复版本");
        put("components", "components", "模块");
    }

    private static void put(String column, String field, String label) {
        COLUMN_FIELD.put(column, field);
        COLUMN_LABEL.put(column, label);
    }

    private final JiraClient jira;
    private final XhtmlContent xhtmlContent;

    @Inject
    public JiraIssueTableMacro(JiraClient jira,
                               @ComponentImport XhtmlContent xhtmlContent) {
        this.jira = jira;
        this.xhtmlContent = xhtmlContent;
    }

    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context)
            throws MacroExecutionException {
        List<Map<String, String>> columns = resolveColumns(parameters.get("columns"));
        String title = trim(parameters.get("title"));
        if (title.isEmpty()) {
            title = "Jira 任务";
        }

        Map<String, Object> model = new HashMap<>();
        model.put("title", escape(title));
        model.put("columns", columns);
        model.put("rows", new ArrayList<Map<String, Object>>());
        model.put("total", 0);
        model.put("shown", 0);
        model.put("error", "");
        model.put("showTitle", true);

        String jql = buildJql(parameters);
        if (jql.isEmpty()) {
            model.put("error", "未配置过滤条件：请至少填写 JQL 或项目 Key");
            return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        }

        Set<String> fields = new LinkedHashSet<>();
        for (Map<String, String> column : columns) {
            String field = column.get("field");
            if (field != null && !field.isEmpty()) {
                fields.add(field);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder("/rest/api/2/search?jql=")
                    .append(encode(jql))
                    .append("&maxResults=")
                    .append(parseMax(parameters.get("maxResults")));
            if (!fields.isEmpty()) {
                url.append("&fields=").append(encode(String.join(",", fields)));
            }

            JsonNode data = jira.get(url.toString());
            for (JsonNode issue : data.path("issues")) {
                rows.add(buildRow(issue, columns));
            }
            model.put("shown", rows.size());
            model.put("total", data.path("total").asInt(rows.size()));
        } catch (Exception e) {
            model.put("error", escape(e.getMessage() == null ? "查询 Jira 失败" : e.getMessage()));
        }

        // 有数据时用 Scroll Title / Scroll Table Layout 包裹，供 Scroll Word 导出应用样式
        if (rows.isEmpty() || !((String) model.get("error")).isEmpty()) {
            model.put("rows", rows);
            return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        }

        model.put("rows", rows);
        model.put("showTitle", false);
        String tableHtml = VelocityUtils.getRenderedTemplate(TEMPLATE, model);
        return wrapInScrollMacros(tableHtml, title, columns.size(), context);
    }

    /**
     * 用 Scroll Title 包裹表格，并在表格前插入 Scroll Table Layout，
     * 使 Scroll Word 导出时应用固定列宽、固定宽度、Word 模板样式等设置。
     */
    private String wrapInScrollMacros(String tableHtml, String title, int columnCount,
                                      ConversionContext context) {
        StringBuilder widths = new StringBuilder();
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                widths.append(',');
            }
            widths.append("1*");
        }

        String storage = "<ac:structured-macro ac:name=\"scroll-title\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"title\">" + escape(title) + "</ac:parameter>"
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
            // Scroll 宏未安装或渲染失败时退回普通表格
            return tableHtml;
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

    private List<Map<String, String>> resolveColumns(String raw) {
        String source = (raw == null || raw.trim().isEmpty()) ? DEFAULT_COLUMNS : raw;
        List<Map<String, String>> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String token : source.split(",")) {
            String key = token.trim().toLowerCase();
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            String field;
            String label;
            if (COLUMN_LABEL.containsKey(key)) {
                field = COLUMN_FIELD.get(key);
                label = COLUMN_LABEL.get(key);
            } else if (key.matches("customfield_\\d+")) {
                field = key;
                label = "自定义(" + key + ")";
            } else {
                continue;
            }
            Map<String, String> column = new LinkedHashMap<>();
            column.put("key", key);
            column.put("field", field == null ? "" : field);
            column.put("label", label);
            columns.add(column);
        }

        if (columns.isEmpty()) {
            return resolveColumns(DEFAULT_COLUMNS);
        }
        return columns;
    }

    private String buildJql(Map<String, String> parameters) {
        String explicit = trim(parameters.get("jql"));
        if (!explicit.isEmpty()) {
            return explicit.toLowerCase().contains("order by")
                    ? explicit : explicit + " ORDER BY updated DESC";
        }

        List<String> clauses = new ArrayList<>();

        String projectKey = trim(parameters.get("projectKey"));
        if (!projectKey.isEmpty()) {
            clauses.add("project = \"" + jqlQuote(projectKey) + "\"");
        }

        String statuses = trim(parameters.get("statuses"));
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

        String assignee = trim(parameters.get("assignee"));
        if (!assignee.isEmpty()) {
            if (assignee.equals("@me") || assignee.equalsIgnoreCase("currentUser()")) {
                clauses.add("assignee = currentUser()");
            } else {
                clauses.add("assignee = \"" + jqlQuote(assignee) + "\"");
            }
        }

        String labels = trim(parameters.get("labels"));
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
            cell.put("text", escape(cellText(issue, column.get("key"), column.get("field"))));
            cell.put("link", "key".equals(column.get("key"))
                    ? escape(baseUrl + "/browse/" + key) : "");
            cells.add(cell);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cells", cells);
        return row;
    }

    private String cellText(JsonNode issue, String column, String field) {
        JsonNode fields = issue.path("fields");
        switch (column) {
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
            case "updated": {
                String value = fields.path(field).asText("");
                return value.length() >= 10 ? value.substring(0, 10) : value;
            }
            default:
                return stringify(fields.path(field));
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
            return String.join("，", parts);
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
            int value = Integer.parseInt(trim(raw));
            return Math.max(1, Math.min(value, MAX_LIMIT));
        } catch (Exception e) {
            return DEFAULT_MAX;
        }
    }

    private static String jqlQuote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
