package com.tangzhi.confluence.jiratask.rest;

import com.tangzhi.confluence.jiratask.JiraClient;
import com.fasterxml.jackson.databind.JsonNode;

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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Named
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class JiraTaskResource {

    private final JiraClient jira;

    @Inject
    public JiraTaskResource(JiraClient jira) {
        this.jira = jira;
    }

    @GET
    @Path("/projects")
    public Response projects(@QueryParam("query") String query) {
        try {
            JsonNode data = jira.get("/rest/api/2/project");
            List<Map<String, String>> result = new ArrayList<>();
            String q = query == null ? "" : query.toLowerCase();
            if (data.isArray()) {
                for (JsonNode project : data) {
                    String key = project.path("key").asText("");
                    String name = project.path("name").asText("");
                    if (q.isEmpty()
                            || key.toLowerCase().contains(q)
                            || name.toLowerCase().contains(q)) {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("key", key);
                        item.put("name", name);
                        result.add(item);
                    }
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
        if (projectKey == null || projectKey.isEmpty()) {
            return badRequest("缺少 projectKey");
        }
        try {
            // Jira 9.x removed the bulk createmeta endpoint; issue types are now
            // returned per project and paged (values/total).
            List<Map<String, String>> result = new ArrayList<>();
            int startAt = 0;
            while (true) {
                JsonNode data = jira.get("/rest/api/2/issue/createmeta/" + encode(projectKey)
                        + "/issuetypes?startAt=" + startAt + "&maxResults=50");
                JsonNode values = data.path("values");
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
                if (values.size() == 0 || startAt >= data.path("total").asInt(0)) {
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
        if (query == null || query.isEmpty()) {
            return Response.ok(Collections.emptyList()).build();
        }
        try {
            JsonNode data = jira.get("/rest/api/2/user/assignable/search?project="
                    + encode(projectKey == null ? "" : projectKey)
                    + "&username=" + encode(query) + "&maxResults=10");
            List<Map<String, String>> result = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode user : data) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("name", user.path("name").asText(""));
                    item.put("displayName", user.path("displayName").asText(""));
                    item.put("email", user.path("emailAddress").asText(""));
                    result.add(item);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @POST
    @Path("/issues")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createIssue(Map<String, Object> payload) {
        String projectKey = str(payload.get("projectKey"));
        String issueTypeName = str(payload.get("issueTypeName"));
        String summary = str(payload.get("summary"));
        if (projectKey.isEmpty() || issueTypeName.isEmpty() || summary.isEmpty()) {
            return badRequest("项目、任务类型和标题为必填项");
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
        if (!assigneeName.isEmpty()) {
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
        String pageId = str(payload.get("pageId"));
        if (!pageId.isEmpty()) {
            labels.add("confluence-page-" + pageId);
        }
        if (!labels.isEmpty()) {
            fields.put("labels", new ArrayList<>(labels));
        }

        try {
            JsonNode created = jira.post("/rest/api/2/issue",
                    Collections.singletonMap("fields", fields));
            String key = created.path("key").asText("");
            Map<String, String> result = new LinkedHashMap<>();
            result.put("id", created.path("id").asText(""));
            result.put("key", key);
            result.put("url", browseUrl(created.path("self").asText(""), key));
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GET
    @Path("/issues")
    public Response pageIssues(@QueryParam("pageId") String pageId) {
        if (pageId == null || pageId.isEmpty()) {
            return Response.ok(Collections.emptyList()).build();
        }
        String jql = "labels = \"confluence-page-" + pageId + "\" ORDER BY created DESC";
        try {
            JsonNode data = jira.get("/rest/api/2/search?jql=" + encode(jql)
                    + "&maxResults=20&fields=summary,status,assignee,duedate,issuetype");
            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode issue : data.path("issues")) {
                Map<String, String> item = new LinkedHashMap<>();
                String key = issue.path("key").asText("");
                item.put("key", key);
                item.put("summary", issue.path("fields").path("summary").asText(""));
                item.put("status", issue.path("fields").path("status").path("name").asText(""));
                item.put("type", issue.path("fields").path("issuetype").path("name").asText(""));
                item.put("assignee", issue.path("fields").path("assignee").path("displayName").asText(""));
                item.put("dueDate", issue.path("fields").path("duedate").asText(""));
                item.put("url", browseUrl(issue.path("self").asText(""), key));
                result.add(item);
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    private static String browseUrl(String self, String key) {
        String base = self.replaceAll("/rest/api/.*$", "");
        return base.isEmpty() ? "" : base + "/browse/" + key;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Collections.singletonMap("error", message)).build();
    }

    private static Response fail(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Map<String, String> body = new HashMap<>();
        body.put("error", message);
        return Response.status(502).entity(body).build();
    }
}
