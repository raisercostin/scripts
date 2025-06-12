//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.fusesource.jansi:jansi:2.4.0

//https://docs.google.com/spreadsheets/d/15uvmySih-KCfDhBJ8UVFnd_taIHbJIN3/edit?gid=868106347#gid=868106347

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.*;
import java.util.*;
import java.util.zip.*;

@Command(name = "chanscm", mixinStandardHelpOptions = true, version = "chanscm 1.0", description = "Work with Samsung .scm channel archives", subcommands = {
    chanscm.FilesCommand.class,
    chanscm.ChannelsCommand.class
})
public class chanscm implements Runnable {
  @Spec
  private CommandSpec spec;

  public void run() {
    spec.commandLine().usage(System.out);
  }

  @Command(name = "files", mixinStandardHelpOptions = true, description = "List all files inside the SCM archive")
  static class FilesCommand implements Runnable {
    @Parameters(index = "0", description = "Path to the .scm or .zip file")
    File scmFile;

    public void run() {
      try {
        List<Map<String, String>> rows = listFiles(scmFile);
        if (rows.isEmpty()) {
          System.out.println("No entries found.");
        } else {
          printTable(rows);
        }
      } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
      }
    }
  }

  @Command(name = "channels", mixinStandardHelpOptions = true, description = "List channel Number + Name from the SCM archive")
  static class ChannelsCommand implements Runnable {
    @Parameters(index = "0", description = "Path to the .scm or .zip file")
    File scmFile;

    public void run() {
      try {
        List<Map<String, String>> rows = parseChannels(scmFile);
        if (rows.isEmpty()) {
          System.out.println("No channels found.");
        } else {
          printTable(rows);
        }
      } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
      }
    }
  }

  private static List<Map<String, String>> listFiles(File file) throws IOException {
    List<Map<String, String>> rows = new ArrayList<>();
    try (ZipFile zip = new ZipFile(file)) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Name", e.getName());
        row.put("Size", String.valueOf(e.getSize()));
        row.put("Compressed", String.valueOf(e.getCompressedSize()));
        row.put("Method", e.getMethod() == ZipEntry.DEFLATED ? "DEFLATED"
            : e.getMethod() == ZipEntry.STORED ? "STORED"
                : String.valueOf(e.getMethod()));
        rows.add(row);
      }
    }
    return rows;
  }

  private static List<String> findChannelEntries(ZipFile zip, String base) throws IOException {
    List<String> files = new ArrayList<>();
    Enumeration<? extends ZipEntry> en = zip.entries();
    while (en.hasMoreElements()) {
      String name = en.nextElement().getName();
      if (name.startsWith(base + "map-")) {
        files.add(name);
      }
    }
    return files;
  }

  private static List<Map<String, String>> parseChannels(File file) throws IOException {
    try (ZipFile zip = new ZipFile(file)) {
      String base = detectBasePath(zip, file.getName());
      List<String> entries = findChannelEntries(zip, base);
      if (entries.isEmpty()) {
        throw new IOException("No map-*.dat channel files found under \"" + base + "\"");
      }
      List<Map<String, String>> rows = new ArrayList<>();
      for (String entryName : entries) {
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(zip.getInputStream(zip.getEntry(entryName))))) {
          String line;
          while ((line = br.readLine()) != null) {
            if (line.isBlank() || line.toLowerCase().startsWith("number"))
              continue;
            String[] parts = line.split("[;,\\t]");
            if (parts.length < 2)
              continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Source", entryName); // which map file
            row.put("Number", parts[0].trim()); // remote-key number
            row.put("Name", parts[1].trim()); // channel name
            rows.add(row);
          }
        }
      }
      return rows;
    }
  }

  private static String detectBasePath(ZipFile zip, String fileName) throws IOException {
    if (fileName.toLowerCase().endsWith(".zip")) {
      if (zip.getEntry("Clone/map-AirD") != null) {
        return "Clone/";
      }
    }
    return "";
  }

  private static void printTable(List<Map<String, String>> rows) {
    List<String> cols = new ArrayList<>(rows.get(0).keySet());
    int[] widths = new int[cols.size()];
    for (int i = 0; i < cols.size(); i++) {
      widths[i] = cols.get(i).length();
    }
    for (Map<String, String> r : rows) {
      for (int i = 0; i < cols.size(); i++) {
        widths[i] = Math.max(widths[i], r.getOrDefault(cols.get(i), "").length());
      }
    }
    // Header
    for (int i = 0; i < cols.size(); i++) {
      System.out.print(pad(cols.get(i), widths[i]) + (i < cols.size() - 1 ? " | " : "\n"));
    }
    // Separator
    for (int i = 0; i < cols.size(); i++) {
      System.out.print("-".repeat(widths[i]) + (i < cols.size() - 1 ? "-+-" : "\n"));
    }
    // Rows
    for (Map<String, String> r : rows) {
      for (int i = 0; i < cols.size(); i++) {
        System.out.print(pad(r.getOrDefault(cols.get(i), ""), widths[i])
            + (i < cols.size() - 1 ? " | " : "\n"));
      }
    }
  }

  private static String pad(String s, int len) {
    return String.format("%-" + len + "s", s);
  }

  public static void main(String... args) {
    org.fusesource.jansi.AnsiConsole.systemInstall();
    try {
      CommandLine cmd = new CommandLine(new chanscm());
      cmd.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.ON));
      int exitCode = cmd.execute(args);
      System.exit(exitCode);
    } finally {
      org.fusesource.jansi.AnsiConsole.systemUninstall();
    }
  }
}
