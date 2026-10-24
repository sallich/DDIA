package ru.itmo.ddia.hw1.metrics;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The public contract of the library: marks a REST handler whose golden signals are collected.
 *
 * No attributes: the handler id comes from the route template, while the set of signals and
 * the histogram boundaries are the same for every handler and are configured globally
 * through ddia.metrics.*.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Measured {
}