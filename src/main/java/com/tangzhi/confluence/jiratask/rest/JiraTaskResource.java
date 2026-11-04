package com.tangzhi.confluence.jiratask.rest;

import com.atlassian.confluence.pages.AbstractPage;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.util.i18n.I18NBean;
import com.atlassian.confluence.util.i18n.I18NBeanFactory;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.permission.PermissionEnforcer;
import com.fasterxml.jackson.databind.JsonNode;
import com.tangzhi.confluence.jiratask.JiraClient;
import com.tangzhi.confluence.jiratask.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Named
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class JiraTaskResource {

    private static final Logger log = LoggerFactory.getLogger(JiraTaskResource.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_RELATED = 200;

    private final JiraClient jira;
    private final PageManager pageManager;
    private final PermissionManager permissionManager;
    private final PermissionEnforcer permissionEnforcer;
    private final I18NBeanFactory i18nBeanFactory;

    @Inject
    public JiraTaskResource(JiraClient jira,
                            @ComponentImport PageManager pageManager,
                            @ComponentImport PermissionManager permissionManager,
                            @ComponentImport PermissionEnforcer permissionEnforcer,
                            @ComponentImport I18NBeanFactory i18nBeanFactory) {
        this.jira = jira;
        this.pageManager = pageManager;
        this.permissionManager = permissionManager;
        this.permissionEnforcer = permissionEnforcer;
        this.i18nBeanFactory = i18nBeanFactory;
    }

    @GET
    @Path("/projects")
    public Response projects(@QueryParam("query") String query) {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        try {
            JsonNode data = jira.get("/rest/api/2/project");
            // Jira 8/9 返回数组，部分版本/代理返回 {values: [...]}
            JsonNode projects = data.isArray() ? data : data.path("values");
            if (!projects.isArray()) {
                log.warn("Unexpected response from Jira /project: {}", data);
                return error(502, i18n().getText("jira.task.error.query"));
            }
            List<Map<String, String>> result = new ArrayList<>();
            String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
            for (JsonNode project : projects) {
                String key = project.path("key").asText("");
                String name = project.path("name").asText("");
                if (q.isEmpty()
                        || key.toLowerCase(Locale.ROOT).contains(q)
                        || name.toLowerCase(Locale.ROOT).contains(q)) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("name", name);
                    result.add(item);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GET
    @Path("/issuetypes")
    public Response issueTypes(@QueryParam("projectKey") String projectKey) {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        if (projectKey == null || projectKey.isEmpty()) {
            return badRequest(i18n().getText("jira.task.error.missing.param"));
        }
        if (!canCreateIssue(projectKey)) {
            return error(Response.Status.FORBIDDEN,
                    i18n().getText("jira.task.error.no.create.permission", new Object[]{projectKey}));
        }
        try {
            List<Map<String, String>> result = new ArrayList<>();
            int startAt = 0;
            while (true) {
                JsonNode data = jira.get("/rest/api/2/issue/createmeta/"
                        + TextUtils.encode(projectKey)
                        + "/issuetypes?startAt=" + startAt + "&maxResults=50");
                // Jira 8 返回数组，Jira 9 返回 {values, total}
                JsonNode values = data.isArray() ? data : data.path("values");
                int total = data.isArray() ? values.size() : data.path("total").asInt(values.size());
                for (JsonNode type : values) {
                    if (type.path("subtask").asBoolean(false)) {
                        continue;
                    }
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("id", type.path("id").asText(""));
                    item.put("name", type.path("name").asText(""));
                    result.add(item);
                }
                startAt += values.size();
                if (data.isArray() || values.size() == 0 || startAt >= total) {
                    break;
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GET
    @Path("/priorities")
    public Response priorities() {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        try {
            JsonNode data = jira.get("/rest/api/2/priority");
            List<Map<String, String>> result = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode priority : data) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("id", priority.path("id").asText(""));
                    item.put("name", priority.path("name").asText(""));
                    result.add(item);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GET
    @Path("/users")
    public Response users(@QueryParam("projectKey") String projectKey,
                          @QueryParam("query") String query) {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        if (query == null || query.isEmpty() || projectKey == null || projectKey.isEmpty()) {
            return Response.ok(Collections.emptyList()).build();
        }
        try {
            JsonNode data = searchAssignableUsers(projectKey, query);
            List<Map<String, String>> result = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode user : data) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("name", user.path("name").asText(""));
                    item.put("accountId", user.path("accountId").asText(""));
                    item.put("displayName", user.path("displayName").asText(""));
                    // 不返回邮箱，避免任何登录用户借此枚举 Jira 用户联系方式
                    result.add(item);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * Jira Cloud 的 assignable search 使用 query 参数，Server/DC 使用 username，
     * 优先走 v3；v3 不存在或参数不被接受时回退到 v2，兼容两种部署形态。
     */
    private JsonNode searchAssignableUsers(String projectKey, String query) throws IOException {
        String base = "/user/assignable/search?project=" + TextUtils.encode(projectKey)
                + "&maxResults=10";
        try {
            return jira.get("/rest/api/3" + base + "&query=" + TextUtils.encode(query));
        } catch (JiraClient.JiraException e) {
            // 404/405 表示接口不存在，400 表示参数不被接受；其余状态（如 403）按真实错误抛出
            int status = e.getStatus();
            if (status != 400 && status != 404 && status != 405) {
                throw e;
            }
            log.debug("Jira v3 user search unavailable (HTTP {}), falling back to v2", status);
            return jira.get("/rest/api/2" + base + "&username=" + TextUtils.encode(query));
        }
    }

    /**
     * 离线环境自检：一次性返回应用链接、Jira 身份、版本、字段数量与项目创建权限，
     * 用一份快照定位"配置 / 网络 / 认证 / 权限"哪一层出了问题。仅站点管理员可调用。
     */
    @GET
    @Path("/diagnose")
    public Response diagnose(@QueryParam("projectKey") String projectKey) {
        Response denied = requireAdmin();
        if (denied != null) {
            return denied;
        }

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> link = new LinkedHashMap<>();
        try {
            link.putAll(jira.describeLink());
            link.put("ok", true);
        } catch (Exception e) {
            link.put("ok", false);
            link.put("error", describeError(e));
        }
        result.put("applicationLink", link);

        result.put("myself", probe("/rest/api/2/myself", "displayName", "name"));
        result.put("serverInfo", probe("/rest/api/2/serverInfo", "version", "deploymentType"));
        result.put("fields", probe("/rest/api/2/field"));

        if (projectKey != null && !projectKey.trim().isEmpty()) {
            Map<String, Object> step = new LinkedHashMap<>();
            long started = System.currentTimeMillis();
            try {
                JsonNode data = jira.get("/rest/api/2/mypermissions?projectKey="
                        + TextUtils.encode(projectKey.trim()) + "&permissions=CREATE_ISSUES", 5000);
                step.put("ok", true);
                step.put("projectKey", projectKey.trim());
                step.put("createIssues", data.path("permissions").path("CREATE_ISSUES")
                        .path("havePermission").asBoolean(false));
            } catch (Exception e) {
                step.put("ok", false);
                step.put("error", describeError(e));
            }
            step.put("elapsedMs", System.currentTimeMillis() - started);
            result.put("myPermissions", step);
        }

        return Response.ok(result).build();
    }

    /**
     * 执行一次轻量 Jira 请求，汇总关键字段、耗时与错误，失败不抛异常。
     */
    private Map<String, Object> probe(String path, String... fields) {
        Map<String, Object> step = new LinkedHashMap<>();
        long started = System.currentTimeMillis();
        try {
            JsonNode data = jira.get(path, 5000);
            step.put("ok", true);
            if (data.isArray()) {
                step.put("count", data.size());
            } else {
                for (String field : fields) {
                    JsonNode value = data.path(field);
                    if (!value.isMissingNode() && !value.isNull()) {
                        step.put(field, value.asText(""));
                    }
                }
            }
        } catch (Exception e) {
            step.put("ok", false);
            step.put("error", describeError(e));
        }
        step.put("elapsedMs", System.currentTimeMillis() - started);
        return step;
    }

    private static String describeError(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    @POST
    @Path("/issues")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createIssue(Map<String, Object> payload) {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        if (payload == null) {
            return badRequest(i18n().getText("jira.task.error.required"));
        }
        String pageId = str(payload.get("pageId"));
        Response pageDenied = requirePage(pageId);
        if (pageDenied != null) {
            return pageDenied;
        }

        String projectKey = str(payload.get("projectKey"));
        String issueTypeName = str(payload.get("issueTypeName"));
        String summary = str(payload.get("summary"));
        if (projectKey.isEmpty() || issueTypeName.isEmpty() || summary.isEmpty()) {
            return badRequest(i18n().getText("jira.task.error.required"));
        }
        if (!canCreateIssue(projectKey)) {
            return error(Response.Status.FORBIDDEN,
                    i18n().getText("jira.task.error.no.create.permission", new Object[]{projectKey}));
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Collections.singletonMap("key", projectKey));
        fields.put("issuetype", Collections.singletonMap("name", issueTypeName));
        fields.put("summary", summary);

        String description = str(payload.get("description"));
        if (!description.isEmpty()) {
            fields.put("description", description);
        }
        String dueDate = str(payload.get("dueDate"));
        if (!dueDate.isEmpty()) {
            fields.put("duedate", dueDate);
        }
        String priorityId = str(payload.get("priorityId"));
        if (!priorityId.isEmpty()) {
            fields.put("priority", Collections.singletonMap("id", priorityId));
        }
        String assigneeName = str(payload.get("assigneeName"));
        String assigneeAccountId = str(payload.get("assigneeAccountId"));
        if (!assigneeAccountId.isEmpty()) {
            fields.put("assignee", Collections.singletonMap("accountId", assigneeAccountId));
        } else if (!assigneeName.isEmpty()) {
            fields.put("assignee", Collections.singletonMap("name", assigneeName));
        }

        Set<String> labels = new LinkedHashSet<>();
        Object rawLabels = payload.get("labels");
        if (rawLabels instanceof List) {
            for (Object label : (List<?>) rawLabels) {
                String value = str(label);
                if (!value.isEmpty()) {
                    labels.add(value);
                }
            }
        }
        labels.add("confluence-page-" + pageId);
        fields.put("labels", new ArrayList<>(labels));

        try {
            JsonNode created = jira.post("/rest/api/2/issue",
                    Collections.singletonMap("fields", fields));
            String key = created.path("key").asText("");
            Map<String, String> result = new LinkedHashMap<>();
            result.put("id", created.path("id").asText(""));
            result.put("key", key);
            result.put("summary", summary);
            result.put("url", browseUrl(created.path("self").asText(""), key));
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GET
    @Path("/issues")
    public Response pageIssues(@QueryParam("pageId") String pageId) {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        Response pageDenied = requirePage(pageId);
        if (pageDenied != null) {
            return pageDenied;
        }

        String jql = "labels = \"confluence-page-" + pageId + "\" ORDER BY created DESC";
        try {
            List<Map<String, String>> result = new ArrayList<>();
            int total = 0;
            int startAt = 0;
            while (result.size() < MAX_RELATED) {
                JsonNode data = jira.get("/rest/api/2/search?jql=" + TextUtils.encode(jql)
                        + "&startAt=" + startAt
                        + "&maxResults=" + PAGE_SIZE
                        + "&fields=summary,status,assignee,duedate,issuetype");
                JsonNode issues = data.path("issues");
                total = data.path("total").asInt(result.size());
                for (JsonNode issue : issues) {
                    if (result.size() >= MAX_RELATED) {
                        break;
                    }
                    String key = issue.path("key").asText("");
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("summary", issue.path("fields").path("summary").asText(""));
                    item.put("status", issue.path("fields").path("status").path("name").asText(""));
                    item.put("type", issue.path("fields").path("issuetype").path("name").asText(""));
                    item.put("assignee", issue.path("fields").path("assignee").path("displayName").asText(""));
                    item.put("dueDate", issue.path("fields").path("duedate").asText(""));
                    item.put("url", browseUrl(issue.path("self").asText(""), key));
                    result.add(item);
                }
                startAt += issues.size();
                if (issues.size() == 0 || startAt >= total) {
                    break;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("issues", result);
            body.put("total", total);
            body.put("shown", result.size());
            return Response.ok(body).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * 以应用链接身份查询 Jira 的 mypermissions。查询失败时返回 true，
     * 由 Jira 在创建任务时做最终校验，避免因接口不可用而误拦截。
     */
    private boolean canCreateIssue(String projectKey) {
        try {
            JsonNode data = jira.get("/rest/api/2/mypermissions?projectKey="
                    + TextUtils.encode(projectKey) + "&permissions=CREATE_ISSUES");
            return data.path("permissions").path("CREATE_ISSUES").path("havePermission").asBoolean(false);
        } catch (Exception e) {
            log.debug("Unable to check CREATE_ISSUES permission for {}", projectKey, e);
            return true;
        }
    }

    private Response requireLogin() {
        if (AuthenticatedUserThreadLocal.isAnonymousUser() || AuthenticatedUserThreadLocal.get() == null) {
            return error(Response.Status.UNAUTHORIZED, i18n().getText("jira.task.error.login"));
        }
        return null;
    }

    private Response requireAdmin() {
        Response denied = requireLogin();
        if (denied != null) {
            return denied;
        }
        if (!permissionEnforcer.isAdmin()) {
            return error(Response.Status.FORBIDDEN, i18n().getText("jira.task.error.admin"));
        }
        return null;
    }

    private Response requirePage(String pageId) {
        // 限制长度，避免超大数字触发 NumberFormatException 导致 500
        if (pageId == null || !pageId.matches("\\d{1,18}")) {
            return badRequest(i18n().getText("jira.task.error.pageid"));
        }
        AbstractPage page = pageManager.getAbstractPage(Long.parseLong(pageId));
        // 无权限与页面不存在返回相同结果，避免通过状态码枚举页面 ID
        if (page == null
                || !permissionManager.hasPermission(AuthenticatedUserThreadLocal.get(),
                Permission.VIEW, page)) {
            return error(Response.Status.NOT_FOUND, i18n().getText("jira.task.error.page.notfound"));
        }
        return null;
    }

    private I18NBean i18n() {
        return i18nBeanFactory.getI18NBean();
    }

    /**
     * 优先使用应用链接的显示地址，保证生成的链接在浏览器中可达；
     * 取不到时退回 Jira 响应中的 self 地址。
     */
    private String browseUrl(String self, String key) {
        String base = "";
        try {
            base = jira.getDisplayUrl();
        } catch (Exception e) {
            log.debug("Unable to resolve Jira display URL, falling back to self link", e);
        }
        if (base.isEmpty()) {
            base = self.replaceAll("/rest/api/.*$", "");
        }
        return base.isEmpty() ? "" : base + "/browse/" + key;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Response badRequest(String message) {
        return error(Response.Status.BAD_REQUEST, message);
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status)
                .entity(Collections.singletonMap("error", message)).build();
    }

    private static Response error(int status, String message) {
        return Response.status(status)
                .entity(Collections.singletonMap("error", message)).build();
    }

    private Response fail(Exception e) {
        if (e instanceof JiraClient.JiraException) {
            int status = ((JiraClient.JiraException) e).getStatus();
            int httpStatus = status == 400 ? 400 : (status == 403 ? 403 : 502);
            if (httpStatus == 502) {
                log.warn("Jira request failed with status {}", status, e);
            }
            return error(httpStatus, e.getMessage());
        }
        // 非 Jira 调用的异常不回显内部细节，但必须留下日志便于排查
        log.error("Unexpected error while calling Jira", e);
        return error(502, i18n().getText("jira.task.error.query"));
    }
}
