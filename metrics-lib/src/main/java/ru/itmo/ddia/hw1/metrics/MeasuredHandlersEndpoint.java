package ru.itmo.ddia.hw1.metrics;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reports the handlers metrics are actually collected from, together with the metric names.
 *
 * The source is the Spring MVC registry filtered by {@link Measured}. The list is therefore
 * exact and available right after startup: service endpoints never appear in it and, unlike
 * walking the values of the route label in the metrics storage, a handler shows up even when
 * it has not received a single request yet.
 *
 * Served at /actuator/measured once the id is added to management.endpoints.web.exposure.include.
 */
@Endpoint(id = "measured")
public class MeasuredHandlersEndpoint {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;
    private final InstanceTagProperties instance;

    public MeasuredHandlersEndpoint(ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
                                    InstanceTagProperties instance) {
        this.handlerMappings = handlerMappings;
        this.instance = instance;
    }

    @ReadOperation
    public Map<String, Object> measuredHandlers() {
        List<Map<String, Object>> handlers = new ArrayList<>();

        // Take the primary controller mapping: Actuator endpoints live in their own.
        RequestMappingHandlerMapping mapping = handlerMappings.getObject();
        mapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (handlerMethod.getMethodAnnotation(Measured.class) == null) {
                return;
            }
            for (String route : routesOf(info)) {
                Map<String, Object> handler = new LinkedHashMap<>();
                handler.put("route", route);
                handler.put("methods", httpMethodsOf(info));
                handlers.add(handler);
            }
        });

        handlers.sort(Comparator
                .comparing((Map<String, Object> h) -> (String) h.get("route"))
                .thenComparing(h -> h.get("methods").toString()));

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("latency", "ddia_http_request_duration_seconds");
        metrics.put("throughput", "ddia_http_requests_total");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deployment", instance.deployment());
        body.put("labels", List.of("pod", "datacenter", "deployment", "route", "method", "metric_type"));
        body.put("metrics", metrics);
        body.put("handlers", handlers);
        return body;
    }

    /**
     * Spring 6 parses paths with PathPatternParser by default, but the older AntPathMatcher
     * is still possible, so both sources of patterns are supported.
     */
    private static Set<String> routesOf(RequestMappingInfo info) {
        Set<String> routes = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition().getPatterns()
                    .forEach(pattern -> routes.add(pattern.getPatternString()));
        }
        PatternsRequestCondition legacy = info.getPatternsCondition();
        if (legacy != null) {
            routes.addAll(legacy.getPatterns());
        }
        return routes;
    }

    private static Set<String> httpMethodsOf(RequestMappingInfo info) {
        Set<String> methods = new TreeSet<>();
        info.getMethodsCondition().getMethods().forEach(method -> methods.add(method.name()));
        return methods;
    }
}