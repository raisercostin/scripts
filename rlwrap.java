//#!/usr/bin/env jbang
//JAVA 11+
//DEPS org.jline:jline:3.21.0

import java.io.*;
import java.nio.file.*;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.*;
import org.jline.terminal.TerminalBuilder;

public class rlwrap {
  public static void main(String[] args) throws Exception {
    Path histFile = Paths.get(System.getProperty("user.home"), ".adb_shell_history");
    Terminal terminal = TerminalBuilder.builder().system(true).build();
    LineReader reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .build();
    DefaultHistory history = new DefaultHistory(reader);
    if (Files.exists(histFile))
      history.load();
    reader.setVariable(LineReader.HISTORY_FILE, histFile);
    reader.setVariable(LineReader.HISTORY_SIZE, 10000);

    ProcessBuilder pb = new ProcessBuilder("adb", "shell")
        .redirectErrorStream(true);
    Process proc = pb.start();

    Thread outputThread = new Thread(() -> {
      try (InputStream in = proc.getInputStream()) {
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
          System.out.write(buf, 0, n);
        }
      } catch (IOException e) {
        // ignore
      }
    });
    outputThread.setDaemon(true);
    outputThread.start();

    OutputStream procIn = proc.getOutputStream();
    String prompt = "adb> ";
    String line;
    while ((line = reader.readLine(prompt)) != null) {
      history.add(line);
      procIn.write((line + "\n").getBytes());
      procIn.flush();
    }

    history.save();
    procIn.close();
    proc.waitFor();
  }
}
