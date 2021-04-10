package io.cucumber.core.runtime;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.exception.UnrecoverableExceptions;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.runner.Runner;
import io.cucumber.messages.Messages;
import io.cucumber.messages.Messages.Envelope;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestSourceParsed;
import io.cucumber.plugin.event.TestSourceRead;

import java.time.Duration;
import java.time.Instant;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static io.cucumber.core.exception.ExceptionUtils.printStackTrace;
import static io.cucumber.core.exception.ExceptionUtils.throwAsUncheckedException;
import static io.cucumber.createmeta.CreateMeta.createMeta;
import static io.cucumber.messages.TimeConversion.javaInstantToTimestamp;
import static java.util.Collections.singletonList;

public final class CucumberExecutionContext {

    private static final String VERSION = ResourceBundle.getBundle("io.cucumber.core.version")
            .getString("cucumber-jvm.version");
    private static final Logger log = LoggerFactory.getLogger(CucumberExecutionContext.class);

    private final EventBus bus;
    private final ExitStatus exitStatus;
    private final RunnerSupplier runnerSupplier;
    private final RethrowingThrowableCollector collector = new RethrowingThrowableCollector();
    private Instant start;

    public CucumberExecutionContext(EventBus bus, ExitStatus exitStatus, RunnerSupplier runnerSupplier) {
        this.bus = bus;
        this.exitStatus = exitStatus;
        this.runnerSupplier = runnerSupplier;
    }

    public void startTestRun() {
        emitMeta();
        emitTestRunStarted();
    }

    private void emitMeta() {
        bus.send(Envelope.newBuilder()
                .setMeta(createMeta("cucumber-jvm", VERSION, System.getenv()))
                .build());
    }

    private void emitTestRunStarted() {
        log.debug(() -> "Sending run test started event");
        start = bus.getInstant();
        bus.send(new TestRunStarted(start));
        bus.send(Envelope.newBuilder()
                .setTestRunStarted(Messages.TestRunStarted.newBuilder()
                        .setTimestamp(javaInstantToTimestamp(start)))
                .build());
    }

    public void runBeforeAllHooks() {
        Runner runner = getRunner();
        collector.executeAndThrow(runner::runBeforeAllHooks);
    }

    public void runAfterAllHooks() {
        Runner runner = getRunner();
        collector.executeAndThrow(runner::runAfterAllHooks);
    }

    public void finishTestRun() {
        log.debug(() -> "Sending test run finished event");
        Throwable cucumberException = getThrowable();
        emitTestRunFinished(cucumberException);
    }

    public Throwable getThrowable() {
        return collector.getThrowable();
    }

    private void emitTestRunFinished(Throwable exception) {
        Instant instant = bus.getInstant();
        Result result = new Result(
            exception != null ? Status.FAILED : exitStatus.getStatus(),
            Duration.between(start, instant),
            exception);
        bus.send(new TestRunFinished(instant, result));

        Messages.TestRunFinished.Builder testRunFinished = Messages.TestRunFinished.newBuilder()
                .setSuccess(exception == null && exitStatus.isSuccess())
                .setTimestamp(javaInstantToTimestamp(instant));

        if (exception != null) {
            testRunFinished.setMessage(printStackTrace(exception));
        }
        bus.send(Envelope.newBuilder()
                .setTestRunFinished(testRunFinished)
                .build());
    }

    public void beforeFeature(Feature feature) {
        log.debug(() -> "Sending test source read event for " + feature.getUri());
        bus.send(new TestSourceRead(bus.getInstant(), feature.getUri(), feature.getSource()));
        bus.send(new TestSourceParsed(bus.getInstant(), feature.getUri(), singletonList(feature)));
        bus.sendAll(feature.getParseEvents());
    }

    public void runTestCase(Consumer<Runner> execution) {
        Runner runner = getRunner();
        collector.executeAndThrow(() -> execution.accept(runner));
    }

    private Runner getRunner() {
        return collector.executeAndThrow(runnerSupplier::get);
    }

}
