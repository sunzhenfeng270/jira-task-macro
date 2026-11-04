package com.tangzhi.confluence.jiratask;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.pages.Draft;
import com.atlassian.confluence.util.i18n.I18NBean;
import com.atlassian.confluence.util.i18n.I18NBeanFactory;
import com.atlassian.confluence.util.velocity.VelocityUtils;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.Map;

@Named
public class JiraTaskMacro implements Macro {

    private static final String TEMPLATE = "templates/jira-task-macro.vm";

    private final I18NBeanFactory i18nBeanFactory;

    @Inject
    public JiraTaskMacro(@ComponentImport I18NBeanFactory i18nBeanFactory) {
        this.i18nBeanFactory = i18nBeanFactory;
    }

    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context)
            throws MacroExecutionException {
        ContentEntityObject entity = context.getPageContext() == null
                ? null : context.getPageContext().getEntity();
        // 草稿/未保存页面没有有效 ID，置空避免前端带着无效 pageId 请求 REST 而 404
        String pageId = entity == null || entity instanceof Draft || entity.getId() <= 0
                ? "" : String.valueOf(entity.getId());

        Map<String, Object> model = new HashMap<>();
        model.put("pageId", pageId);
        model.put("pageTitle", TextUtils.escapeHtml(entity == null ? "" : entity.getTitle()));
        model.put("projectKey", TextUtils.escapeHtml(get(parameters, "projectKey")));
        model.put("issueTypeName", TextUtils.escapeHtml(get(parameters, "issueTypeName")));
        model.put("summaryPrefix", TextUtils.escapeHtml(get(parameters, "summaryPrefix")));
        model.put("labels", TextUtils.escapeHtml(get(parameters, "labels")));
        model.put("texts", texts(i18nBeanFactory.getI18NBean()));

        return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
    }

    private static Map<String, String> texts(I18NBean i18n) {
        Map<String, String> texts = new HashMap<>();
        texts.put("head", TextUtils.escapeHtml(i18n.getText("jira.task.head")));
        texts.put("loading", TextUtils.escapeHtml(i18n.getText("jira.task.loading")));
        texts.put("labelProject", TextUtils.escapeHtml(i18n.getText("jira.task.label.project")));
        texts.put("labelIssueType", TextUtils.escapeHtml(i18n.getText("jira.task.label.issuetype")));
        texts.put("labelSummary", TextUtils.escapeHtml(i18n.getText("jira.task.label.summary")));
        texts.put("labelDescription", TextUtils.escapeHtml(i18n.getText("jira.task.label.description")));
        texts.put("labelDueDate", TextUtils.escapeHtml(i18n.getText("jira.task.label.duedate")));
        texts.put("labelPriority", TextUtils.escapeHtml(i18n.getText("jira.task.label.priority")));
        texts.put("labelAssignee", TextUtils.escapeHtml(i18n.getText("jira.task.label.assignee")));
        texts.put("placeholderSummary", TextUtils.escapeHtml(i18n.getText("jira.task.placeholder.summary")));
        texts.put("placeholderDescription",
                TextUtils.escapeHtml(i18n.getText("jira.task.placeholder.description")));
        texts.put("placeholderAssignee",
                TextUtils.escapeHtml(i18n.getText("jira.task.placeholder.assignee")));
        texts.put("priorityDefault", TextUtils.escapeHtml(i18n.getText("jira.task.priority.default")));
        texts.put("search", TextUtils.escapeHtml(i18n.getText("jira.task.search")));
        texts.put("create", TextUtils.escapeHtml(i18n.getText("jira.task.create")));
        texts.put("creating", TextUtils.escapeHtml(i18n.getText("jira.task.creating")));
        texts.put("created", TextUtils.escapeHtml(i18n.getText("jira.task.created")));
        texts.put("remove", TextUtils.escapeHtml(i18n.getText("jira.task.remove")));
        texts.put("selectFirst", TextUtils.escapeHtml(i18n.getText("jira.task.select.first")));
        texts.put("select", TextUtils.escapeHtml(i18n.getText("jira.task.select")));
        texts.put("loadFailed", TextUtils.escapeHtml(i18n.getText("jira.task.load.failed")));
        texts.put("relatedTitle", TextUtils.escapeHtml(i18n.getText("jira.task.related.title")));
        texts.put("relatedTruncated", TextUtils.escapeHtml(i18n.getText("jira.task.related.truncated")));
        texts.put("due", TextUtils.escapeHtml(i18n.getText("jira.task.due")));
        texts.put("errorRequired", TextUtils.escapeHtml(i18n.getText("jira.task.error.required")));
        texts.put("errorProjects", TextUtils.escapeHtml(i18n.getText("jira.task.error.projects")));
        texts.put("errorIssueTypes", TextUtils.escapeHtml(i18n.getText("jira.task.error.issuetypes")));
        texts.put("errorUsers", TextUtils.escapeHtml(i18n.getText("jira.task.error.users")));
        texts.put("errorCreate", TextUtils.escapeHtml(i18n.getText("jira.task.error.create")));
        texts.put("errorRelated", TextUtils.escapeHtml(i18n.getText("jira.task.error.related")));
        texts.put("errorUnsaved", TextUtils.escapeHtml(i18n.getText("jira.task.error.unsaved")));
        return texts;
    }

    @Override
    public BodyType getBodyType() {
        return BodyType.NONE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.BLOCK;
    }

    private static String get(Map<String, String> parameters, String key) {
        String value = parameters.get(key);
        return value == null ? "" : value;
    }
}
