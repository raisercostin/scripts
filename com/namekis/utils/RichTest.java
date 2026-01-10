package com.namekis.utils;
//DEPS org.slf4j:slf4j-api:2.0.7

//DEPS ch.qos.logback:logback-classic:1.4.11
//DEPS ch.qos.logback:logback-core:1.4.11

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RichTest provides CLI test helpers:
 * - runCommand captures stdout/stderr, includes command line + exit code in the result.
 * - runCommandExpect compares full output to expected multiline content.
 * - runCommandsParallel runs multiple commands concurrently and returns results in finish order.
 * Each run is logged (tee) via log.info while also returned for assertions.
 */
public class RichTest {
    private static final Logger log = LoggerFactory.getLogger(RichTest.class);

    public static class CommandSpec {
        public final Object command;
        public final List<String> args;

        public CommandSpec(Object command, List<String> args) {
            this.command = command;
            this.args = args;
        }
    }

    public static class CommandResult {
        public final String commandLine;
        public final int exitCode;
        public final String output;
        public final Instant finishedAt;

        public CommandResult(String commandLine, int exitCode, String output, Instant finishedAt) {
            this.commandLine = commandLine;
            this.exitCode = exitCode;
            this.output = output;
            this.finishedAt = finishedAt;
        }
    }

    public static CommandResult runCommand(Object command, String... args) {
        List<String> argList = List.of(args);
        return runCommand(command, argList);
    }

    public static CommandResult runCommand(Object command, List<String> args) {
        String commandLine = formatCommandLine(command, args);
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exitCode;
        String output;
        try {
            System.setOut(new PrintStream(outBuffer));
            System.setErr(new PrintStream(errBuffer));
            exitCode = new CommandLine(command).execute(args.toArray(String[]::new));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        output = outBuffer.toString() + errBuffer.toString();
        StringBuilder combined = new StringBuilder();
        combined.append("$ ").append(commandLine).append(System.lineSeparator());
        combined.append("exit: ").append(exitCode).append(System.lineSeparator());
        combined.append(output);
        CommandResult result = new CommandResult(commandLine, exitCode, combined.toString(), Instant.now());
        log.info(result.output);
        return result;
    }

    public static CommandResult runCommandExpect(Object command, String expectedOutput, String... args) {
        CommandResult result = runCommand(command, args);
        if (!result.output.equals(expectedOutput)) {
            String message = "Expected output did not match.\nExpected:\n" + expectedOutput
                    + "\nActual:\n" + result.output;
            throw new AssertionError(message);
        }
        return result;
    }

    public static List<CommandResult> runCommandsParallel(List<CommandSpec> commands) {
        if (commands.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(commands.size());
        try {
            List<CompletableFuture<CommandResult>> futures = new ArrayList<>();
            for (CommandSpec spec : commands) {
                futures.add(CompletableFuture.supplyAsync(() -> runCommand(spec.command, spec.args), executor));
            }
            List<CommandResult> results = new ArrayList<>();
            for (CompletableFuture<CommandResult> future : futures) {
                results.add(future.join());
            }
            results.sort(Comparator.comparing(r -> r.finishedAt));
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private static String formatCommandLine(Object command, List<String> args) {
        String name = command instanceof CommandLine ? ((CommandLine) command).getCommandName()
                : command.getClass().getSimpleName();
        List<String> tokens = new ArrayList<>();
        tokens.add(name);
        for (String arg : args) {
            tokens.add(quoteIfNeeded(arg));
        }
        return String.join(" ", tokens);
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (value.contains(" ")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }
}
