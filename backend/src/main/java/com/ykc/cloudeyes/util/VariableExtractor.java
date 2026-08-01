package com.ykc.cloudeyes.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 变量提取工具
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
public class VariableExtractor {

    /**
     * 变量名正则表达式：{变量名}，变量名只能包含字母、数字和下划线
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    /**
     * 从查询模板中提取变量列表
     *
     * @param queryTemplate 查询模板
     * @return 变量名列表（去重，保持顺序）
     */
    public List<String> extractVariables(String queryTemplate) {
        if (queryTemplate == null || queryTemplate.isEmpty()) {
            return List.of();
        }

        Set<String> variableNames = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(queryTemplate);

        while (matcher.find()) {
            String varName = matcher.group(1);
            // 验证变量名格式（只包含字母、数字和下划线）
            if (varName.matches("[a-zA-Z0-9_]+")) {
                variableNames.add(varName);
            } else {
                log.warn("发现无效的变量名格式: {}", varName);
            }
        }

        return variableNames.stream().collect(Collectors.toList());
    }

    /**
     * 验证查询模板中的变量格式
     *
     * @param queryTemplate 查询模板
     * @return 是否有效
     */
    public boolean validateTemplate(String queryTemplate) {
        if (queryTemplate == null || queryTemplate.isEmpty()) {
            return false;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(queryTemplate);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!varName.matches("[a-zA-Z0-9_]+")) {
                return false;
            }
        }

        return true;
    }
}
