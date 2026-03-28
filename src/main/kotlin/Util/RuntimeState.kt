package com.TTT.Util

/**
 * Marks a mutable property as runtime-transient state that should be skipped during reflection-based cloning.
 *
 * Properties annotated with `@RuntimeState` are left at their default value in the fresh instance produced by
 * [cloneInstance]. This covers execution counters, pause flags, session caches, generated identities, and other
 * per-execution state that should not carry over between isolated P2P request executions.
 *
 * Unannotated mutable properties are cloned by default. Forgetting this annotation is safe — the clone just
 * carries extra state that gets reset on `init()`.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class RuntimeState
