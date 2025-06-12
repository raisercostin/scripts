//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS org.ini4j:ini4j:0.5.4
//DEPS ch.qos.logback:logback-classic:1.4.11
//DEPS org.slf4j:slf4j-api:2.0.7

//https://docs.google.com/spreadsheets/d/15uvmySih-KCfDhBJ8UVFnd_taIHbJIN3/edit?gid=868106347#gid=868106347

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.ini4j.Wini;
import org.ini4j.Profile.Section;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "chanscm", mixinStandardHelpOptions = true, version = "chanscm 1.0", description = "Work with Samsung .scm channel archives", subcommands = {
    chanscm.FilesCommand.class,
    chanscm.ChannelsCommand.class
})
public class chanscm implements Runnable {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(chanscm.class);

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
        throw new RuntimeException("Failed to list files in " + scmFile, e);
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
        throw new RuntimeException("Failed to list files in " + scmFile, e);
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

  private static List<String> findChannelEntries(ZipFile zip, String base, SeriesConfig sc) {
    List<String> files = new ArrayList<>();
    Enumeration<? extends ZipEntry> en = zip.entries();
    while (en.hasMoreElements()) {
      String full = en.nextElement().getName();
      if (!full.startsWith(base + "map-"))
        continue;

      String name = full.substring(full.lastIndexOf('/') + 1);
      try {
        sc.getRecordLength(name); // will throw if no config
        files.add(full);
      } catch (IOException e) {
        // skip entries without a matching record length
      }
    }
    return files;
  }

  // 1) Orchestrator
  private static String detectSeries(File scmFile) throws IOException {
    // 1a. Filename
    String series = detectSeriesFromFileName(scmFile);
    if (series != null)
      return series;

    try (ZipFile zip = new ZipFile(scmFile)) {
      // 2. CloneInfo
      series = detectSeriesFromCloneInfo(zip);
      if (series != null)
        return series;
      // 3. File‐length heuristics
      series = detectSeriesFromContentFileLengths(zip);
      if (series != null)
        return series;
    }
    throw new IOException("Unable to detect series for file: " + scmFile.getName());
  }

  // 1a) Filename‐based detection
  private static String detectSeriesFromFileName(File scmFile) {
    String name = scmFile.getName().toLowerCase();
    if (name.endsWith(".zip")) {
      return "Series:E";
    }
    Matcher m = Pattern.compile(".*_(\\d{4})\\.scm$").matcher(scmFile.getName());
    if (m.matches()) {
      switch (m.group(1)) {
        case "1001":
          return "Series:C";
        case "1101":
          return "Series:D";
        case "1201":
          return "Series:E";
        default:
          return null;
      }
    }
    return null;
  }

  // 2) CloneInfo‐based detection
  private static String detectSeriesFromCloneInfo(ZipFile zip) throws IOException {
    ZipEntry e = zip.getEntry("CloneInfo");
    if (e == null) {
      // no CloneInfo ⇒ assume B
      return "Series:B";
    }
    byte[] data = zip.getInputStream(e).readAllBytes();
    if (data.length <= 8)
      return null;
    char c = (char) data[8];
    if (c == 'B')
      c = 'E';
    else if (c == 'C')
      return null;
    else if ("EFHJ".indexOf(c) >= 0)
      c = 'E';
    return "Series:" + c;
  }

  // 3) Content‐length heuristic detection
  private static String detectSeriesFromContentFileLengths(ZipFile zip) {
    List<Set<Character>> candidates = new ArrayList<>();

    // map-AirA: 320⇒D/E, 292⇒C, 248⇒B
    candidates.add(detectLetterByLength(zip, "map-AirA",
        Map.of(320, Set.of('D', 'E'), 292, Set.of('C'), 248, Set.of('B'))));
    // map-AirD
    candidates.add(detectLetterByLength(zip, "map-AirD",
        Map.of(320, Set.of('D', 'E'), 292, Set.of('C'), 248, Set.of('B'))));
    // map-CableD
    candidates.add(detectLetterByLength(zip, "map-CableD",
        Map.of(520, Set.of('D', 'E'), 484, Set.of('C'), 412, Set.of('B'))));

    // intersect all sets, keep B/C/D/E
    Set<Character> valid = new HashSet<>(Set.of('B', 'C', 'D', 'E'));
    for (Set<Character> s : candidates) {
      if (s != null)
        valid.retainAll(s);
    }
    if (valid.size() == 1) {
      return "Series:" + valid.iterator().next();
    }
    return null;
  }

  private static Set<Character> detectLetterByLength(
      ZipFile zip, String entryName, Map<Integer, Set<Character>> map) {
    ZipEntry e = zip.getEntry(entryName);
    if (e == null)
      return null;
    long len = e.getSize();
    for (var kv : map.entrySet()) {
      if (len % kv.getKey() == 0) {
        return kv.getValue();
      }
    }
    return null;
  }

  static class Channel {
    final Map<String, Object> values;
    final DataMapping mapping;

    Channel(Map<String, Object> values, DataMapping mapping) {
      this.values = values;
      this.mapping = mapping;
    }
  }

  private static List<Map<String, String>> parseChannels(File scmFile) throws IOException {
    String series = detectSeries(scmFile);
    logger.info("detected series: {}", series);

    SeriesConfig sc = loadSeriesConfig(series);
    logger.info("detected config: {}", sc);

    Wini ini = loadModelIni();

    List<Map<String, String>> rows = new ArrayList<>();
    try (ZipFile zip = new ZipFile(scmFile)) {
      String base = detectBasePath(zip, scmFile.getName());
      List<String> entries = findChannelEntries(zip, base, sc);

      for (String entryName : entries) {
        // pick mapping section and record length
        ModelConstants mc = getMappingConstants(sc, entryName);
        String sectionName = getSectionNameFor(entryName, mc.recordLength);
        Section section = ini.get(sectionName);
        DataMapping mapping = new DataMapping(section);

        ZipEntry entry = zip.getEntry(entryName);
        byte[] data = zip.getInputStream(entry).readAllBytes();
        int count = data.length / mc.recordLength;

        for (int i = 0; i < count; i++) {
          int off = i * mc.recordLength;
          mapping.setDataPtr(data, off);

          // skip unused
          if (!mapping.getFlag("InUse"))
            continue;

          // raw big-endian program number
          int rawProg = mapping.getWord("offProgramNr");
          // logical or slot little-endian if present
          int number;
          if (mapping.offsets.containsKey("offLogicalProgramNr")) {
            int o = mapping.offsets.get("offLogicalProgramNr");
            number = (data[off + o] & 0xFF) | ((data[off + o + 1] & 0xFF) << 8);
          } else if (mapping.offsets.containsKey("offSlotNr")) {
            int o = mapping.offsets.get("offSlotNr");
            number = (data[off + o] & 0xFF) | ((data[off + o + 1] & 0xFF) << 8);
          } else {
            number = rawProg;
          }
          if (number <= 0)
            continue;

          Map<String, String> row = new LinkedHashMap<>();
          row.put("Source", entryName);
          row.put("ProgramNr_raw", String.valueOf(rawProg));
          row.put("ProgramNr_remote", String.valueOf(number));
          row.put("Name", mapping.getString("offName", mc.lenName));
          // ShortName if available
          if (section.containsKey("offShortName")) {
            int lenS = Integer.parseInt(section.getOrDefault("lenShortName", "0"));
            row.put("ShortName", mapping.getString("offShortName", lenS));
          }
          // Favorites as bitmask
          row.put("FavoritesMask", String.valueOf(mapping.getFavorites()));
          // Boolean flags
          for (String flag : List.of("Lock", "Deleted", "IsActive", "Encrypted", "Skip", "Hidden", "HiddenAlt")) {
            if (mapping.offsets.containsKey("off" + flag)) {
              row.put(flag, String.valueOf(mapping.getFlag(flag)));
            }
          }
          // DVB-CT fields
          if (mapping.offsets.containsKey("offServiceId")) {
            row.put("ServiceType", String.valueOf(mapping.getWord("offServiceType")));
            row.put("ServiceId", String.valueOf(mapping.getWord("offServiceId")));
            row.put("OriginalNetworkId", String.valueOf(mapping.getWord("offOriginalNetworkId")));
            row.put("TransportStreamId", String.valueOf(mapping.getWord("offTransportStreamId")));
            row.put("VideoPid", String.valueOf(mapping.getWord("offVideoPid")));
            row.put("AudioPid", String.valueOf(mapping.getWord("offAudioPid")));
            row.put("PcrPid", String.valueOf(mapping.getWord("offPcrPid")));
            row.put("SymbolRate", String.valueOf(mapping.getWord("offSymbolRate")));
          }
          // DVB-S fields
          if (mapping.offsets.containsKey("offTransponderIndex")) {
            row.put("TransponderIndex", String.valueOf(mapping.getWord("offTransponderIndex")));
            row.put("SatelliteIndex", String.valueOf(mapping.getWord("offSatelliteIndex")));
          }
          // Analog frequency/slot
          if (mapping.offsets.containsKey("offFrequency")) {
            row.put("Frequency", String.valueOf(
                ((data[off + mapping.offsets.get("offFrequency")] & 0xFF) << 8)
                    | (data[off + mapping.offsets.get("offFrequency") + 1] & 0xFF)));
            row.put("SlotNr", String.valueOf(mapping.getWord("offSlotNr")));
          }

          rows.add(row);
        }
      }
    }
    return rows;
  }

  // maps ZIP entry → INI section name
  private static String getSectionNameFor(String entryName, int recLen) {
    String name = entryName.contains("/")
        ? entryName.substring(entryName.lastIndexOf('/') + 1)
        : entryName;
    if (name.startsWith("map-AirA") || name.startsWith("map-CableA"))
      return "Analog:" + recLen;
    if (name.startsWith("map-AirD") || name.startsWith("map-CableD"))
      return "DvbCT:" + recLen;
    if (name.startsWith("map-SateD"))
      return "DvbS:" + recLen;
    if (name.startsWith("map-AstraHDPlusD"))
      return "AstraHDPlusD:" + recLen;
    if (name.startsWith("map-CyfraPlusD"))
      return "CyfraPlusD:" + recLen;
    throw new IllegalArgumentException("Unknown channel type: " + name);
  }

  // minimal Java port of ChanSort’s DataMapping
  static class DataMapping {
    private final Section settings;
    private final Map<String, Integer> offsets = new HashMap<>();
    private byte[] data;
    private int base;

    DataMapping(Section section) {
      this.settings = section;
      // collect every "offXxx" key into offsets map
      for (Map.Entry<String, String> e : section.entrySet()) {
        String k = e.getKey();
        if (k.startsWith("off")) {
          try {
            offsets.put(k, Integer.decode(e.getValue()));
          } catch (NumberFormatException ignore) {
          }
        }
      }
    }

    void setDataPtr(byte[] data, int base) {
      this.data = data;
      this.base = base;
    }

    int getWord(String offKey) {
      int off = offsets.get(offKey);
      // big-endian for ProgramNr; we handle logical/slot little-endian in parser
      // below
      return ((data[base + off] & 0xFF) << 8) | (data[base + off + 1] & 0xFF);
    }

    String getString(String offKey, int maxChars) {
      int off = offsets.get(offKey);
      int len = maxChars * 2;
      byte[] buf = Arrays.copyOfRange(data, base + off, base + off + len);
      String s = new String(buf, StandardCharsets.UTF_16BE);
      int i = s.indexOf('\0');
      return (i >= 0) ? s.substring(0, i) : s;
    }

    boolean getFlag(String prefix) {
      String offKey = "off" + prefix;
      Integer offVal = offsets.get(offKey);
      if (offVal == null) {
        // flag not defined → treat as false
        return false;
      }
      String maskKey = "mask" + prefix;
      int mask = settings.containsKey(maskKey)
          ? Integer.decode(settings.get(maskKey))
          : 1;
      int off = offVal;
      return (data[base + off] & mask) != 0;
    }

    int getFavorites() {
      // ChanSort stores favorites as multiple offFavoriteN offsets
      int favMask = 0;
      for (int bit = 0; bit < 32; bit++) {
        String key = "offFavorite" + bit;
        if (offsets.containsKey(key)) {
          int off = offsets.get(key);
          if ((data[base + off] & 0xFF) != 0) {
            favMask |= (1 << bit);
          }
        }
      }
      return favMask;
    }
  }

  static class ModelConstants {
    final int recordLength;
    final int offProgramNr;
    final int offName;
    final int lenName;
    final int offLogicalProgramNr;
    final int offSlotNr;

    ModelConstants(int recordLength,
        int offProgramNr,
        int offName,
        int lenName,
        int offLogicalProgramNr,
        int offSlotNr) {
      this.recordLength = recordLength;
      this.offProgramNr = offProgramNr;
      this.offName = offName;
      this.lenName = lenName;
      this.offLogicalProgramNr = offLogicalProgramNr;
      this.offSlotNr = offSlotNr;
    }

    public String toString() {
      return String.format(
          "MC[len=%d,prog=%d,logical=%d,slot=%d,nameOff=%d,lenName=%d]",
          recordLength, offProgramNr, offLogicalProgramNr, offSlotNr, offName, lenName);
    }
  }

  private static final String INI_URL = "https://raw.githubusercontent.com/PredatH0r/ChanSort/master/" +
      "ChanSort.Loader.Samsung/ChanSort.Loader.Samsung.ini";
  private static final Path INI_PATH = Paths.get("ChanSort.Loader.Samsung.ini");

  private static Wini loadModelIni() throws IOException {
    if (Files.notExists(INI_PATH)) {
      try (var in = new URL(INI_URL).openStream()) {
        Files.copy(in, INI_PATH, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return new Wini(INI_PATH.toFile());
  }

  // 1) Holds all map-*.dat lengths for a series
  static record SeriesConfig(Map<String, Integer> recLen) {
    int getRecordLength(String entryName) throws IOException {
      // strip any “Clone/” or folder prefix
      String name = entryName.contains("/")
          ? entryName.substring(entryName.lastIndexOf('/') + 1)
          : entryName;
      Integer len = recLen.get(name);
      if (len == null) {
        throw new IOException("No record length for map file '"
            + name + "' in series config");
      }
      return len;
    }
  }

  // 2) Load the [Series:X] section into SeriesConfig
  private static SeriesConfig loadSeriesConfig(String series) throws IOException {
    Wini ini = loadModelIni();
    Section s = ini.get(series);
    Map<String, Integer> recs = new HashMap<>();
    for (String key : List.of("map-AirA", "map-AirD", "map-CableD",
        "map-SateD", "map-AstraHDPlusD", "map-CyfraPlusD")) {
      String val = s.get(key);
      if (val != null)
        recs.put(key, Integer.parseInt(val));
    }
    return new SeriesConfig(recs);
  }

  // 3) Given a ZIP entry name and the SeriesConfig, pick the right INI section
  // and read the three offsets (program number, name offset, name length).
  private static ModelConstants getMappingConstants(
      SeriesConfig sc, String entryName) throws IOException {
    int recLen = sc.getRecordLength(entryName);
    String section;
    if (entryName.contains("map-AirA") || entryName.contains("map-CableA")) {
      section = "Analog:" + recLen;
    } else if (entryName.contains("map-AirD") || entryName.contains("map-CableD")) {
      section = "DvbCT:" + recLen;
    } else if (entryName.contains("map-SateD")) {
      section = "DvbS:" + recLen;
    } else if (entryName.contains("map-AstraHDPlusD")) {
      section = "AstraHDPlusD:" + recLen;
    } else if (entryName.contains("map-CyfraPlusD")) {
      section = "CyfraPlusD:" + recLen;
    } else {
      throw new IOException("Unknown channel type: " + entryName);
    }

    Wini ini = loadModelIni();
    Section m = ini.get(section);
    int offProg = Integer.parseInt(m.get("offProgramNr"));
    int offName = Integer.parseInt(m.get("offName"));
    int lenName = Integer.parseInt(m.getOrDefault("lenName", m.get("offNameLength")));
    int offLogical = m.containsKey("offLogicalProgramNr")
        ? Integer.parseInt(m.get("offLogicalProgramNr"))
        : -1;
    int offSlot = m.containsKey("offSlotNr")
        ? Integer.parseInt(m.get("offSlotNr"))
        : -1;

    return new ModelConstants(recLen, offProg, offName, lenName, offLogical, offSlot);
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
