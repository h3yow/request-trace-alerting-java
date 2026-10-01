package com.github.h3yow.requesttracealertingjava.config;

import com.github.h3yow.requesttracealertingjava.alert.AlertManager;
import com.github.h3yow.requesttracealertingjava.alert.AlertRule;
import com.github.h3yow.requesttracealertingjava.alert.AlertSink;
import com.github.h3yow.requesttracealertingjava.alert.LoggingAlertSink;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsInterceptor;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import com.github.h3yow.requesttracealertingjava.trace.TraceIdFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 观测能力装配：链路过滤器、指标过滤器、指标注册表、告警管理器。
 *
 * <p>过滤器顺序：TraceIdFilter 在最前，保证后续所有环节都能拿到链路标识。
 */
@Configuration
@EnableConfigurationProperties(ObservabilityConfig.AlertProperties.class)
public class ObservabilityConfig {

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public AlertSink alertSink() {
        return new LoggingAlertSink();
    }

    @Bean
    public AlertManager alertManager(AlertProperties properties, AlertSink sink) {
        return new AlertManager(properties.toRule(), sink);
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public WebMvcConfigurer metricsWebMvcConfigurer(MetricsRegistry registry, AlertManager alertManager) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry interceptorRegistry) {
                interceptorRegistry.addInterceptor(new MetricsInterceptor(registry, alertManager));
            }
        };
    }

    /**
     * 告警规则配置（observability.alert.*），启动时生效；
     * 运行期可通过 AlertManager.updateRule 更新，只影响之后的窗口。
     */
    @ConfigurationProperties("observability.alert")
    public record AlertProperties(
            long windowSizeMillis,
            long minRequests,
            double maxErrorRate,
            long slowThresholdMillis,
            long maxSlowCount,
            int consecutiveWindows,
            long suppressionMillis) {

        public AlertProperties {
            AlertRule defaults = AlertRule.defaults();
            if (windowSizeMillis <= 0) {
                windowSizeMillis = defaults.windowSizeMillis();
            }
            if (minRequests <= 0) {
                minRequests = defaults.minRequests();
            }
            if (maxErrorRate <= 0) {
                maxErrorRate = defaults.maxErrorRate();
            }
            if (slowThresholdMillis <= 0) {
                slowThresholdMillis = defaults.slowThresholdMillis();
            }
            if (maxSlowCount <= 0) {
                maxSlowCount = defaults.maxSlowCount();
            }
            if (consecutiveWindows <= 0) {
                consecutiveWindows = defaults.consecutiveWindows();
            }
            if (suppressionMillis <= 0) {
                suppressionMillis = defaults.suppressionMillis();
            }
        }

        public AlertRule toRule() {
            return new AlertRule(windowSizeMillis, minRequests, maxErrorRate,
                    slowThresholdMillis, maxSlowCount, consecutiveWindows, suppressionMillis);
        }
    }
}
