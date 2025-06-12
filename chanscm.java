//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.fusesource.jansi:jansi:2.4.0

//https://docs.google.com/spreadsheets/d/15uvmySih-KCfDhBJ8UVFnd_taIHbJIN3/edit?gid=868106347#gid=868106347

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Help.Ansi;

import java.io.*;
import java.util.*;
import java.util.zip.*;

@Command(name = "chanscm", mixinStandardHelpOptions = true, description = "Import Samsung .scm files and list channel number + name")
public class chanscm implements Runnable {

  @Parameters(index = "0", description = "Path to the .scm file")
  File scmFile;

  public void run() {
    try {
      List<Map<String, String>> channels = parseChannels(scmFile);
      if (channels.isEmpty()) {
        System.out.println("No channels found.");
      } else {
        printTable(channels);
      }
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
    }
  }

  private List<Map<String, String>> listFiles(File file) throws IOException {
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

  private List<Map<String, String>> parseChannels(File file) throws IOException {
    try (ZipFile zip = new ZipFile(file)) {
      String channelEntry = null;
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        String name = en.nextElement().getName().toLowerCase();
        if (name.contains("channel") && name.endsWith(".dat")) {
          channelEntry = name;
          break;
        }
      }
      if (channelEntry == null) {
        throw new IOException("No channel-info .dat file found in archive");
      }

      ZipEntry entry = zip.getEntry(channelEntry);
      try (BufferedReader br = new BufferedReader(
          new InputStreamReader(zip.getInputStream(entry)))) {
        List<Map<String, String>> rows = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isBlank() || line.toLowerCase().startsWith("number")) {
            continue;
          }
          String[] parts = line.split("[;,\\t]");
          if (parts.length < 2)
            continue;
          Map<String, String> row = new LinkedHashMap<>();
          row.put("Number", parts[0].trim());
          row.put("Name", parts[1].trim());
          rows.add(row);
        }
        return rows;
      }
    }
  }

  private void printTable(List<Map<String, String>> rows) {
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

  private String pad(String s, int len) {
    return String.format("%-" + len + "s", s);
  }

  public static void main(String... args) {
    CommandLine cmd = new CommandLine(new chanscm());
    cmd.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.ON));
    int exitCode = cmd.execute(args);
    System.exit(exitCode);
  }
}
