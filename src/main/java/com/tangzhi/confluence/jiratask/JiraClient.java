package com.tangzhi.confluence.jiratask;

import com.atlassian.applinks.api.ApplicationLink;
import com.atlassian.applinks.api.ApplicationLinkRequest;
import com.atlassian.applinks.api.ApplicationLinkRequestFactory;
import com.atlassian.applinks.api.ApplicationLinkService;
import com.atlassian.applinks.api.CredentialsRequiredException;
import com.atlassian.applinks.api.application.jira.JiraApplicationType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.net.Request;
import com.atlassian.sal.api.net.ResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.Iterator;

@Named
public class JiraClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApplicationLinkService applicationLinkService;

    @Inject
    public JiraClient(@ComponentImport ApplicationLinkService applicationLinkService) {
        this.applicationLinkService = applicationLinkService;
    }

    public JsonNode get(String pathAndQuery) throws IOException {
        return execute(Request.MethodType.GET, pathAndQuery, null);
    }

    public JsonNode post(String path, Object payload) throws IOException {
        return execute(Request.MethodType.POST, path, MAPPER.writeValueAsString(payload));
    }

    private JsonNode execute(Request.MethodType method, String pathAndQuery, String body) throws IOException {
        ApplicationLink link = resolveJiraLink();
        String base = link.getDisplayUrl().toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String path = pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery;

        try {
            ApplicationLinkRequestFactory factory = link.createAuthenticatedRequestFactory();
            ApplicationLinkRequest request = factory.createRequest(method, base + path);
            request.addHeader("Accept", "application/json");
            if (body != null) {
                request.addHeader("Content-Type", "application/json");
                request.setRequestBody(body);
            }
            String response = request.execute();
            if (response == null || response.trim().isEmpty()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(response);
        } catch (CredentialsRequiredException e) {
            throw new IOException("应用链接需要重新授权，请到「设置 → 应用 → 应用链接」中重新授权该 Jira 链接", e);
        } catch (ResponseException e) {
            throw new IOException("Jira 请求失败：" + e.getMessage(), e);
        }
    }

    private ApplicationLink resolveJiraLink() {
        ApplicationLink link = applicationLinkService.getPrimaryApplicationLink(JiraApplicationType.class);
        if (link != null) {
            return link;
        }
        Iterator<ApplicationLink> iterator =
                applicationLinkService.getApplicationLinks(JiraApplicationType.class).iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        throw new IllegalStateException(
                "未配置到 Jira 的应用链接，请在「设置 → 应用 → 应用链接」中创建 Jira 链路");
    }
}
