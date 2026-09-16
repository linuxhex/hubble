package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.dto.UserBehaviorTraceQueryDTO;
import com.ykc.hubble.entity.BusinessTrace;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.entity.NodeQueryResult;
import com.ykc.hubble.entity.TraceNode;
import com.ykc.hubble.util.UserBehaviorTraceParser;
import com.ykc.hubble.vo.UserBehaviorTraceItemVO;
import com.ykc.hubble.vo.UserBehaviorTraceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 业务链路查询服务
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceQueryService {

    private final TraceMgmtService traceService;
    private final SlsQueryClient slsService;
    private final UserBehaviorTraceParser traceParser;

    /**
     * 执行业务链路查询（只查询顶级节点）
     *
     * @param traceId 业务链路ID
     * @param variables 查询变量值
     * @param fromTime 开始时间（Unix时间戳，秒）
     * @param toTime 结束时间（Unix时间戳，秒）
     * @return 节点名称到查询结果的Map
     */
    public Map<String, NodeQueryResult> executeQuery(
            Long traceId,
            Map<String, String> variables,
            long fromTime,
            long toTime) {

        // 1. 获取业务链路配置（只包含顶级节点）
        BusinessTrace trace = traceService.getTraceDetail(traceId);
        List<TraceNode> nodes = trace.getNodes();

        // 2. 并行查询所有节点
        Map<String, NodeQueryResult> results = slsService.queryNodes(nodes, variables, fromTime, toTime);

        // 3. 设置nodeId、hasChildren和nodeOrder
        for (TraceNode node : nodes) {
            NodeQueryResult result = results.get(node.getName());
            if (result != null) {
                result.setNodeId(node.getId());
                result.setHasChildren(traceService.hasChildren(node.getId()));
                result.setNodeOrder(node.getNodeOrder());
            }
        }

        return results;
    }

    /**
     * 执行子节点查询
     *
     * @param parentNodeId 父节点ID
     * @param variables 查询变量值
     * @param fromTime 开始时间（Unix时间戳，秒）
     * @param toTime 结束时间（Unix时间戳，秒）
     * @return 节点名称到查询结果的Map
     */
    public Map<String, NodeQueryResult> executeChildNodesQuery(
            Long parentNodeId,
            Map<String, String> variables,
            long fromTime,
            long toTime) {

        // 1. 获取子节点列表
        List<TraceNode> childNodes = traceService.getChildNodes(parentNodeId);

        // 2. 并行查询所有子节点
        Map<String, NodeQueryResult> results = slsService.queryNodes(childNodes, variables, fromTime, toTime);

        // 3. 设置nodeId、hasChildren和nodeOrder（子节点不应该再有子节点）
        for (TraceNode node : childNodes) {
            NodeQueryResult result = results.get(node.getName());
            if (result != null) {
                result.setNodeId(node.getId());
                result.setHasChildren(false); // 子节点不再有子节点
                result.setNodeOrder(node.getNodeOrder());
            }
        }

        return results;
    }

    /**
     * 查询用户行为轨迹
     *
     * @param keyword 查询关键字
     * @param date 查询日期（yyyy-MM-dd格式），如果为空则使用timeRange
     * @param timeRange 时间范围（Unix时间戳，秒），如果指定了date则忽略此字段
     * @param offset 查询偏移量（用于分页）
     * @param limit 每页查询数量
     * @return 用户行为轨迹列表
     */
    public UserBehaviorTraceVO queryUserBehaviorTrace(
            String keyword,
            String date,
            UserBehaviorTraceQueryDTO.TimeRange timeRange,
            Integer offset,
            Integer limit) {

        // 1. 解析时间范围
        long fromTime;
        long toTime;
        
        if (date != null && !date.isEmpty()) {
            // 使用日期字符串
            LocalDate localDate = LocalDate.parse(date);
            LocalDateTime startDateTime = localDate.atStartOfDay();
            LocalDateTime endDateTime = localDate.atTime(23, 59, 59);
            
            fromTime = startDateTime.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
            toTime = endDateTime.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
        } else if (timeRange != null && timeRange.getFrom() != null && timeRange.getTo() != null) {
            // 使用时间范围
            fromTime = timeRange.getFrom();
            toTime = timeRange.getTo();
        } else {
            // 默认使用当天
            LocalDate today = LocalDate.now();
            LocalDateTime startDateTime = today.atStartOfDay();
            LocalDateTime endDateTime = today.atTime(23, 59, 59);
            
            fromTime = startDateTime.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
            toTime = endDateTime.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
        }

        // 2. 构建查询条件（不限定容器，在所有日志中搜索关键字；同时匹配 message 和容器名）
        String escapedKeyword = keyword.replace("\"", "\\\"");
        String query;
        if (escapedKeyword.matches("\\S+")) {
            // 单个词：同时匹配 message 和容器名（SLS tag 字段名不能加引号）
            query = String.format("message: \"%s\" or __tag__:_container_name_: %s",
                    escapedKeyword, escapedKeyword);
        } else {
            query = String.format("message: \"%s\"", escapedKeyword);
        }

        // 3. 验证时间戳
        if (fromTime < 0 || toTime < 0) {
            throw new IllegalArgumentException("时间戳不能为负数");
        }
        if (fromTime > Integer.MAX_VALUE || toTime > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("时间戳应为秒级，不应超过int最大值");
        }
        if (fromTime > toTime) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }

        // 4. 先获取总数（仅在首次查询时获取，offset=0时）
        long totalCount = 0;
        if (offset == null || offset == 0) {
            try {
                totalCount = slsService.countLogstore(
                        "all", // 使用固定的logstore "all"
                        query,
                        fromTime,
                        toTime
                );
            } catch (Exception e) {
                log.warn("获取总数失败，继续查询数据: {}", e.getMessage());
                // 如果获取总数失败，不影响数据查询，继续执行
            }
        }

        // 5. 执行SLS查询
        List<UserBehaviorTraceItemVO> items = new ArrayList<>();
        boolean hasMore = false;
        try {
            int queryLimit = (limit != null && limit > 0) ? limit : 100;
            int queryOffset = (offset != null && offset > 0) ? offset : 0;
            
            // 使用 SlsQueryClient 查询日志
            List<LogEntry> logs = slsService.queryLogstore(
                    "all", // 使用固定的logstore "all"
                    query,
                    fromTime,
                    toTime,
                    queryOffset,
                    queryLimit
            );

            // 6. 解析日志
            int parsedCount = 0;
            int filteredCount = 0;
            for (LogEntry logEntry : logs) {
                if (logEntry != null && logEntry.getMessage() != null) {
                    // 传递trace和time字段给解析器
                    UserBehaviorTraceItemVO item = traceParser.parse(
                            logEntry.getMessage(),
                            logEntry.getTime(),
                            logEntry.getLevel(),
                            logEntry.getContainerName(),
                            logEntry.getContainerIp()
                    );
                    if (item != null) {
                        items.add(item);
                        parsedCount++;
                    } else {
                        filteredCount++;
                    }
                }
            }
            if (logs.isEmpty()) {
                log.info("用户行为查询 SLS 返回空结果: keyword={}, from={}, to={}", keyword, fromTime, toTime);
            } else if (items.isEmpty()) {
                log.warn("用户行为查询 SLS 返回 {} 条日志但全部被解析器过滤: keyword={}", logs.size(), keyword);
            } else {
                log.info("用户行为查询解析完成: SLS返回{}条, 解析成功{}条, 过滤{}条", logs.size(), parsedCount, filteredCount);
            }

            // 7. 按dateTime升序排序
            items.sort(Comparator.comparing(UserBehaviorTraceItemVO::getDateTime));

            // 8. 判断是否还有更多数据
            if (totalCount > 0) {
                // 如果有总数，根据总数和已加载数量判断
                hasMore = (queryOffset + items.size()) < totalCount;
            } else {
                // 如果没有总数，根据返回的数据量判断（如果返回的数据量等于limit，可能还有更多）
                hasMore = items.size() >= queryLimit;
            }

        } catch (Exception e) {
            log.error("查询用户行为轨迹失败: keyword={}, fromTime={}, toTime={}, offset={}, limit={}", 
                    keyword, fromTime, toTime, offset, limit, e);
            throw new RuntimeException("查询用户行为轨迹失败: " + e.getMessage(), e);
        }

        // 9. 构建返回结果
        UserBehaviorTraceVO result = new UserBehaviorTraceVO();
        result.setItems(items);
        // 如果有总数，使用总数；否则使用当前已加载的数量
        result.setTotal(totalCount > 0 ? (int) totalCount : items.size());
        result.setHasMore(hasMore);

        return result;
    }
}
