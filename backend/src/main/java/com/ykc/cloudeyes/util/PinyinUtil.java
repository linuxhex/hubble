package com.ykc.cloudeyes.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import lombok.extern.slf4j.Slf4j;

/**
 * 拼音转换工具类
 *
 * @author Cloud Eyes Team
 */
@Slf4j
public class PinyinUtil {

    private static final int MAX_LENGTH = 32;
    private static final HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();

    static {
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    /**
     * 将中文转换为小写拼音，并限制长度不超过32位
     *
     * @param text 原始文本
     * @return 转换后的拼音（小写，无空格，最长32位）
     */
    public static String toPinyin(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                // 中文字符转换为拼音
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, format);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        result.append(pinyinArray[0]);
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    log.warn("拼音转换失败: {}", c, e);
                }
            } else if (Character.isLetterOrDigit(c)) {
                // 字母和数字直接保留
                result.append(Character.toLowerCase(c));
            }
            // 其他字符（空格、特殊字符等）忽略
        }

        String pinyin = result.toString();

        // 限制长度不超过32位
        if (pinyin.length() > MAX_LENGTH) {
            pinyin = pinyin.substring(0, MAX_LENGTH);
        }

        return pinyin;
    }

    /**
     * 判断字符是否为中文
     *
     * @param c 字符
     * @return 是否为中文
     */
    private static boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }
}

