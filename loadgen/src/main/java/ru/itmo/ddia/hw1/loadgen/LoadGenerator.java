package ru.itmo.ddia.hw1.loadgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background load against the service: a gradual climb to the target RPS and holding it.
 *
 * The rate is driven by a ticker: every 100 ms one tenth of the current RPS is sent.
 * Each request runs on a virtual thread, so a slow response does not shift the schedule
 * and the effective rate does not sag under load.
 *
 * The ramp-up exists so the graphs show a climb onto a plateau rather than a step
 * from zero to full load.
 */
public final class LoadGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    public static void main(String[] args) throws Exception {
        List<String> targets = Arrays.stream(required("TARGETS").split(","))
                .map(String::trim)
                .filter(target -> !target.isEmpty())
                .toList();
        int targetRps = intEnv("TARGET_RPS", 20);
        int rampUpSteps = intEnv("RAMP_UP_STEPS", 5);
        int stepSeconds = intEnv("RAMP_UP_STEP_SECONDS", 30);
        double errorRate = doubleEnv("ERROR_RATE", 0.15);

        log.info("Load: targets={}, target RPS={}, ramp-up={} steps of {}s, error rate={}%",
                targets.size(), targetRps, rampUpSteps, stepSeconds, Math.round(errorRate * 100));

        Scenario scenario = new Scenario(targets, errorRate);
        AtomicInteger currentRps = new AtomicInteger(Math.max(1, targetRps / rampUpSteps));

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

        // Ramp-up steps: every stepSeconds adds 1/rampUpSteps of the target RPS.
        // Once the plateau is reached the task cancels itself, otherwise the log repeats forever.
        AtomicInteger step = new AtomicInteger(1);
        AtomicReference<ScheduledFuture<?>> rampUp = new AtomicReference<>();
        rampUp.set(scheduler.scheduleAtFixedRate(() -> {
            int next = step.incrementAndGet();
            int rps = Math.min(targetRps, Math.max(1, targetRps * next / rampUpSteps));
            currentRps.set(rps);
            if (next >= rampUpSteps) {
                log.info("Ramp-up finished, holding {} rps", rps);
                rampUp.get().cancel(false);
            } else {
                log.info("Ramp-up: step {}/{}, {} rps", next, rampUpSteps, rps);
            }
        }, stepSeconds, stepSeconds, TimeUnit.SECONDS));

        // Rate ticker: ten times per second a tenth of the current RPS is submitted.
        scheduler.scheduleAtFixedRate(() -> {
            int perTick = Math.max(1, currentRps.get() / 10);
            for (int i = 0; i < perTick; i++) {
                workers.submit(scenario::fireOnce);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(
                () -> log.info("Responses so far: {}", scenario.statsLine()),
                30, 30, TimeUnit.SECONDS);

        // Runs for as long as the container lives: the load is a background one to watch metrics against.
        Thread.currentThread().join();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: " + name);
        }
        return value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static double doubleEnv(String name, double fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
    }
}