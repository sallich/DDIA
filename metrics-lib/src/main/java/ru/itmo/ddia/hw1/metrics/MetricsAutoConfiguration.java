package ru.itmo.ddia.hw1.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Wires the library into an application without a single line of application code.
 *
 * Activated through META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({InstanceTagProperties.class, LatencyHistogramProperties.class})
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GoldenSignalsRecorder goldenSignalsRecorder(MeterRegistry registry,
                                                       LatencyHistogramProperties histogram) {
        return new GoldenSignalsRecorder(registry, histogram);
    }

    @Bean
    @ConditionalOnMissingBean
    public MeasuredHandlerInterceptor measuredHandlerInterceptor(GoldenSignalsRecorder recorder) {
        return new MeasuredHandlerInterceptor(recorder);
    }

    @Bean
    public WebMvcConfigurer measuredInterceptorRegistrar(MeasuredHandlerInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public MeasuredHandlersEndpoint measuredHandlersEndpoint(
            // The context holds two beans of this type: the primary controller mapping and
            // the controllerEndpointHandlerMapping of Actuator. The first one is the right one.
            @Qualifier("requestMappingHandlerMapping")
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
            InstanceTagProperties instance) {
        return new MeasuredHandlersEndpoint(handlerMappings, instance);
    }

    /**
     * Common labels are applied through a customizer on purpose: Boot runs customizers before
     * meters are registered, and already registered meters never pick labels up retroactively.
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    public MeterRegistryCustomizer<MeterRegistry> instanceCommonTags(InstanceTagProperties properties) {
        return registry -> registry.config().commonTags(commonTagsOf(properties));
    }

    /**
     * Instance-wide labels: applied to every meter in the registry, JVM meters included.
     *
     * Tags.of(String...) takes an alternating "name, value, name, value, ..." sequence.
     * The names job and instance are avoided on purpose: the scraper adds them itself,
     * and on a collision VictoriaMetrics would rename ours to exported_*.
     */
    private static Tags commonTagsOf(InstanceTagProperties properties) {
        return Tags.of(
                "pod", properties.pod(),
                "datacenter", properties.datacenter(),
                "deployment", properties.deployment());
    }
}