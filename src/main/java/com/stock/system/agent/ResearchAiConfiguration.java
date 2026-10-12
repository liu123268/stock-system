package com.stock.system.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** 模型失败反馈给业务层，重试须由用户明确发起，避免隐式重复计费。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchAiConfiguration {
    @Bean
    RetryTemplate researchModelRetryTemplate() {
        // 这里限制的是“首次尝试之后的重试次数”，0 才是只发一次。
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }
}
