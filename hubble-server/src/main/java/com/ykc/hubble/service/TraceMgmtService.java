package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ykc.hubble.common.exception.BusinessException;
import com.ykc.hubble.dto.BusinessTraceDTO;
import com.ykc.hubble.dto.TraceNodeDTO;
import com.ykc.hubble.entity.BusinessTrace;
import com.ykc.hubble.entity.TraceNode;
import com.ykc.hubble.mapper.TraceMapper;
import com.ykc.hubble.mapper.TraceNodeMapper;
import com.ykc.hubble.util.VariableExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务链路管理服务
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceMgmtService {

    private final TraceMapper traceMapper;
    private final TraceNodeMapper traceNodeMapper;
    private final DictService dictService;
    private final VariableExtractor variableExtractor;

    /**
     * 创建业务链路
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createTrace(BusinessTraceDTO dto) {
        // 1. 验证业务分类是否存在
        if (!dictService.existsDictValue(DictService.DICT_TYPE_CATEGORY, dto.getCategory())) {
            throw new BusinessException(400, "业务分类不存在");
        }

        // 2. 验证业务名称唯一性（只检查未删除的记录）
        LambdaQueryWrapper<BusinessTrace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BusinessTrace::getName, dto.getName());
        wrapper.eq(BusinessTrace::getDeleted, 0);
        Long count = traceMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BusinessException(400, "业务名称已存在");
        }

        // 3. 验证节点名称在同一业务内唯一
        Set<String> nodeNames = dto.getNodes().stream()
                .map(TraceNodeDTO::getName)
                .collect(Collectors.toSet());
        if (nodeNames.size() != dto.getNodes().size()) {
            throw new BusinessException(400, "节点名称在同一业务内不能重复");
        }

        // 4. 验证节点数量限制（每一级节点不超过20个）
        validateNodeCount(dto.getNodes());

        // 5. 验证父子节点关系（只支持一级子节点）
        for (TraceNodeDTO node : dto.getNodes()) {
            if (node.getParentId() != null) {
                // parentId在创建时表示父节点在nodes数组中的索引
                if (node.getParentId() < 0 || node.getParentId() >= dto.getNodes().size()) {
                    throw new BusinessException(400, "节点[" + node.getName() + "]的父节点索引无效");
                }
                TraceNodeDTO parent = dto.getNodes().get(node.getParentId().intValue());
                // 确保父节点不是子节点（最多支持一级子节点）
                if (parent.getParentId() != null) {
                    throw new BusinessException(400, "最多只支持一级子节点，节点[" + node.getName() + "]的父节点不能是子节点");
                }
            }
        }

        // 6. 验证查询模板格式
        for (TraceNodeDTO node : dto.getNodes()) {
            if (!variableExtractor.validateTemplate(node.getQueryTemplate())) {
                throw new BusinessException(400, "节点[" + node.getName() + "]的查询模板格式错误");
            }
        }

        // 6. 保存业务链路
        BusinessTrace trace = new BusinessTrace();
        trace.setName(dto.getName());
        trace.setDescription(dto.getDescription());
        trace.setCategory(dto.getCategory());
        traceMapper.insert(trace);

        // 7. 保存节点（先保存顶级节点，再保存子节点）
        Long traceId = trace.getId();
        Map<Integer, Long> indexToIdMap = new HashMap<>();
        
        // 第一遍：保存所有顶级节点
        for (int i = 0; i < dto.getNodes().size(); i++) {
            TraceNodeDTO nodeDto = dto.getNodes().get(i);
            if (nodeDto.getParentId() == null) {
                TraceNode node = new TraceNode();
                node.setTraceId(traceId);
                node.setParentId(null);
                node.setName(nodeDto.getName());
                node.setDescription(nodeDto.getDescription());
                node.setSlsLogstore(nodeDto.getSlsLogstore());
                node.setQueryTemplate(nodeDto.getQueryTemplate());
                node.setNodeOrder(nodeDto.getNodeOrder());
                traceNodeMapper.insert(node);
                indexToIdMap.put(i, node.getId());
            }
        }
        
        // 第二遍：保存所有子节点
        for (int i = 0; i < dto.getNodes().size(); i++) {
            TraceNodeDTO nodeDto = dto.getNodes().get(i);
            if (nodeDto.getParentId() != null) {
                Long parentId = indexToIdMap.get(nodeDto.getParentId().intValue());
                if (parentId == null) {
                    throw new BusinessException(400, "节点[" + nodeDto.getName() + "]的父节点未找到");
                }
                TraceNode node = new TraceNode();
                node.setTraceId(traceId);
                node.setParentId(parentId);
                node.setName(nodeDto.getName());
                node.setDescription(nodeDto.getDescription());
                node.setSlsLogstore(nodeDto.getSlsLogstore());
                node.setQueryTemplate(nodeDto.getQueryTemplate());
                node.setNodeOrder(nodeDto.getNodeOrder());
                traceNodeMapper.insert(node);
            }
        }

        log.info("创建业务链路成功: id={}, name={}", traceId, dto.getName());
        return traceId;
    }

    /**
     * 分页查询业务链路列表
     */
    public Page<BusinessTrace> getTraceList(int page, int pageSize, String keyword, String categoryId) {
        Page<BusinessTrace> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<BusinessTrace> wrapper = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(BusinessTrace::getName, keyword);
        }

        if (categoryId != null && !categoryId.trim().isEmpty()) {
            wrapper.eq(BusinessTrace::getCategory, categoryId);
        }

        wrapper.orderByDesc(BusinessTrace::getCreatedAt);
        Page<BusinessTrace> result = traceMapper.selectPage(pageParam, wrapper);
        
        // 清空nodes，避免序列化时包含节点详情（节点数量在Controller中查询）
        result.getRecords().forEach(trace -> {
            trace.setNodes(null);
        });
        
        return result;
    }

    /**
     * 获取业务链路详情（只包含顶级节点列表）
     */
    public BusinessTrace getTraceDetail(Long id) {
        BusinessTrace trace = traceMapper.selectById(id);
        if (trace == null) {
            throw new BusinessException(404, "业务链路不存在");
        }

        // 查询关联的顶级节点（parent_id为null）
        LambdaQueryWrapper<TraceNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceNode::getTraceId, id);
        wrapper.isNull(TraceNode::getParentId);
        wrapper.orderByAsc(TraceNode::getNodeOrder);
        List<TraceNode> nodes = traceNodeMapper.selectList(wrapper);
        trace.setNodes(nodes);

        return trace;
    }

    /**
     * 获取业务链路所有节点（包含顶级节点和子节点，用于编辑）
     */
    public BusinessTrace getTraceDetailWithAllNodes(Long id) {
        BusinessTrace trace = traceMapper.selectById(id);
        if (trace == null) {
            throw new BusinessException(404, "业务链路不存在");
        }

        // 查询所有节点（包括子节点）
        LambdaQueryWrapper<TraceNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceNode::getTraceId, id);
        wrapper.orderByAsc(TraceNode::getNodeOrder);
        List<TraceNode> nodes = traceNodeMapper.selectList(wrapper);
        trace.setNodes(nodes);

        return trace;
    }

    /**
     * 获取节点的子节点列表
     */
    public List<TraceNode> getChildNodes(Long parentNodeId) {
        TraceNode parentNode = traceNodeMapper.selectById(parentNodeId);
        if (parentNode == null) {
            throw new BusinessException(404, "父节点不存在");
        }

        LambdaQueryWrapper<TraceNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceNode::getParentId, parentNodeId);
        wrapper.orderByAsc(TraceNode::getNodeOrder);
        return traceNodeMapper.selectList(wrapper);
    }

    /**
     * 检查节点是否有子节点
     */
    public boolean hasChildren(Long nodeId) {
        LambdaQueryWrapper<TraceNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceNode::getParentId, nodeId);
        Long count = traceNodeMapper.selectCount(wrapper);
        return count > 0;
    }

    /**
     * 从业务链路中提取所有查询变量
     */
    public List<String> extractVariables(Long traceId) {
        BusinessTrace trace = getTraceDetail(traceId);
        Set<String> allVariables = trace.getNodes().stream()
                .flatMap(node -> variableExtractor.extractVariables(node.getQueryTemplate()).stream())
                .collect(Collectors.toSet());
        return allVariables.stream().sorted().collect(Collectors.toList());
    }

    /**
     * 更新业务链路
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTrace(Long id, BusinessTraceDTO dto) {
        // 1. 检查业务链路是否存在
        BusinessTrace existingTrace = traceMapper.selectById(id);
        if (existingTrace == null) {
            throw new BusinessException(404, "业务链路不存在");
        }

        // 2. 验证业务分类是否存在
        if (!dictService.existsDictValue(DictService.DICT_TYPE_CATEGORY, dto.getCategory())) {
            throw new BusinessException(400, "业务分类不存在");
        }

        // 3. 如果名称变更，验证新名称唯一性（只检查未删除的记录）
        if (!existingTrace.getName().equals(dto.getName())) {
            LambdaQueryWrapper<BusinessTrace> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BusinessTrace::getName, dto.getName());
            wrapper.eq(BusinessTrace::getDeleted, 0);
            Long count = traceMapper.selectCount(wrapper);
            if (count > 0) {
                throw new BusinessException(400, "业务名称已存在");
            }
        }

        // 4. 验证节点名称唯一性
        Set<String> nodeNames = dto.getNodes().stream()
                .map(TraceNodeDTO::getName)
                .collect(Collectors.toSet());
        if (nodeNames.size() != dto.getNodes().size()) {
            throw new BusinessException(400, "节点名称在同一业务内不能重复");
        }

        // 5. 验证节点数量限制（每一级节点不超过20个）
        validateNodeCount(dto.getNodes());

        // 6. 验证父子节点关系（只支持一级子节点）
        for (TraceNodeDTO node : dto.getNodes()) {
            if (node.getParentId() != null) {
                // parentId在创建/更新时表示父节点在nodes数组中的索引
                if (node.getParentId() < 0 || node.getParentId() >= dto.getNodes().size()) {
                    throw new BusinessException(400, "节点[" + node.getName() + "]的父节点索引无效");
                }
                TraceNodeDTO parent = dto.getNodes().get(node.getParentId().intValue());
                // 确保父节点不是子节点（最多支持一级子节点）
                if (parent.getParentId() != null) {
                    throw new BusinessException(400, "最多只支持一级子节点，节点[" + node.getName() + "]的父节点不能是子节点");
                }
            }
        }

        // 7. 验证查询模板格式
        for (TraceNodeDTO node : dto.getNodes()) {
            if (!variableExtractor.validateTemplate(node.getQueryTemplate())) {
                throw new BusinessException(400, "节点[" + node.getName() + "]的查询模板格式错误");
            }
        }

        // 8. 更新业务链路
        existingTrace.setName(dto.getName());
        existingTrace.setDescription(dto.getDescription());
        existingTrace.setCategory(dto.getCategory());
        traceMapper.updateById(existingTrace);

        // 9. 物理删除旧节点（包括所有子节点）
        traceNodeMapper.physicalDeleteByTraceId(id);

        // 10. 保存新节点（先保存顶级节点，再保存子节点）
        Map<Integer, Long> indexToIdMap = new HashMap<>();
        
        // 第一遍：保存所有顶级节点
        for (int i = 0; i < dto.getNodes().size(); i++) {
            TraceNodeDTO nodeDto = dto.getNodes().get(i);
            if (nodeDto.getParentId() == null) {
                TraceNode node = new TraceNode();
                node.setTraceId(id);
                node.setParentId(null);
                node.setName(nodeDto.getName());
                node.setDescription(nodeDto.getDescription());
                node.setSlsLogstore(nodeDto.getSlsLogstore());
                node.setQueryTemplate(nodeDto.getQueryTemplate());
                node.setNodeOrder(nodeDto.getNodeOrder());
                traceNodeMapper.insert(node);
                indexToIdMap.put(i, node.getId());
            }
        }
        
        // 第二遍：保存所有子节点
        for (int i = 0; i < dto.getNodes().size(); i++) {
            TraceNodeDTO nodeDto = dto.getNodes().get(i);
            if (nodeDto.getParentId() != null) {
                Long parentId = indexToIdMap.get(nodeDto.getParentId().intValue());
                if (parentId == null) {
                    throw new BusinessException(400, "节点[" + nodeDto.getName() + "]的父节点未找到");
                }
                TraceNode node = new TraceNode();
                node.setTraceId(id);
                node.setParentId(parentId);
                node.setName(nodeDto.getName());
                node.setDescription(nodeDto.getDescription());
                node.setSlsLogstore(nodeDto.getSlsLogstore());
                node.setQueryTemplate(nodeDto.getQueryTemplate());
                node.setNodeOrder(nodeDto.getNodeOrder());
                traceNodeMapper.insert(node);
            }
        }

        log.info("更新业务链路成功: id={}, name={}", id, dto.getName());
    }

    /**
     * 删除业务链路（逻辑删除，级联逻辑删除节点）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTrace(Long id) {
        BusinessTrace trace = traceMapper.selectById(id);
        if (trace == null) {
            throw new BusinessException(404, "业务链路不存在");
        }

        // 逻辑删除是UPDATE操作，不会触发外键的ON DELETE CASCADE
        // 需要手动级联逻辑删除所有关联节点
        LambdaQueryWrapper<TraceNode> nodeWrapper = new LambdaQueryWrapper<>();
        nodeWrapper.eq(TraceNode::getTraceId, id);
        List<TraceNode> nodes = traceNodeMapper.selectList(nodeWrapper);
        
        // 逻辑删除所有节点
        for (TraceNode node : nodes) {
            traceNodeMapper.deleteById(node.getId());
        }
        
        // 逻辑删除链路
        traceMapper.deleteById(id);
        log.info("逻辑删除业务链路成功: id={}, name={}, 级联删除节点数={}", id, trace.getName(), nodes.size());
    }

    /**
     * 验证节点数量限制
     * 规则：每一级节点不允许超过20个，多个子节点分别计算
     * 例如：有两个顶级节点有子节点，这两个顶级节点下的子节点各允许20个
     *
     * @param nodes 节点列表
     */
    private void validateNodeCount(List<TraceNodeDTO> nodes) {
        // 1. 验证顶级节点数量（parentId为null的节点）
        List<TraceNodeDTO> topLevelNodes = nodes.stream()
                .filter(node -> node.getParentId() == null)
                .collect(Collectors.toList());
        
        if (topLevelNodes.size() > 20) {
            throw new BusinessException(400, "顶级节点数量不能超过20个，当前有" + topLevelNodes.size() + "个");
        }

        // 2. 验证每个父节点下的子节点数量
        // 注意：在创建/更新时，parentId表示父节点在nodes数组中的索引
        for (int i = 0; i < nodes.size(); i++) {
            TraceNodeDTO parentNode = nodes.get(i);
            // 只检查顶级节点（parentId为null）的子节点
            if (parentNode.getParentId() == null) {
                List<TraceNodeDTO> children = new ArrayList<>();
                for (TraceNodeDTO node : nodes) {
                    if (node.getParentId() != null && node.getParentId().equals((long) i)) {
                        children.add(node);
                    }
                }
                
                if (children.size() > 20) {
                    throw new BusinessException(400, 
                            "节点[" + parentNode.getName() + "]的子节点数量不能超过20个，当前有" + children.size() + "个");
                }
            }
        }
    }
}
