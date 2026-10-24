package ru.itmo.ddia.hw1.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Collects the golden signals from handlers marked with {@link Measured}.
 *
 * Handlers without the annotation are skipped, so Actuator endpoints are filtered out on
 * their own: their handler is not an annotated application method.
 */
public class MeasuredHandlerInterceptor implements HandlerInterceptor {

    private static final String START_NANOS = MeasuredHandlerInterceptor.class.getName() + ".startNanos";

    private final GoldenSignalsRecorder recorder;

    public MeasuredHandlerInterceptor(GoldenSignalsRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (annotationOf(handler) == null) {
            return true;
        }
        request.setAttribute(START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object start = request.getAttribute(START_NANOS);
        if (start == null) {
            return;
        }
        request.removeAttribute(START_NANOS);

        long durationNanos = System.nanoTime() - (Long) start;
        recorder.record(routeOf(request), request.getMethod(), response.getStatus(), durationNanos);
    }

    private static Measured annotationOf(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getMethodAnnotation(Measured.class);
        }
        return null;
    }

    /**
     * The route template, taken from the same request attribute the built-in Spring Boot
     * metric uses. This is what keeps cardinality bounded: without it the label would be the
     * raw URI containing a UUID, and every created object would spawn a new series.
     */
    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}