package com.tangzhi.confluence.jiratask;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.util.velocity.VelocityUtils;

import javax.inject.Named;
import java.util.HashMap;
import java.util.Map;

@Named
public class JiraTaskMacro implements Macro {

    private static final String TEMPLATE = "templates/jira-task-macro.vm";

    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context)
            throws MacroExecutionException {
        ContentEntityObject entity = context.getPageContext() == null
                ? null : context.getPageContext().getEntity();

        Map<String, Object> model = new HashMap<>();
        model.put("pageId", entity == null ? "" : String.valueOf(entity.getId()));
        model.put("pageTitle", escape(entity == null ? "" : entity.getTitle()));
        model.put("projectKey", escape(get(parameters, "projectKey")));
        model.put("issueTypeName", escape(get(parameters, "issueTypeName")));
        model.put("summaryPrefix", escape(get(parameters, "summaryPrefix")));
        model.put("labels", escape(get(parameters, "labels")));

        return VelocityUtils.getRenderedTemplate(TEMPLATE, model);
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
