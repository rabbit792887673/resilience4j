/*
 *
 *  Copyright 2019 Robert Winkler
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.circuitbreaker.internal;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.ResultRecordedAsFailureException;
import io.github.resilience4j.circuitbreaker.event.*;
import io.github.resilience4j.core.EventConsumer;
import io.github.resilience4j.core.EventProcessor;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.core.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.*;
import static io.github.resilience4j.circuitbreaker.internal.CircuitBreakerMetrics.Result;
import static io.github.resilience4j.circuitbreaker.internal.CircuitBreakerMetrics.Result.BELOW_THRESHOLDS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Collections.emptyMap;

/**
 * A CircuitBreaker finite state machine.
 */
public final class CircuitBreakerStateMachine implements CircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakerStateMachine.class);

    private final String name;
    private final AtomicReference<CircuitBreakerState> stateReference;
    private final CircuitBreakerConfig circuitBreakerConfig;
    private final Map<String, String> tags;
    private final CircuitBreakerEventProcessor eventProcessor;
    private final Clock clock;
    private final SchedulerFactory schedulerFactory;
    private final Function<Clock, Long> currentTimestampFunction;
    private final TimeUnit timestampUnit;

    /**
     * Creates a circuitBreaker. 创建一个熔断器
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param clock                A Clock which can be mocked in tests.
     * @param schedulerFactory     A SchedulerFactory which can be mocked in tests.
     */
    private CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock, SchedulerFactory schedulerFactory, Map<String, String> tags) {

        this.name = name;
        // Objects.requireNonNull(),检查指定的对象引用是否为空。该方法主要用于在方法和构造函数中进行参数验证
        this.circuitBreakerConfig = Objects
            .requireNonNull(circuitBreakerConfig, "Config must not be null");
        // 熔断器的事件处理器
        this.eventProcessor = new CircuitBreakerEventProcessor();
        this.clock = clock;
        // 使用给定的初始值(关闭状态)创建一个新的AtomicReference
        this.stateReference = new AtomicReference<>(new ClosedState());
        this.schedulerFactory = schedulerFactory;
        this.tags = Objects.requireNonNull(tags, "Tags must not be null");
        this.currentTimestampFunction = circuitBreakerConfig.getCurrentTimestampFunction();
        this.timestampUnit = circuitBreakerConfig.getTimestampUnit();
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param schedulerFactory     A SchedulerFactory which can be mocked in tests.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        SchedulerFactory schedulerFactory) {
        this(name, circuitBreakerConfig, Clock.systemUTC(), schedulerFactory, emptyMap());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock) {
        this(name, circuitBreakerConfig, clock, SchedulerFactory.getInstance(), emptyMap());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock, Map<String, String> tags) {
        this(name, circuitBreakerConfig, clock, SchedulerFactory.getInstance(), tags);
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig) {
        this(name, circuitBreakerConfig, Clock.systemUTC());
    }

    /**
     * Creates a circuitBreaker. 创建一个熔断器
     *
     * @param name                 the name of the CircuitBreaker 熔断器的名称
     * @param circuitBreakerConfig The CircuitBreaker configuration. 熔断器的配置
     * @param tags                 Tags to add to the CircuitBreaker. 为熔断器添加的标签
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Map<String, String> tags) {
        this(name, circuitBreakerConfig, Clock.systemUTC(), tags);
    }

    /**
     * Creates a circuitBreaker with default config.
     *
     * @param name the name of the CircuitBreaker
     */
    public CircuitBreakerStateMachine(String name) {
        this(name, CircuitBreakerConfig.ofDefaults());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration supplier.
     */
    public CircuitBreakerStateMachine(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfig) {
        this(name, circuitBreakerConfig.get());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration supplier.
     */
    public CircuitBreakerStateMachine(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfig,
        Map<String, String> tags) {
        this(name, circuitBreakerConfig.get(), tags);
    }

    @Override
    public long getCurrentTimestamp() {
        return this.currentTimestampFunction.apply(clock);
    }

    @Override
    public TimeUnit getTimestampUnit() {
        return timestampUnit;
    }

    @Override
    public boolean tryAcquirePermission() {
        boolean callPermitted = stateReference.get().tryAcquirePermission();
        if (!callPermitted) {
            publishCallNotPermittedEvent();
        }
        return callPermitted;
    }

    @Override
    public void releasePermission() {
        stateReference.get().releasePermission();
    }

    @Override
    public void acquirePermission() {
        try {
            stateReference.get().acquirePermission();
        } catch (Exception e) {
            publishCallNotPermittedEvent();
            throw e;
        }
    }

    /**
     * error事件处理器
     *
     * @param duration     The elapsed time duration of the call 调用的执行时长
     * @param durationUnit The duration unit 时间单位
     * @param throwable    The throwable which must be recorded
     */
    @Override
    public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
        // Handle the case if the completable future throws a CompletionException wrapping the original exception
        // where original exception is the the one to retry not the CompletionException.
        // 如果可完成的future抛出一个CompletionException包装的原始异常，其中原始异常是要重试的异常，而不是CompletionException，则处理这种情况。

        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            // 拿到用户实际抛出来的的异常
            Throwable cause = throwable.getCause();
            //todo
            handleThrowable(duration, durationUnit, cause);
        } else {
            handleThrowable(duration, durationUnit, throwable);
        }
    }


    private void handleThrowable(long duration, TimeUnit durationUnit, Throwable throwable) {
        // 可忽略的异常
        if (circuitBreakerConfig.getIgnoreExceptionPredicate().test(throwable)) {
            LOG.debug("CircuitBreaker '{}' ignored an exception:", name, throwable);
            //
            releasePermission();
            // 触发熔断器失败事件
            publishCircuitIgnoredErrorEvent(name, duration, durationUnit, throwable);
            return;
        }
        // 需记录的异常
        if (circuitBreakerConfig.getRecordExceptionPredicate().test(throwable)) {
            LOG.debug("CircuitBreaker '{}' recorded an exception as failure:", name, throwable);
            // 触发熔断器error事件
            publishCircuitErrorEvent(name, duration, durationUnit, throwable);
            stateReference.get().onError(duration, durationUnit, throwable);
        } else {
            LOG.debug("CircuitBreaker '{}' recorded an exception as success:", name, throwable);
            // 触发成功事件
            publishSuccessEvent(duration, durationUnit);
            stateReference.get().onSuccess(duration, durationUnit);
        }
        handlePossibleTransition(Either.right(throwable));
    }

    @Override
    public void onSuccess(long duration, TimeUnit durationUnit) {
        LOG.debug("CircuitBreaker '{}' succeeded:", name);
        publishSuccessEvent(duration, durationUnit);
        stateReference.get().onSuccess(duration, durationUnit);
    }

    /**
     *
     * @param duration     The elapsed time duration of the call
     * @param durationUnit The duration unit
     * @param result       The result of the protected function
     */
    @Override
    public void onResult(long duration, TimeUnit durationUnit, @Nullable Object result) {

        // 是记录的预期结果
        if (result != null && circuitBreakerConfig.getRecordResultPredicate().test(result)) {
            LOG.debug("CircuitBreaker '{}' recorded a result type '{}' as failure:", name, result.getClass());
            ResultRecordedAsFailureException failure = new ResultRecordedAsFailureException(name, result);
            // 触发熔断器error事件
            publishCircuitErrorEvent(name, duration, durationUnit, failure);
            //
            stateReference.get().onError(duration, durationUnit, failure);
        } else {
            onSuccess(duration, durationUnit);
            if (result != null) {
                handlePossibleTransition(Either.left(result));
            }
        }
    }

    /**
     * 处理可能的状态的转换
     * @param result
     */
    private void handlePossibleTransition(Either<Object, Throwable> result) {
        //
        CircuitBreakerConfig.TransitionCheckResult transitionCheckResult =
            circuitBreakerConfig.getTransitionOnResult()
            .apply(result);
        //
        stateReference.get().handlePossibleTransition(transitionCheckResult);
    }

    /**
     * Get the state of this CircuitBreaker.
     *
     * @return the the state of this CircuitBreaker
     */
    @Override
    public State getState() {
        return this.stateReference.get().getState();
    }

    /**
     * Get the name of this CircuitBreaker.
     *
     * @return the the name of this CircuitBreaker
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Get the config of this CircuitBreaker.
     *
     * @return the config of this CircuitBreaker
     */
    @Override
    public CircuitBreakerConfig getCircuitBreakerConfig() {
        return circuitBreakerConfig;
    }

    @Override
    public Metrics getMetrics() {
        return this.stateReference.get().getMetrics();
    }

    @Override
    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return String.format("CircuitBreaker '%s'", this.name);
    }

    @Override
    public void reset() {
        CircuitBreakerState previousState = stateReference
            .getAndUpdate(currentState -> new ClosedState());
        if (previousState.getState() != CLOSED) {
            publishStateTransitionEvent(
                StateTransition.transitionBetween(getName(), previousState.getState(), CLOSED));
        }
        publishResetEvent();
    }

    private void stateTransition(State newState,
        UnaryOperator<CircuitBreakerState> newStateGenerator) {
        CircuitBreakerState previousState = stateReference.getAndUpdate(currentState -> {
            StateTransition.transitionBetween(getName(), currentState.getState(), newState);
            currentState.preTransitionHook();
            return newStateGenerator.apply(currentState);
        });
        publishStateTransitionEvent(
            StateTransition.transitionBetween(getName(), previousState.getState(), newState));
    }

    @Override
    public void transitionToDisabledState() {
        stateTransition(DISABLED, currentState -> new DisabledState());
    }

    @Override
    public void transitionToMetricsOnlyState() {
        stateTransition(METRICS_ONLY, currentState -> new MetricsOnlyState());
    }

    @Override
    public void transitionToForcedOpenState() {
        stateTransition(FORCED_OPEN,
            currentState -> new ForcedOpenState(currentState.attempts() + 1));
    }

    @Override
    public void transitionToClosedState() {
        stateTransition(CLOSED, currentState -> new ClosedState());
    }

    @Override
    public void transitionToOpenState() {
        stateTransition(OPEN,
            currentState -> new OpenState(currentState.attempts() + 1, currentState.getMetrics()));
    }

    /**
     * todo???
     * @param waitDuration how long should we wait in open state
     */
    @Override
    public void transitionToOpenStateFor(Duration waitDuration) {
        stateTransition(OPEN,
            currentState -> new OpenState(currentState.attempts(), waitDuration, currentState.getMetrics()));
    }

    /**
     * todo???
     * @param waitUntil how long should we wait in open state
     */
    @Override
    public void transitionToOpenStateUntil(Instant waitUntil) {
        stateTransition(OPEN,
            currentState -> new OpenState(currentState.attempts(), waitUntil, currentState.getMetrics()));
    }

    @Override
    public void transitionToHalfOpenState() {
        stateTransition(HALF_OPEN, currentState -> new HalfOpenState(currentState.attempts()));
    }

    private boolean shouldPublishEvents(CircuitBreakerEvent event) {
        return stateReference.get().shouldPublishEvents(event);
    }

    private void publishEventIfHasConsumer(CircuitBreakerEvent event) {
        if (!eventProcessor.hasConsumers()) {
            LOG.debug("No Consumers: Event {} not published", event.getEventType());
            return;
        }

        publishEvent(event);
    }

    private void publishEvent(CircuitBreakerEvent event) {
        if (shouldPublishEvents(event)) {
            try {
                eventProcessor.consumeEvent(event);
                LOG.debug("Event {} published: {}", event.getEventType(), event);
            } catch (Throwable t) {
                LOG.warn("Failed to handle event {}", event.getEventType(), t);
            }
        } else {
            LOG.debug("Publishing not allowed: Event {} not published", event.getEventType());
        }
    }

    private void publishStateTransitionEvent(final StateTransition stateTransition) {
        if (StateTransition.isInternalTransition(stateTransition)) {
            return;
        }
        publishEventIfHasConsumer(new CircuitBreakerOnStateTransitionEvent(name, stateTransition));
    }

    private void publishResetEvent() {
        publishEventIfHasConsumer(new CircuitBreakerOnResetEvent(name));
    }

    private void publishCallNotPermittedEvent() {
        publishEventIfHasConsumer(new CircuitBreakerOnCallNotPermittedEvent(name));
    }

    private void publishSuccessEvent(final long duration, TimeUnit durationUnit) {
        if (eventProcessor.hasConsumers()) {
            publishEvent(new CircuitBreakerOnSuccessEvent(name, elapsedDuration(duration, durationUnit)));
        }
    }

    private Duration elapsedDuration(final long duration, TimeUnit durationUnit) {
        return Duration.ofNanos(durationUnit.toNanos(duration));
    }

    private void publishCircuitErrorEvent(final String name, final long duration, final TimeUnit durationUnit,
                                          final Throwable throwable) {
        if (eventProcessor.hasConsumers()) {
            final Duration elapsedDuration = elapsedDuration(duration, durationUnit);
            publishEvent(new CircuitBreakerOnErrorEvent(name, elapsedDuration, throwable));
        }
    }

    private void publishCircuitIgnoredErrorEvent(final String name, long duration, final TimeUnit durationUnit,
                                                 final Throwable throwable) {
        final Duration elapsedDuration = elapsedDuration(duration, durationUnit);
        publishEventIfHasConsumer(new CircuitBreakerOnIgnoredErrorEvent(name, elapsedDuration, throwable));
    }

    private void publishCircuitFailureRateExceededEvent(final String name, float failureRate) {
        publishEventIfHasConsumer(new CircuitBreakerOnFailureRateExceededEvent(name, failureRate));
    }

    private void publishCircuitSlowCallRateExceededEvent(final String name, float slowCallRate) {
        publishEventIfHasConsumer(new CircuitBreakerOnSlowCallRateExceededEvent(name, slowCallRate));
    }


    /**
     * 触发熔断器超过阈值的事件
     * @param result 结果
     * @param metrics 熔断器相关阈值指标
     */
    private void publishCircuitThresholdsExceededEvent(final Result result, final CircuitBreakerMetrics metrics) {
        // 失败率超过阈值
        if (Result.hasFailureRateExceededThreshold(result)) {
            // 触发熔断器失败率超过阈值事件
            publishCircuitFailureRateExceededEvent(getName(), metrics.getFailureRate());
        }
        // 慢请求率超过阈值
        if (Result.hasSlowCallRateExceededThreshold(result)) {
            // 触发熔断器慢请求率超过阈值事件
            publishCircuitSlowCallRateExceededEvent(getName(), metrics.getSlowCallRate());
        }
    }

    @Override
    public EventPublisher getEventPublisher() {
        return eventProcessor;
    }

    private interface CircuitBreakerState {

        boolean tryAcquirePermission();

        void acquirePermission();

        void releasePermission();

        void onError(long duration, TimeUnit durationUnit, Throwable throwable);

        void onSuccess(long duration, TimeUnit durationUnit);

        void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result);

        int attempts();

        CircuitBreaker.State getState();

        CircuitBreakerMetrics getMetrics();

        /**
         * Should the CircuitBreaker in this state publish events
         *
         * @return a boolean signaling if the events should be published
         */
        default boolean shouldPublishEvents(CircuitBreakerEvent event) {
            return event.getEventType().forcePublish || getState().allowPublish;
        }

        /**
         * This method is invoked before transit to other CircuitBreakerState.
         */
        default void preTransitionHook() {
            // noOp
        }
    }

    /**
     * 熔断器事件处理器
     */
    private class CircuitBreakerEventProcessor extends
        EventProcessor<CircuitBreakerEvent> implements EventConsumer<CircuitBreakerEvent>,
        EventPublisher {

        @Override
        public EventPublisher onSuccess(
            EventConsumer<CircuitBreakerOnSuccessEvent> onSuccessEventConsumer) {
            registerConsumer(CircuitBreakerOnSuccessEvent.class.getName(),
                onSuccessEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onError(
            EventConsumer<CircuitBreakerOnErrorEvent> onErrorEventConsumer) {
            registerConsumer(CircuitBreakerOnErrorEvent.class.getName(),
                onErrorEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onStateTransition(
            EventConsumer<CircuitBreakerOnStateTransitionEvent> onStateTransitionEventConsumer) {
            registerConsumer(CircuitBreakerOnStateTransitionEvent.class.getName(),
                onStateTransitionEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onReset(
            EventConsumer<CircuitBreakerOnResetEvent> onResetEventConsumer) {
            registerConsumer(CircuitBreakerOnResetEvent.class.getName(),
                onResetEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onIgnoredError(
            EventConsumer<CircuitBreakerOnIgnoredErrorEvent> onIgnoredErrorEventConsumer) {
            registerConsumer(CircuitBreakerOnIgnoredErrorEvent.class.getName(),
                onIgnoredErrorEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onCallNotPermitted(
            EventConsumer<CircuitBreakerOnCallNotPermittedEvent> onCallNotPermittedEventConsumer) {
            registerConsumer(CircuitBreakerOnCallNotPermittedEvent.class.getName(),
                onCallNotPermittedEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onFailureRateExceeded(
            EventConsumer<CircuitBreakerOnFailureRateExceededEvent> onFailureRateExceededConsumer) {
            registerConsumer(CircuitBreakerOnFailureRateExceededEvent.class.getName(),
                onFailureRateExceededConsumer);
            return this;
        }

        @Override
        public EventPublisher onSlowCallRateExceeded(
            EventConsumer<CircuitBreakerOnSlowCallRateExceededEvent> onSlowCallRateExceededConsumer) {
            registerConsumer(CircuitBreakerOnSlowCallRateExceededEvent.class.getName(),
                onSlowCallRateExceededConsumer);
            return this;
        }

        @Override
        public void consumeEvent(CircuitBreakerEvent event) {
            super.processEvent(event);
        }
    }

    private class ClosedState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isClosed;

        ClosedState() {
            this.circuitBreakerMetrics = CircuitBreakerMetrics.forClosed(getCircuitBreakerConfig(), clock);
            this.isClosed = new AtomicBoolean(true);
        }

        /**
         * Returns always true, because the CircuitBreaker is closed.
         *
         * @return always true, because the CircuitBreaker is closed.
         */
        @Override
        public boolean tryAcquirePermission() {
            return isClosed.get();
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is closed.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // CircuitBreakerMetrics is thread-safe 。CircuitBreakerMetrics是线程安全的
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        /**
         * 处理可能的状态转换
         * @param result
         */
        @Override
        public void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result) {

            //
            if (result.isTransitionToOpen() && isClosed.compareAndSet(true, false)) {
                // 有等待时长
                if (result.getWaitDuration() != null) {
                    // 变为打开状态
                    transitionToOpenStateFor(result.getWaitDuration());

                    // 有等待时间单位
                } else if (result.getWaitUntil() != null) {
                    //
                    transitionToOpenStateUntil(result.getWaitUntil());
                } else {
                    // 过渡检查导致打开请求，但现在设置了等待属性?这是不应该发生的 ???
                    throw new IllegalArgumentException("Transition check resulted in open request but now wait " +
                        "attribute was set? This should never happen");
                }
            }
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Transitions to open state when thresholds have been exceeded.
         * 超过阈值时转换到打开状态
         *
         * @param result the Result
         */
        private void checkIfThresholdsExceeded(Result result) {
            // 结果超过了阈值 && 状态为关闭状态
            if (Result.hasExceededThresholds(result) && isClosed.compareAndSet(true, false)) {
                // 触发熔断器超过阈值的事件
                publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
                // 熔断器变为打开状态
                transitionToOpenState();
            }
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.CLOSED;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class OpenState implements CircuitBreakerState {

        private final int attempts;
        private final Instant retryAfterWaitDuration;
        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isOpen;

        @Nullable
        private final ScheduledFuture<?> transitionToHalfOpenFuture;

        OpenState(final int attempts, CircuitBreakerMetrics circuitBreakerMetrics) {
            this(attempts, circuitBreakerConfig.getWaitIntervalFunctionInOpenState().apply(attempts),
                circuitBreakerMetrics);
        }

        OpenState(final int attempts, final Duration waitDuration, CircuitBreakerMetrics circuitBreakerMetrics) {
            this(attempts, waitDuration.toMillis(), circuitBreakerMetrics);
        }

        OpenState(final int attempts, final long waitDurationInMillis, CircuitBreakerMetrics circuitBreakerMetrics) {
            this(attempts, waitDurationInMillis, clock.instant().plus(waitDurationInMillis, MILLIS),
                circuitBreakerMetrics);
        }

        OpenState(final int attempts, final Instant waitUntil, CircuitBreakerMetrics circuitBreakerMetrics) {
            this(attempts, Duration.between(clock.instant(), waitUntil).toMillis(), waitUntil,
                circuitBreakerMetrics);
        }

        OpenState(final int attempts, final long waitDurationInMillis, final Instant retryAfterWaitDuration,
                  CircuitBreakerMetrics circuitBreakerMetrics) {
            this.attempts = attempts;
            this.retryAfterWaitDuration = retryAfterWaitDuration;
            this.circuitBreakerMetrics = circuitBreakerMetrics;

            if (circuitBreakerConfig.isAutomaticTransitionFromOpenToHalfOpenEnabled()) {
                ScheduledExecutorService scheduledExecutorService = schedulerFactory.getScheduler();
                transitionToHalfOpenFuture = scheduledExecutorService
                    .schedule(this::toHalfOpenState, waitDurationInMillis, TimeUnit.MILLISECONDS);
            } else {
                transitionToHalfOpenFuture = null;
            }
            isOpen = new AtomicBoolean(true);
        }

        /**
         * Returns false, if the wait duration has not elapsed. Returns true, if the wait duration
         * has elapsed and transitions the state machine to HALF_OPEN state.
         *
         * @return false, if the wait duration has not elapsed. true, if the wait duration has
         * elapsed.
         */
        @Override
        public boolean tryAcquirePermission() {
            // Thread-safe
            if (clock.instant().isAfter(retryAfterWaitDuration)) {
                toHalfOpenState();
                // Check if the call is allowed to run in HALF_OPEN state after state transition
                // super.tryAcquirePermission() doesn't work right that's why the code is copied
                boolean callPermitted = stateReference.get().tryAcquirePermission();
                if (!callPermitted) {
                    publishCallNotPermittedEvent();
                    circuitBreakerMetrics.onCallNotPermitted();
                }
                return callPermitted;
            }
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            if (!tryAcquirePermission()) {
                throw CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreakerStateMachine.this);
            }
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // Could be called when Thread 1 invokes acquirePermission when the state is CLOSED, but in the meantime another
            // Thread 2 calls onError and the state changes from CLOSED to OPEN before Thread 1 calls onError.
            // But the onError event should still be recorded, even if it happened after the state transition.
            circuitBreakerMetrics.onError(duration, durationUnit);
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // Could be called when Thread 1 invokes acquirePermission when the state is CLOSED, but in the meantime another
            // Thread 2 calls onError and the state changes from CLOSED to OPEN before Thread 1 calls onSuccess.
            // But the onSuccess event should still be recorded, even if it happened after the state transition.
            circuitBreakerMetrics.onSuccess(duration, durationUnit);
        }

        @Override
        public void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result) {
            // noOp
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }

        @Override
        public void preTransitionHook() {
            cancelAutomaticTransitionToHalfOpen();
        }

        private synchronized void toHalfOpenState() {
            if (isOpen.compareAndSet(true, false)) {
                transitionToHalfOpenState();
            }
        }

        private void cancelAutomaticTransitionToHalfOpen() {
            if (transitionToHalfOpenFuture != null && !transitionToHalfOpenFuture.isDone()) {
                transitionToHalfOpenFuture.cancel(true);
            }
        }

    }

    private class DisabledState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;

        DisabledState() {
            this.circuitBreakerMetrics = CircuitBreakerMetrics
                .forDisabled(getCircuitBreakerConfig(), clock);
        }

        /**
         * Returns always true, because the CircuitBreaker is disabled.
         *
         * @return always true, because the CircuitBreaker is disabled.
         */
        @Override
        public boolean tryAcquirePermission() {
            return true;
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is disabled.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // noOp
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // noOp
        }

        @Override
        public void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result) {
            // noOp
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.DISABLED;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class MetricsOnlyState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isFailureRateExceeded;
        private final AtomicBoolean isSlowCallRateExceeded;

        MetricsOnlyState() {
            circuitBreakerMetrics = CircuitBreakerMetrics
                .forMetricsOnly(getCircuitBreakerConfig(), clock);
            isFailureRateExceeded = new AtomicBoolean(false);
            isSlowCallRateExceeded = new AtomicBoolean(false);
        }

        /**
         * Returns always true, because the CircuitBreaker is always closed in this state.
         *
         * @return always true, because the CircuitBreaker is always closed in this state.
         */
        @Override
        public boolean tryAcquirePermission() {
            return true;
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is always closed in this state.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        @Override
        public void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result) {
            // noOp
        }

        private void checkIfThresholdsExceeded(Result result) {
            if (!Result.hasExceededThresholds(result)) {
                return;
            }

            if (shouldPublishFailureRateExceededEvent(result)) {
                publishCircuitFailureRateExceededEvent(getName(), circuitBreakerMetrics.getFailureRate());
            }

            if (shouldPublishSlowCallRateExceededEvent(result)) {
                publishCircuitSlowCallRateExceededEvent(getName(), circuitBreakerMetrics.getSlowCallRate());
            }
        }

        private boolean shouldPublishFailureRateExceededEvent(Result result) {
            return Result.hasFailureRateExceededThreshold(result) &&
                isFailureRateExceeded.compareAndSet(false, true);
        }

        private boolean shouldPublishSlowCallRateExceededEvent(Result result) {
            return Result.hasSlowCallRateExceededThreshold(result) &&
                isSlowCallRateExceeded.compareAndSet(false, true);
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.METRICS_ONLY;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class ForcedOpenState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final int attempts;

        ForcedOpenState(int attempts) {
            this.attempts = attempts;
            this.circuitBreakerMetrics = CircuitBreakerMetrics.forForcedOpen(circuitBreakerConfig, clock);
        }

        /**
         * Returns always false, and records the rejected call.
         *
         * @return always false, since the FORCED_OPEN state always denies calls.
         */
        @Override
        public boolean tryAcquirePermission() {
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            circuitBreakerMetrics.onCallNotPermitted();
            throw CallNotPermittedException
                .createCallNotPermittedException(CircuitBreakerStateMachine.this);
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // noOp
        }

        @Override
        public void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result) {
            // noOp
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.FORCED_OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    private class HalfOpenState implements CircuitBreakerState {

        private final AtomicInteger permittedNumberOfCalls;
        private final AtomicBoolean isHalfOpen;
        private final int attempts;
        private final CircuitBreakerMetrics circuitBreakerMetrics;
        @Nullable
        private final ScheduledFuture<?> transitionToOpenFuture;

        HalfOpenState(int attempts) {
            int permittedNumberOfCallsInHalfOpenState = circuitBreakerConfig
                .getPermittedNumberOfCallsInHalfOpenState();
            this.circuitBreakerMetrics = CircuitBreakerMetrics
                .forHalfOpen(permittedNumberOfCallsInHalfOpenState, getCircuitBreakerConfig(), clock);
            this.permittedNumberOfCalls = new AtomicInteger(permittedNumberOfCallsInHalfOpenState);
            this.isHalfOpen = new AtomicBoolean(true);
            this.attempts = attempts;

            final long maxWaitDurationInHalfOpenState = circuitBreakerConfig.getMaxWaitDurationInHalfOpenState().toMillis();
            if (maxWaitDurationInHalfOpenState >= 1) {
                ScheduledExecutorService scheduledExecutorService = schedulerFactory.getScheduler();
                transitionToOpenFuture = scheduledExecutorService
                    .schedule(this::toOpenState, maxWaitDurationInHalfOpenState, TimeUnit.MILLISECONDS);
            } else {
                transitionToOpenFuture = null;
            }
        }

        /**
         * Checks if test request is allowed.
         * <p>
         * Returns true, if test request counter is not zero. Returns false, if test request counter
         * is zero.
         *
         * @return true, if test request counter is not zero.
         */
        @Override
        public boolean tryAcquirePermission() {
            if (permittedNumberOfCalls.getAndUpdate(current -> current == 0 ? current : --current)
                > 0) {
                return true;
            }
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            if (!tryAcquirePermission()) {
                throw CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreakerStateMachine.this);
            }
        }

        @Override
        public void preTransitionHook() {
            cancelAutomaticTransitionToOpen();
        }

        private void cancelAutomaticTransitionToOpen() {
            if (transitionToOpenFuture != null && !transitionToOpenFuture.isDone()) {
                transitionToOpenFuture.cancel(true);
            }
        }

        private void toOpenState() {
            if (isHalfOpen.compareAndSet(true, false)) {
                transitionToOpenState();
            }
        }

        @Override
        public void releasePermission() {
            permittedNumberOfCalls.incrementAndGet();
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        @Override
        public void handlePossibleTransition(CircuitBreakerConfig.TransitionCheckResult result) {
            // noOp
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Transitions to open state when thresholds have been exceeded. Transitions to closed state
         * when thresholds have not been exceeded.
         *
         * @param result the result
         */
        private void checkIfThresholdsExceeded(Result result) {
            if (Result.hasExceededThresholds(result) && isHalfOpen.compareAndSet(true, false)) {
                transitionToOpenState();
            }
            if (result == BELOW_THRESHOLDS && isHalfOpen.compareAndSet(true, false)) {
                transitionToClosedState();
            }
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.HALF_OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }
}
