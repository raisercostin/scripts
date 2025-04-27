// rlwrap.java
// A JBang-based drop-in replacement for https://github.com/hanslub42/rlwrap
// Wrap any command with line-editing & persistent history; prompt redraws immediately.

//#!/usr/bin/env jbang
//JAVA 11+
//DEPS org.jline:jline:3.21.0

import java.io.*;
import java.nio.file.*;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.*;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.impl.completer.NullCompleter;

public class rlwrap {
  public static void main(String[] args) throws Exception {
    // 1) Usage/help
    if (args.length == 0) {
      System.out.println(
          "Usage: rlwrap <command> [args...]\n" +
              "Wraps <command> with line-editing + persistent history.\n" +
              "Example: jbang rlwrap.java adb shell\n" +
              "This replaces hanslub42/rlwrap.\n" +
              "On Windows, for a true PTY, prefix with winpty:\n" +
              "  jbang rlwrap.java winpty adb shell");
      return;
    }

    // 2) Determine command to run (no defaults)
    String os = System.getProperty("os.name").toLowerCase();
    String[] cmd = args;
    if (os.contains("win")) {
      System.out.println("[rlwrap] on Windows: if you need a real PTY, prefix with `winpty`");
    }
    System.out.println("[rlwrap] spawning: " + String.join(" ", cmd));

    // 3) Prepare per-command history file
    String cmdName = Paths.get(cmd[0]).getFileName().toString();
    Path histFile = Paths.get(System.getProperty("user.home"))
        .resolve(".rlwrap_history_" + cmdName);
    System.out.println("[rlwrap] history → " + histFile);

    // 4) Init terminal & reader
    Terminal terminal = TerminalBuilder.builder()
        .system(true)
        .build();
    LineReader reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(NullCompleter.INSTANCE)
        .build();
    // turn off JLine’s completion engine entirely:
    reader.setOpt(LineReader.Option.DISABLE_COMPLETION);
    DefaultHistory history = new DefaultHistory(reader);
    try {
      if (Files.exists(histFile)) {
        history.load();
        System.out.println("[rlwrap] loaded " + history.size() + " entries");
      } else {
        Files.createDirectories(histFile.getParent());
        Files.createFile(histFile);
      }
    } catch (IOException e) {
      System.err.println("[rlwrap] could not load/create history: " + e.getMessage());
    }
    reader.setVariable(LineReader.HISTORY_FILE, histFile);
    reader.setVariable(LineReader.HISTORY_SIZE, 10000);

    // 5) Start the wrapped process
    ProcessBuilder pb = new ProcessBuilder(cmd)
        .redirectErrorStream(true);
    Process proc = pb.start();

    // 6) Forward remote output *above* prompt so it redraws immediately
    Thread outputThread = new Thread(() -> {
      try (
          BufferedReader br = new BufferedReader(
              new InputStreamReader(proc.getInputStream()))) {
        String oline;
        while ((oline = br.readLine()) != null) {
          reader.printAbove(oline);
        }
      } catch (IOException e) {
        System.err.println("[rlwrap] output error: " + e.getMessage());
      }
    });
    outputThread.setDaemon(true);
    outputThread.start();

    // 7) Read local lines & send to process stdin
    try (OutputStream procIn = proc.getOutputStream()) {
      String prompt = "> ";
      String line;
      while ((line = reader.readLine(prompt)) != null) {
        history.add(line);
        procIn.write((line + "\n").getBytes());
        procIn.flush();
      }
    } catch (IOException e) {
      System.err.println("[rlwrap] input error: " + e.getMessage());
    }

    // 8) Save history
    try {
      history.save();
      System.out.println("[rlwrap] history saved → " + histFile);
    } catch (IOException e) {
      System.err.println("[rlwrap] failed to save history: " + e.getMessage());
    }

    // 9) Wait for process to exit
    int code = proc.waitFor();
    System.out.println("[rlwrap] process exited with code " + code);
  }
}
