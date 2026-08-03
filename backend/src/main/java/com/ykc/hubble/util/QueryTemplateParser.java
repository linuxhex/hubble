package com.ykc.hubble.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询模板解析工具
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
public class QueryTemplateParser {

    /**
     * 变量占位符正则表达式
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    /**
     * 替换模板中的变量
     *
     * @param template 查询模板
     * @param variables 变量值Map
     * @return 替换后的查询语句
     */
    public String replaceVariables(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        String result = template;
        Matcher matcher = VARIABLE_PATTERN.matcher(template);

        while (matcher.find()) {
            String varName = matcher.group(1);
            String varValue = variables.get(varName);

            if (varValue == null) {
                log.warn("变量 {} 未提供值，使用空字符串", varName);
                varValue = "";
            } else {
                // 转义特殊字符，避免SLS查询语法错误
                varValue = escapeSpecialChars(varValue);
            }

            result = result.replace("{" + varName + "}", varValue);
        }

        return result;
    }

    /**
     * 转义特殊字符
     * SLS查询中的特殊字符需要转义：空格、引号、括号等
     */
    private String escapeSpecialChars(String value) {
        if (value == null) {
            return "";
        }

        // 如果值包含空格或特殊字符，使用引号包裹
        if (value.contains(" ") || value.contains("\"") || value.contains("'") 
            || value.contains("(") || value.contains(")") || value.contains(":")) {
            // 转义引号
            value = value.replace("\"", "\\\"");
            // 使用双引号包裹
            return "\"" + value + "\"";
        }

        return value;
    }
}
