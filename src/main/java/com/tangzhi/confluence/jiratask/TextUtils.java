package com.tangzhi.confluence.jiratask;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 插件内共用的文本处理工具，避免各宏重复实现转义与空值处理。
 */
public final class TextUtils {

    private TextUtils() {
    }

    public static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static String encode(String value) {
        return URLEncoder.encode(trim(value), StandardCharsets.UTF_8);
    }

    /**
     * 转义 HTML 文本及双引号包裹的属性值。
     */
    public static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
