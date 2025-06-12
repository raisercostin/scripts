
//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.fusesource.jansi:jansi:2.4.2

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "scmlist", mixinStandardHelpOptions = true, description = "Imports Samsung .scm files and lists contents as a table.")
class chanscm implements Runnable {
  @Parameters(index = "0", description = "Path to the .scm file")
  File scmFile;

  public void run() {
    try {
      List<Map<String, String>> channels = parseScm(scmFile);
      if (channels.isEmpty()) {
        System.out.println("No channels found.");
        return;
      }
      printTable(channels);
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
    }
  }

  private List<Map<String, String>> parseScm(File scmFile) throws IOException {
    List<Map<String, String>> list = new ArrayList<>();
    try (ZipFile zip = new ZipFile(scmFile)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Name", e.getName());
        row.put("Size", String.valueOf(e.getSize()));
        row.put("Compressed", String.valueOf(e.getCompressedSize()));
        row.put("Method", e.getMethod() == ZipEntry.DEFLATED ? "DEFLATED"
            : e.getMethod() == ZipEntry.STORED ? "STORED"
                : String.valueOf(e.getMethod()));
        list.add(row);
      }
    } catch (ZipException ze) {
      throw new IOException("Not a ZIP/SCM archive: " + ze.getMessage(), ze);
    }
    return list;
  }

  // -- Anchor: Table print, JS-style
  private void printTable(List<Map<String, String>> rows) {
    List<String> columns = new ArrayList<>(rows.get(0).keySet());
    int[] widths = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++)
      widths[i] = columns.get(i).length();

    for (Map<String, String> row : rows)
      for (int i = 0; i < columns.size(); i++)
        widths[i] = Math.max(widths[i], row.getOrDefault(columns.get(i), "").length());

    // Header
    for (int i = 0; i < columns.size(); i++)
      System.out.print(pad(columns.get(i), widths[i]) + (i < columns.size() - 1 ? " | " : "\n"));
    // Separator
    for (int i = 0; i < columns.size(); i++)
      System.out.print("-".repeat(widths[i]) + (i < columns.size() - 1 ? "-+-" : "\n"));
    // Rows
    for (Map<String, String> row : rows) {
      for (int i = 0; i < columns.size(); i++)
        System.out
            .print(pad(row.getOrDefault(columns.get(i), ""), widths[i]) + (i < columns.size() - 1 ? " | " : "\n"));
    }
  }

  private String pad(String s, int len) {
    return String.format("%-" + len + "s", s);
  }

  public static void main(String... args) {
    org.fusesource.jansi.AnsiConsole.systemInstall();
    try {
      var cmd = new CommandLine(new chanscm());
      cmd.setColorScheme(CommandLine.Help.defaultColorScheme(Help.Ansi.ON));
      int exitCode = cmd.execute(args);
      System.exit(exitCode);
    } finally {
      org.fusesource.jansi.AnsiConsole.systemUninstall();
    }
  }
}
