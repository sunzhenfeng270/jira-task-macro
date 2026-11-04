package com.tangzhi.confluence.jiratask;

import com.atlassian.applinks.api.ApplicationLink;
import com.atlassian.applinks.api.ApplicationLinkRequest;
import com.atlassian.applinks.api.ApplicationLinkRequestFactory;
import com.atlassian.applinks.api.ApplicationLinkService;
import com.atlassian.applinks.api.CredentialsRequiredException;
import com.atlassian.applinks.api.application.jira.JiraApplicationType;
import com.atlassian.confluence.util.i18n.I18NBean;
import com.atlassian.confluence.util.i18n.I18NBeanFactory;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.net.Request;
import com.atlassian.sal.api.net.Response;
import com.atlassian.sal.api.net.ResponseException;
import com.atlassian.sal.api.net.ResponseHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Named
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SO_TIMEOUT_MS = 30_000;
    private static final int MAX_ERROR_BODY = 300;

    private final ApplicationLinkService applicationLinkService;
    private final I18NBeanFactory i18nBeanFactory;

    @Inject
    public JiraClient(@ComponentImport ApplicationLinkService applicationLinkService,
                      @ComponentImport I18NBeanFactory i18nBeanFactory) {
        this.applicationLinkService = applicationLinkService;
        this.i18nBeanFactory = i18nBeanFactory;
    }

    public JsonNode get(String pathAndQuery) throws IOException {
        return execute(Request.MethodType.GET, pathAndQuery, null,
                CONNECT_TIMEOUT_MS, SO_TIMEOUT_MS);
    }

    /**
     * 用于不应阻塞页面渲染的轻量查询（如字段列表探测），连接与读取超时都更短。
     */
    public JsonNode get(String pathAndQuery, int timeoutMs) throws IOException {
        return execute(Request.MethodType.GET, pathAndQuery, null, timeoutMs, timeoutMs);
    }

    public JsonNode post(String path, Object payload) throws IOException {
        return execute(Request.MethodType.POST, path, MAPPER.writeValueAsString(payload),
                CONNECT_TIMEOUT_MS, SO_TIMEOUT_MS);
    }

    /**
     * 返回浏览器访问 Jira 使用的基础地址（应用链接的显示地址）。
     * RPC 地址可能指向内网，不能直接拼给用户点击，因此浏览链接必须用显示地址。
     */
    public String getDisplayUrl() throws IOException {
        return trimSlash(resolveJiraLink(i18nBeanFactory.getI18NBean()).getDisplayUrl().toString());
    }

    /**
     * 汇总应用链接信息，供诊断接口在离线环境确认请求实际发往哪里。
     */
    public Map<String, String> describeLink() throws IOException {
        ApplicationLink link = resolveJiraLink(i18nBeanFactory.getI18NBean());
        ApplicationLink primary =
                applicationLinkService.getPrimaryApplicationLink(JiraApplicationType.class);
        Map<String, String> info = new LinkedHashMap<>();
        info.put("name", link.getName());
        info.put("rpcUrl", trimSlash(link.getRpcUrl().toString()));
        info.put("displayUrl", trimSlash(link.getDisplayUrl().toString()));
        info.put("primary", String.valueOf(link.equals(primary)));
        return info;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private JsonNode execute(Request.MethodType method, String pathAndQuery, String body,
                             int connectTimeoutMs, int soTimeoutMs) throws IOException {
        I18NBean i18n = i18nBeanFactory.getI18NBean();
        ApplicationLink link = resolveJiraLink(i18n);
        // 机器调用应使用 RPC URL；当显示 URL 与 RPC URL 不同（如反向代理/DC 部署）时更可靠
        String base = link.getRpcUrl().toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String path = pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery;
        String url = base + path;

        final int[] status = {0};
        final String[] responseBody = {""};
        long started = System.currentTimeMillis();
        try {
            ApplicationLinkRequestFactory factory = link.createAuthenticatedRequestFactory();
            ApplicationLinkRequest request = factory.createRequest(method, url);
            request.setHeader("Accept", "application/json");
            request.setConnectionTimeout(connectTimeoutMs);
            request.setSoTimeout(soTimeoutMs);
            if (body != null) {
                request.setHeader("Content-Type", "application/json");
                request.setRequestBody(body);
            }
            // 使用 handle 版本以便拿到 Jira 的错误响应体，便于把真实原因透出到界面
            request.execute(new ResponseHandler<Response>() {
                @Override
                public void handle(Response response) throws ResponseException {
                    status[0] = response.getStatusCode();
                    responseBody[0] = response.getResponseBodyAsString();
                }
            });
        } catch (CredentialsRequiredException e) {
            log.warn("Jira {} {} requires re-authorisation after {} ms", method, url,
                    System.currentTimeMillis() - started, e);
            throw new JiraException(i18n.getText("jira.task.error.credentials"), e, 0);
        } catch (ResponseException e) {
            if (status[0] == 0) {
                log.warn("Jira {} {} failed after {} ms", method, url,
                        System.currentTimeMillis() - started, e);
                throw new JiraException(i18n.getText("jira.task.error.jira",
                        new Object[]{e.getMessage()}), e, 0);
            }
            log.debug("Jira {} {} reported a response but threw", method, url, e);
        }

        long elapsed = System.currentTimeMillis() - started;
        if (status[0] >= 400) {
            // 离线环境需要完整证据：地址、耗时、状态码与 Jira 原始响应体
            log.warn("Jira {} {} -> HTTP {} in {} ms, response: {}", method, url, status[0],
                    elapsed, abbreviate(responseBody[0]));
            throw new JiraException(i18n.getText("jira.task.error.jira",
                    new Object[]{describe(status[0], responseBody[0])}), status[0]);
        }
        log.debug("Jira {} {} -> HTTP {} in {} ms", method, url, status[0], elapsed);

        String response = responseBody[0];
        if (response == null || response.trim().isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(response);
        } catch (Exception e) {
            log.warn("Jira {} {} returned a non-JSON response in {} ms: {}", method, url, elapsed,
                    abbreviate(response), e);
            throw new JiraException(i18n.getText("jira.task.error.jira",
                    new Object[]{"HTTP " + status[0]}), status[0]);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "…" : text;
    }

    private ApplicationLink resolveJiraLink(I18NBean i18n) throws IOException {
        ApplicationLink link = applicationLinkService.getPrimaryApplicationLink(JiraApplicationType.class);
        if (link != null) {
            return link;
        }
        Iterator<ApplicationLink> iterator =
                applicationLinkService.getApplicationLinks(JiraApplicationType.class).iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        throw new JiraException(i18n.getText("jira.task.error.link"), 0);
    }

    private static String describe(int status, String body) {
        String detail = parseError(body);
        return detail.isEmpty() ? "HTTP " + status : "HTTP " + status + " " + detail;
    }

    /**
     * 从 Jira 的 {"errorMessages": [...], "errors": {...}} 响应中提取可读的错误原因。
     */
    private static String parseError(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            List<String> messages = new ArrayList<>();
            JsonNode errorMessages = node.path("errorMessages");
            if (errorMessages.isArray()) {
                for (JsonNode message : errorMessages) {
                    String text = message.asText("");
                    if (!text.isEmpty()) {
                        messages.add(text);
                    }
                }
            }
            JsonNode errors = node.path("errors");
            if (errors.isObject()) {
                Iterator<String> names = errors.fieldNames();
                while (names.hasNext()) {
                    String field = names.next();
                    messages.add(field + ": " + errors.path(field).asText(""));
                }
            }
            if (!messages.isEmpty()) {
                return String.join("; ", messages);
            }
            return node.path("message").asText("");
        } catch (Exception ignored) {
            String text = body.trim();
            return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "…" : text;
        }
    }

    /**
     * 携带 Jira 返回的 HTTP 状态码，便于上层把 400/403 等业务错误映射为合适的响应。
     */
    public static class JiraException extends IOException {

        private final int status;

        public JiraException(String message, int status) {
            super(message);
            this.status = status;
        }

        public JiraException(String message, Throwable cause, int status) {
            super(message, cause);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
