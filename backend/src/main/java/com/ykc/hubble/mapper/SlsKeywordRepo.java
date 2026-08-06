package com.ykc.hubble.mapper;

import com.ykc.hubble.common.exception.BusinessException;
import com.ykc.hubble.config.MilvusConfig;
import com.ykc.hubble.entity.SlsKeyword;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * SLS关键字模版Repository
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Repository
public class SlsKeywordRepo {

    private MilvusServiceClient milvusClient;
    private final MilvusConfig milvusConfig;

    @Autowired(required = false)
    public void setMilvusClient(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    public SlsKeywordRepo(MilvusConfig milvusConfig) {
        this.milvusConfig = milvusConfig;
    }

    /**
     * Collection名称常量
     */
    private static final String COLLECTION_NAME = "sls_keyword";

    private static final String ID_FIELD = "id";
    private static final String DESC_FIELD = "desc";
    private static final String DESC_VECTOR_FIELD = "desc_vector";
    private static final String KEYWORDS_FIELD = "keywords";
    private static final String APPLICATION_FIELD = "application";
    private static final String LOGSTORE_FIELD = "logstore";
    private static final String TAGS_FIELD = "tags";

    /**
     * 初始化Collection（如果不存在则创建）
     */
    public void initCollection() {
        if (milvusClient == null) {
            log.debug("Milvus客户端未初始化，跳过Collection初始化");
            return;
        }
        try {
            // 检查Collection是否存在
            HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .build();
            R<Boolean> hasCollection = milvusClient.hasCollection(hasCollectionParam);
            
            if (!hasCollection.getData()) {
                // 创建Collection
                FieldType idField = FieldType.newBuilder()
                        .withName(ID_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(255)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build();
                
                FieldType descField = FieldType.newBuilder()
                        .withName(DESC_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(500)
                        .build();
                
                FieldType descVectorField = FieldType.newBuilder()
                        .withName(DESC_VECTOR_FIELD)
                        .withDataType(DataType.FloatVector)
                        .withDimension(milvusConfig.getVectorDimension())
                        .build();
                
                FieldType keywordsField = FieldType.newBuilder()
                        .withName(KEYWORDS_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(1000)
                        .build();
                
                FieldType applicationField = FieldType.newBuilder()
                        .withName(APPLICATION_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(100)
                        .build();
                
                FieldType logstoreField = FieldType.newBuilder()
                        .withName(LOGSTORE_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(200)
                        .build();
                
                FieldType tagsField = FieldType.newBuilder()
                        .withName(TAGS_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(500)
                        .build();
                
                CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withDescription("SLS关键字模版Collection")
                        .withShardsNum(2)
                        .addFieldType(idField)
                        .addFieldType(descField)
                        .addFieldType(descVectorField)
                        .addFieldType(keywordsField)
                        .addFieldType(applicationField)
                        .addFieldType(logstoreField)
                        .addFieldType(tagsField)
                        .build();
                
                R<?> createCollection = milvusClient.createCollection(createCollectionParam);
                if (createCollection.getStatus() != R.Status.Success.getCode()) {
                    throw new BusinessException(500, "创建Collection失败: " + createCollection.getMessage());
                }
                
                // 创建索引
                CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withFieldName(DESC_VECTOR_FIELD)
                        .withIndexType(IndexType.HNSW)
                        .withMetricType(MetricType.L2)
                        .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                        .build();
                
                R<?> createIndex = milvusClient.createIndex(createIndexParam);
                if (createIndex.getStatus() != R.Status.Success.getCode()) {
                    log.warn("创建索引失败: {}", createIndex.getMessage());
                }
                
                // 加载Collection
                LoadCollectionParam loadCollectionParam = LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build();
                R<?> loadCollection = milvusClient.loadCollection(loadCollectionParam);
                if (loadCollection.getStatus() != R.Status.Success.getCode()) {
                    log.warn("加载Collection失败: {}", loadCollection.getMessage());
                }
                
                log.info("Collection创建成功: {}", COLLECTION_NAME);
            } else {
                log.debug("Collection已存在: {}", COLLECTION_NAME);
            }
        } catch (Exception e) {
            log.error("Collection初始化失败: {}", COLLECTION_NAME, e);
            throw new BusinessException(500, "Collection初始化失败: " + e.getMessage());
        }
    }

    /**
     * 插入SLS关键字模版
     *
     * @param slsKeyword 模版实体
     * @return 插入的记录数
     */
    public int insert(SlsKeyword slsKeyword) {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法插入数据");
            return 0;
        }
        initCollection();
        
        try {
            // 构建插入数据
            List<String> ids = Collections.singletonList(slsKeyword.getId());
            List<String> descs = Collections.singletonList(slsKeyword.getDesc());
            List<List<Float>> descVectors = Collections.singletonList(slsKeyword.getDescVector());
            List<String> keywords = Collections.singletonList(slsKeyword.getKeywords());
            List<String> applications = Collections.singletonList(
                    StringUtils.isNoneBlank(slsKeyword.getApplication()) ? slsKeyword.getApplication() : "");
            List<String> logstores = Collections.singletonList(
                    StringUtils.isNoneBlank(slsKeyword.getLogstore()) ? slsKeyword.getLogstore() : "");
            List<String> tags = Collections.singletonList(
                    StringUtils.isNoneBlank(slsKeyword.getTags()) ? slsKeyword.getTags() : "");

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(ID_FIELD, ids));
            fields.add(new InsertParam.Field(DESC_FIELD, descs));
            fields.add(new InsertParam.Field(DESC_VECTOR_FIELD, descVectors));
            fields.add(new InsertParam.Field(KEYWORDS_FIELD, keywords));
            fields.add(new InsertParam.Field(APPLICATION_FIELD, applications));
            fields.add(new InsertParam.Field(LOGSTORE_FIELD, logstores));
            fields.add(new InsertParam.Field(TAGS_FIELD, tags));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withFields(fields)
                    .build();
            
            R<MutationResult> insert = milvusClient.insert(insertParam);
            if (insert.getStatus() != R.Status.Success.getCode()) {
                throw new BusinessException(500, "插入数据失败: " + insert.getMessage());
            }
            
            log.info("插入数据成功: id={}", slsKeyword.getId());
            return (int) insert.getData().getInsertCnt();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("插入数据失败", e);
            throw new BusinessException(500, "插入数据失败: " + e.getMessage());
        }
    }

    /**
     * 根据ID查询SLS关键字模版
     *
     * @param id 模版ID
     * @return 模版实体
     */
    public SlsKeyword selectById(String id) {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法查询数据");
            return null;
        }
        initCollection();
        
        try {
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withExpr(ID_FIELD + " == '" + id + "'")
                    .withOutFields(Arrays.asList(ID_FIELD, DESC_FIELD, DESC_VECTOR_FIELD, KEYWORDS_FIELD, 
                            APPLICATION_FIELD, LOGSTORE_FIELD, TAGS_FIELD))
                    .build();
            
            R<QueryResults> query = milvusClient.query(queryParam);
            if (query.getStatus() != R.Status.Success.getCode()) {
                throw new BusinessException(500, "查询数据失败: " + query.getMessage());
            }
            
            QueryResultsWrapper wrapper = new QueryResultsWrapper(query.getData());
            if (wrapper.getRowRecords().isEmpty()) {
                return null;
            }
            
            SlsKeyword slsKeyword = new SlsKeyword();
            slsKeyword.setId((String) wrapper.getFieldWrapper(ID_FIELD).getFieldData().get(0));
            slsKeyword.setDesc((String) wrapper.getFieldWrapper(DESC_FIELD).getFieldData().get(0));
            @SuppressWarnings("unchecked")
            List<Float> descVector = (List<Float>) wrapper.getFieldWrapper(DESC_VECTOR_FIELD).getFieldData().get(0);
            slsKeyword.setDescVector(descVector);
            slsKeyword.setKeywords((String) wrapper.getFieldWrapper(KEYWORDS_FIELD).getFieldData().get(0));
            slsKeyword.setApplication((String) wrapper.getFieldWrapper(APPLICATION_FIELD).getFieldData().get(0));
            slsKeyword.setLogstore((String) wrapper.getFieldWrapper(LOGSTORE_FIELD).getFieldData().get(0));
            slsKeyword.setTags((String) wrapper.getFieldWrapper(TAGS_FIELD).getFieldData().get(0));

            return slsKeyword;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询数据失败: id={}", id, e);
            throw new BusinessException(500, "查询数据失败: " + e.getMessage());
        }
    }

    /**
     * 分页查询SLS关键字模版（支持条件筛选和标签模糊查询）
     *
     * @param page 页码（从1开始）
     * @param pageSize 每页数量
     * @param application 应用名称（可选）
     * @param tag 标签（可选，模糊查询）
     * @return 包含列表和总数的Map
     */
    public Map<String, Object> selectAllWithPagination(int page, int pageSize, String desc,
                                                       String application, String tag) {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法查询数据");
            return new java.util.HashMap<>();
        }
        try {
            // 构建查询表达式
            List<String> conditions = new ArrayList<>();

            if (StringUtils.isNotEmpty(desc)) {
                conditions.add("desc like \"%" + desc + "%\"");
            }
            if (StringUtils.isNotEmpty(application)) {
                conditions.add("application like \"%" + application + "%\"");
            }
            if (StringUtils.isNotEmpty(tag)) {
                conditions.add("tags like \"%" + tag + "%\"");
            }

            String expr = conditions.isEmpty() ? "" : String.join(" and ", conditions);
            
            // 先查询总数（不限制数量）
            QueryParam.Builder queryBuilder = QueryParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withExpr(expr.toString())
                    .withOutFields(Collections.singletonList(ID_FIELD))
                    .withLimit(16384L);

            if (StringUtils.isNotEmpty(expr)) {
                queryBuilder.withExpr(expr);
            }
            
            R<QueryResults> countQuery = milvusClient.query(queryBuilder.build());
            if (countQuery.getStatus() != R.Status.Success.getCode()) {
                throw new BusinessException(500, "查询数据失败: " + countQuery.getMessage());
            }
            
            QueryResultsWrapper countWrapper = new QueryResultsWrapper(countQuery.getData());
            long total = countWrapper.getFieldWrapper(ID_FIELD).getFieldData().size();
            
            // 计算分页参数
            int offset = (page - 1) * pageSize;
            int limit = pageSize;
            
            // 分页查询数据
            QueryParam.Builder pageBuilder = QueryParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withExpr(expr.toString())
                    .withOutFields(Arrays.asList(ID_FIELD, DESC_FIELD, DESC_VECTOR_FIELD, KEYWORDS_FIELD, 
                            APPLICATION_FIELD, LOGSTORE_FIELD, TAGS_FIELD))
                    .withOffset((long) offset)
                    .withLimit((long) limit);
            if (StringUtils.isNotEmpty(expr)) {
                pageBuilder.withExpr(expr);
            }
            
            R<QueryResults> query = milvusClient.query(pageBuilder.build());
            if (query.getStatus() != R.Status.Success.getCode()) {
                throw new BusinessException(500, "查询数据失败: " + query.getMessage());
            }
            
            QueryResultsWrapper wrapper = new QueryResultsWrapper(query.getData());
            List<SlsKeyword> result = new ArrayList<>();
            
            int rowCount = wrapper.getRowRecords().size();
            for (int i = 0; i < rowCount; i++) {
                SlsKeyword slsKeyword = new SlsKeyword();
                slsKeyword.setId((String) wrapper.getFieldWrapper(ID_FIELD).getFieldData().get(i));
                slsKeyword.setDesc((String) wrapper.getFieldWrapper(DESC_FIELD).getFieldData().get(i));
                @SuppressWarnings("unchecked")
                List<Float> descVector = (List<Float>) wrapper.getFieldWrapper(DESC_VECTOR_FIELD).getFieldData().get(i);
                slsKeyword.setDescVector(descVector);
                slsKeyword.setKeywords((String) wrapper.getFieldWrapper(KEYWORDS_FIELD).getFieldData().get(i));
                slsKeyword.setApplication((String) wrapper.getFieldWrapper(APPLICATION_FIELD).getFieldData().get(i));
                slsKeyword.setLogstore((String) wrapper.getFieldWrapper(LOGSTORE_FIELD).getFieldData().get(i));
                slsKeyword.setTags((String) wrapper.getFieldWrapper(TAGS_FIELD).getFieldData().get(i));
                result.add(slsKeyword);
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("list", result);
            resultMap.put("total", total);
            return resultMap;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询数据失败", e);
            throw new BusinessException(500, "查询数据失败: " + e.getMessage());
        }
    }

    /**
     * 根据ID删除SLS关键字模版
     *
     * @param id 模版ID
     * @return 删除的记录数
     */
    public int deleteById(String id) {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法删除数据");
            return 0;
        }
        initCollection();
        
        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withExpr(ID_FIELD + " == '" + id + "'")
                    .build();
            
            R<MutationResult> delete = milvusClient.delete(deleteParam);
            if (delete.getStatus() != R.Status.Success.getCode()) {
                throw new BusinessException(500, "删除数据失败: " + delete.getMessage());
            }
            
            log.info("删除数据成功: id={}", id);
            return (int) delete.getData().getDeleteCnt();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除数据失败: id={}", id, e);
            throw new BusinessException(500, "删除数据失败: " + e.getMessage());
        }
    }

}

