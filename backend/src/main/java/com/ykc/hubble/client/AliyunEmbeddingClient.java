package com.ykc.hubble.client;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.ykc.hubble.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 阿里云Embedding客户端
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
public class AliyunEmbeddingClient {

    @Value("${dashscope.apiKey:}")
    private String apiKey;

    @Value("${dashscope.embeddingModel:text-embedding-v3}")
    private String embeddingModel;

    /**
     * 初始化，设置API Key
     */
    @PostConstruct
    public void init() {
        if (StringUtils.hasText(apiKey) && !apiKey.equals("your-api-key-here")) {
            log.info("阿里云DashScope Embedding服务已初始化，模型: {}", embeddingModel);
        } else {
            log.warn("阿里云DashScope API Key未配置，向量转换功能将不可用");
        }
    }

    /**
     * 将文本转换为向量
     *
     * @param text 要转换的文本
     * @return 文本向量
     */
    public List<Float> textToVector(String text) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(400, "文本不能为空");
        }

        // 如果API Key未配置或为默认值，抛出异常
        if (!StringUtils.hasText(apiKey) || apiKey.equals("your-api-key-here")) {
            throw new BusinessException(500, "未配置DashScope API Key，无法进行向量转换");
        }

        try {
            // 调用阿里云Embedding API
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                    .model(embeddingModel)
                    .texts(Arrays.asList(text))
                    .apiKey(apiKey)
                    .build();

            TextEmbedding textEmbedding = new TextEmbedding();
            TextEmbeddingResult result = textEmbedding.call(param);

            // 获取向量
            if (result != null && result.getOutput() != null 
                    && result.getOutput().getEmbeddings() != null 
                    && !result.getOutput().getEmbeddings().isEmpty()) {
                
                List<Double> embedding = result.getOutput().getEmbeddings().get(0).getEmbedding();
                
                // 转换为Float列表
                List<Float> vector = new ArrayList<>(embedding.size());
                for (Double value : embedding) {
                    vector.add(value.floatValue());
                }
                
                log.debug("成功生成向量，维度: {}", vector.size());
                return vector;
            } else {
                log.error("阿里云Embedding API返回结果为空");
                throw new BusinessException(500, "向量转换失败：API返回结果为空");
            }

        } catch (ApiException | NoApiKeyException e) {
            log.error("调用阿里云Embedding API失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "向量转换失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("向量转换异常: {}", e.getMessage(), e);
            throw new BusinessException(500, "向量转换失败: " + e.getMessage());
        }
    }
}

