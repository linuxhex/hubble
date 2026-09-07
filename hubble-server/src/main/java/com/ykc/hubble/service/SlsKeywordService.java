package com.ykc.hubble.service;

import com.ykc.hubble.client.AliyunEmbeddingClient;
import com.ykc.hubble.common.exception.BusinessException;
import com.ykc.hubble.config.MilvusConfig;
import com.ykc.hubble.dto.SlsKeywordDTO;
import com.ykc.hubble.entity.SlsKeyword;
import com.ykc.hubble.mapper.SlsKeywordRepo;
import com.ykc.hubble.vo.SlsKeywordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SLS关键字模版服务
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlsKeywordService {

    private final SlsKeywordRepo slsKeywordRepo;
    private final MilvusConfig milvusConfig;
    private final AliyunEmbeddingClient embeddingService;
    private final DictService dictService;

    /**
     * 验证向量维度
     */
    private void validateVectorDimension(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new BusinessException(400, "描述向量不能为空");
        }
        if (vector.size() != milvusConfig.getVectorDimension()) {
            throw new BusinessException(400, 
                    String.format("向量维度不匹配，期望维度: %d, 实际维度: %d", 
                            milvusConfig.getVectorDimension(), vector.size()));
        }
    }

    /**
     * 创建SLS关键字模版
     */
    public String createSlsKeyword(SlsKeywordDTO dto) {
        // 如果未提供向量，则从desc自动生成
        List<Float> descVector = dto.getDescVector();
        if (descVector == null || descVector.isEmpty()) {
            log.info("未提供描述向量，从desc自动生成向量");
            descVector = embeddingService.textToVector(dto.getDesc());
        }
        
        // 验证向量维度
        validateVectorDimension(descVector);
        
        // 生成ID
        String id = UUID.randomUUID().toString();
        
        // 转换为实体
        SlsKeyword slsKeyword = new SlsKeyword();
        slsKeyword.setId(id);
        slsKeyword.setDesc(dto.getDesc());
        slsKeyword.setDescVector(descVector);
        slsKeyword.setKeywords(dto.getKeywords());
        slsKeyword.setApplication(dto.getApplication());
        slsKeyword.setLogstore(dto.getLogstore());
        slsKeyword.setTags(dto.getTags());

        // 插入数据
        slsKeywordRepo.insert(slsKeyword);
        
        log.info("创建SLS关键字模版成功: id={}", id);
        return id;
    }

    /**
     * 分页查询SLS关键字模版列表
     */
    public Map<String, Object> getSlsKeywordList(int page, int pageSize, String desc, String application, String tag) {
        // 使用Milvus分页查询
        Map<String, Object> queryResult = slsKeywordRepo.selectAllWithPagination(page, pageSize, desc, application, tag);
        
        @SuppressWarnings("unchecked")
        List<SlsKeyword> pageList = (List<SlsKeyword>) queryResult.get("list");
        Long total = (Long) queryResult.get("total");

        // 转换为VO（处理空列表情况）
        List<SlsKeywordVO> voList = (pageList != null) ? pageList.stream().map(slsKeyword -> {
            SlsKeywordVO vo = new SlsKeywordVO();
            BeanUtils.copyProperties(slsKeyword, vo);
            return vo;
        }).collect(Collectors.toList()) : new ArrayList<>();
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("list", voList);
        return result;
    }

    /**
     * 根据ID查询SLS关键字模版详情
     */
    public SlsKeywordVO getSlsKeywordDetail(String id) {
        SlsKeyword slsKeyword = slsKeywordRepo.selectById(id);
        if (slsKeyword == null) {
            throw new BusinessException(404, "模版不存在");
        }
        
        SlsKeywordVO vo = new SlsKeywordVO();
        BeanUtils.copyProperties(slsKeyword, vo);
        return vo;
    }

    /**
     * 更新SLS关键字模版
     */
    public void updateSlsKeyword(String id, SlsKeywordDTO dto) {
        // 检查模版是否存在
        SlsKeyword existing = slsKeywordRepo.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "模版不存在");
        }
        
        // 确定使用的向量
        List<Float> descVector;
        
        // 检查描述是否变化（处理null情况）
        String existingDesc = existing.getDesc();
        String newDesc = dto.getDesc();
        boolean descChanged = (existingDesc == null && newDesc != null) 
                || (existingDesc != null && !existingDesc.equals(newDesc));
        
        if (descChanged) {
            // 描述有变化，重新生成向量
            log.info("描述已变更，重新生成向量");
            descVector = embeddingService.textToVector(dto.getDesc());
            // 验证向量维度
            validateVectorDimension(descVector);
        } else {
            // 描述未变化，使用旧的向量
            log.info("描述未变更，复用原有向量");
            descVector = existing.getDescVector();
        }
        
        // 删除旧数据
        slsKeywordRepo.deleteById(id);
        
        // 创建新数据（保持原ID）
        SlsKeyword slsKeyword = new SlsKeyword();
        slsKeyword.setId(id);
        slsKeyword.setDesc(dto.getDesc());
        slsKeyword.setDescVector(descVector);
        slsKeyword.setKeywords(dto.getKeywords());
        slsKeyword.setApplication(dto.getApplication());
        slsKeyword.setLogstore(dto.getLogstore());
        slsKeyword.setTags(dto.getTags());

        slsKeywordRepo.insert(slsKeyword);
        
        log.info("更新SLS关键字模版成功: id={}", id);
    }

    /**
     * 删除SLS关键字模版
     */
    public void deleteSlsKeyword(String id) {
        SlsKeyword existing = slsKeywordRepo.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "模版不存在");
        }
        
        slsKeywordRepo.deleteById(id);
        log.info("删除SLS关键字模版成功: id={}", id);
    }

    /**
     * 获取应用列表（从字典中获取）
     */
    public List<String> getApplicationList() {
        return dictService.getDictValues(DictService.DICT_TYPE_APPLICATION);
    }

    /**
     * 获取标签列表（从字典中获取）
     */
    public List<String> getTagList() {
        return dictService.getDictValues(DictService.DICT_TYPE_TAG);
    }

    /**
     * 新增应用
     *
     * @param application 应用名称（展示值自动使用应用名称）
     */
    public void addApplication(String application) {
        dictService.addDictValue(DictService.DICT_TYPE_APPLICATION, application, null);
    }

    /**
     * 新增标签
     *
     * @param tag 标签名称（展示值自动使用标签名称）
     */
    public void addTag(String tag) {
        dictService.addDictValue(DictService.DICT_TYPE_TAG, tag, null);
    }
}
