package com.stock.system.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.stock.system.agent.service.ResearchModelExecution;
import com.stock.system.agent.service.ResearchExperimentBudget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** 模型失败反馈给业务层，重试须由用户明确发起，避免隐式重复计费。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchAiConfiguration {
    @Autowired private ObjectProvider<ResearchExperimentBudget> experimentBudgets;
    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder researchModelRestClientBuilder() {
        return RestClient.builder().requestFactory((URI uri, HttpMethod method) -> {
            var execution = ResearchModelExecution.current();
            int remaining = execution == null ? 60_000 : execution.remainingMillis();
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Math.min(5_000, Math.max(1, remaining / 4)));
            factory.setReadTimeout(Math.min(45_000, Math.max(1, remaining * 3 / 4)));
            return factory.createRequest(uri, method);
        }).requestInterceptor((request, body, next) -> {
            if (body.length > 65_536) throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED);
            var execution = ResearchModelExecution.current();
            if (execution != null) {
                execution.remainingMillis();
                execution.dispatch(experimentBudgets == null ? null : experimentBudgets.getIfAvailable(), body.length);
            }
            return next.execute(request, body);
        });
    }

    @Bean
    RetryTemplate researchModelRetryTemplate() {
        // 这里限制的是“首次尝试之后的重试次数”，0 才是只发一次。
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }
}
