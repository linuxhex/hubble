package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.hubble.common.exception.BusinessException;
import com.ykc.hubble.entity.SysDict;
import com.ykc.hubble.mapper.DictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字典服务
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictService {

    private final DictMapper dictMapper;

    /**
     * 字典类型常量
     */
    public static final String DICT_TYPE_APPLICATION = "application";
    public static final String DICT_TYPE_TAG = "sls_tag";
    public static final String DICT_TYPE_CATEGORY = "business_category";

    /**
     * 根据字典类型获取字典值列表（返回展示值，如果展示值为空则返回字典值）
     *
     * @param dictType 字典类型
     * @return 字典值列表
     */
    public List<String> getDictValues(String dictType) {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getDictType, dictType)
                .orderByAsc(SysDict::getDictValue);
        List<SysDict> dicts = dictMapper.selectList(wrapper);
        return dicts.stream()
                .map(dict -> {
                    // 如果有展示值则使用展示值，否则使用字典值
                    String label = dict.getDictLabel();
                    return StringUtils.hasText(label) ? label : dict.getDictValue();
                })
                .filter(value -> value != null && !value.trim().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 添加字典值
     *
     * @param dictType 字典类型
     * @param dictValue 字典值
     * @param dictLabel 字典展示值（可选）
     */
    @Transactional(rollbackFor = Exception.class)
    public void addDictValue(String dictType, String dictValue, String dictLabel) {
        if (!StringUtils.hasText(dictValue)) {
            throw new BusinessException(400, "字典值不能为空");
        }

        // 检查是否已存在
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getDictType, dictType)
                .eq(SysDict::getDictValue, dictValue.trim());
        Long count = dictMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BusinessException(400, "字典值已存在");
        }

        // 创建字典
        SysDict dict = new SysDict();
        dict.setDictType(dictType);
        dict.setDictValue(dictValue.trim());
        // 如果展示值为空，默认使用字典值
        dict.setDictLabel(StringUtils.hasText(dictLabel) ? dictLabel.trim() : dictValue.trim());
        dict.setCreatedAt(LocalDateTime.now());
        dict.setUpdatedAt(LocalDateTime.now());
        dictMapper.insert(dict);

        log.info("添加字典值成功: type={}, value={}, label={}", dictType, dictValue, 
                StringUtils.hasText(dictLabel) ? dictLabel : dictValue);
    }

    /**
     * 删除字典值
     *
     * @param dictType 字典类型
     * @param dictValue 字典值
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictValue(String dictType, String dictValue) {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getDictType, dictType)
                .eq(SysDict::getDictValue, dictValue);
        dictMapper.delete(wrapper);
        log.info("删除字典值成功: type={}, value={}", dictType, dictValue);
    }

    /**
     * 检查字典值是否存在
     *
     * @param dictType 字典类型
     * @param dictValue 字典值
     * @return 是否存在
     */
    public boolean existsDictValue(String dictType, String dictValue) {
        if (!StringUtils.hasText(dictValue)) {
            return false;
        }
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getDictType, dictType)
                .eq(SysDict::getDictValue, dictValue.trim());
        Long count = dictMapper.selectCount(wrapper);
        return count > 0;
    }

    /**
     * 根据字典类型和字典值获取展示值
     *
     * @param dictType 字典类型
     * @param dictValue 字典值
     * @return 展示值（如果展示值为空则返回字典值）
     */
    public String getDictLabel(String dictType, String dictValue) {
        if (!StringUtils.hasText(dictValue)) {
            return null;
        }
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDict::getDictType, dictType)
                .eq(SysDict::getDictValue, dictValue.trim());
        SysDict dict = dictMapper.selectOne(wrapper);
        if (dict == null) {
            return null;
        }
        return StringUtils.hasText(dict.getDictLabel()) ? dict.getDictLabel() : dict.getDictValue();
    }
}

