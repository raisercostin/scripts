//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS org.ini4j:ini4j:0.5.4
//DEPS ch.qos.logback:logback-classic:1.4.11
//DEPS org.slf4j:slf4j-api:2.0.7

//https://docs.google.com/spreadsheets/d/15uvmySih-KCfDhBJ8UVFnd_taIHbJIN3/edit?gid=868106347#gid=868106347

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
          printTable(rows, "New Pos", "Channel name");
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

  private static List<Map<String, String>> parseChannels(File scmFile) throws IOException {
    // prep
    String series = detectSeries(scmFile);
    SeriesConfig sc = loadSeriesConfig(series);

    Wini ini = loadModelIni();
    try (ZipFile zip = new ZipFile(scmFile)) {
      // load the per-transponder frequency table (DVB-CT/PTC)
      Map<Integer, BigDecimal> freqTable = loadDvbTransponderFrequencies(zip, ini, series);
      // load the service-provider names
      Map<Integer, String> providerNames = loadServiceProviderNames(zip, ini, series);
      // load the satellite name/operator map
      Map<Integer, Satellite> sats = loadSatellites(zip, ini, series);

      String base = detectBasePath(zip, scmFile.getName());
      List<String> entries = findChannelEntries(zip, base, sc);
      List<Map<String, String>> rows = new ArrayList<>();

      for (String entryName : entries) {
        ModelConstants mc = getMappingConstants(sc, entryName);
        String sectName = getSectionNameFor(entryName, mc.recordLength);
        Section section = ini.get(sectName);
        DataMapping mapping = new DataMapping(section);

        byte[] data = zip.getInputStream(zip.getEntry(entryName)).readAllBytes();
        int count = data.length / mc.recordLength;

        for (int i = 0; i < count; i++) {
          int off = i * mc.recordLength;
          mapping.setDataPtr(data, off);

          // 1) old position
          int oldPos = mapping.getWord("offProgramNr");
          if (oldPos == 0)
            continue;

          // 2) in-use check (default true if missing)
          if (!mapping.getFlag("InUse", true))
            continue;

          // 3) new position (logical) if available
          int newPos = mapping.offsets.containsKey("offLogicalProgramNr")
              ? (data[off + mapping.offsets.get("offLogicalProgramNr")] & 0xFF)
                  | ((data[off + mapping.offsets.get("offLogicalProgramNr") + 1] & 0xFF) << 8)
              : 0;

          // 4) names
          String name = mapping.getString("offName", mc.lenName);
          String shortName = "";
          if (section.containsKey("offShortName")) {
            int shortLen = Integer.parseInt(section.getOrDefault("lenShortName", "0"));
            shortName = mapping.getString("offShortName", shortLen);
          }

          // 5) IDs
          int onid = mapping.getWord("offOriginalNetworkId");
          int tsid = mapping.getWord("offTransportStreamId");
          int sid = mapping.getWord("offServiceId");

          // 6) flags
          String fav = mapping.getFavorites() > 0 ? "Checked" : "Unchecked";
          String lock = mapping.getFlag("Lock", false) ? "Checked" : "Unchecked";
          String skip = mapping.getFlag("Skip", false) ? "Checked" : "Unchecked";
          String hide = mapping.getFlag("Hidden", false) ? "Checked" : "Unchecked";
          String crypt = mapping.getFlag("Encrypted", false) ? "Checked" : "Unchecked";

          // 7) service type
          int svcTypeCode = mapping.getWord("offServiceType");
          String svcType = svcTypeCode == 1 ? "HD-TV" : "SD-TV";

          // 8) frequency & transponder
          int transpIdx = mapping.getWord("offChannelTransponder");
          BigDecimal freq = freqTable.getOrDefault(transpIdx, BigDecimal.ZERO);

          // 9) PCR, Video, SymbolRate
          int pcr = mapping.getWord("offPcrPid");
          int vidPid = mapping.getWord("offVideoPid");
          int symRate = mapping.getWord("offSymbolRate");

          // 10) provider lookup
          int src = mapping.offsets.containsKey("offSignalSource")
              ? mapping.getWord("offSignalSource")
              : 0;
          int provIndex = mapping.offsets.containsKey("offServiceProviderId")
              ? mapping.getWord("offServiceProviderId")
              : 0;
          String provKey = providerNames.get((src << 16) + provIndex);

          // 11) network names via satellite index
          int satIdx = mapping.offsets.containsKey("offTransponderIndex")
              ? mapping.getWord("offTransponderIndex")
              : -1;
          Satellite sat = sats.get(satIdx);
          String netName = sat != null ? sat.getName() : "";
          String netOp = sat != null ? sat.getOperator() : "";

          // assemble row
          Map<String, String> row = new LinkedHashMap<>();
          row.put("Old Pos", String.valueOf(oldPos));
          row.put("New Pos", newPos > 0 ? String.valueOf(newPos) : "");
          row.put("Channel name", name);
          row.put("Short name", shortName);
          row.put("Network (ONID)", String.valueOf(onid));
          row.put("TS ID", String.valueOf(tsid));
          row.put("Service ID", String.valueOf(sid));
          row.put("Favorites", fav);
          row.put("Locked", lock);
          row.put("Skip", skip);
          row.put("Hide", hide);
          row.put("Crypt", crypt);
          row.put("Service Type", svcType);
          row.put("Frequency (MHz)", freq.toPlainString());
          row.put("Chan/ Transp", String.valueOf(transpIdx));
          row.put("PCR PID", String.valueOf(pcr));
          row.put("Video PID", String.valueOf(vidPid));
          row.put("Symbol rate", String.valueOf(symRate));
          row.put("Network Name", netName);
          row.put("Network Operator", netOp);
          row.put("Provider", provKey != null ? provKey : "");

          rows.add(row);
        }
      }
      return rows;
    }
  }

  // -----------------------------------------------------------------------------
  // Helper stubs – you’ll need to port these directly from ScmSerializer.cs:
  // loadDvbTransponderFrequencies(...) (ReadDvbTransponderFrequenciesFromPtc)
  // :contentReference[oaicite:11]{index=11}
  // loadServiceProviderNames(...) (ReadDvbServiceProviders)
  // :contentReference[oaicite:12]{index=12}
  // loadSatellites(...) (ReadSatellites) :contentReference[oaicite:13]{index=13}
  // -----------------------------------------------------------------------------

  // picks the right INI section name for this map entry
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

  // helper to read any off*/mask* fields from the INI section
  static class DataMapping {
    private final Section settings;
    private final Map<String, Integer> offsets = new HashMap<>();
    private byte[] data;
    private int base;

    DataMapping(Section section) {
      this.settings = section;
      for (var e : section.entrySet()) {
        String k = e.getKey();
        if (k.startsWith("off") || k.startsWith("mask")) {
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

    boolean getFlag(String name) {
      return getFlag(name, false);
    }

    boolean getFlag(String name, boolean defaultValue) {
      String offKey = "off" + name;
      Integer offVal = offsets.get(offKey);
      if (offVal == null) {
        return defaultValue;
      }
      String maskKey = "mask" + name;
      int mask = settings.containsKey(maskKey)
          ? Integer.decode(settings.get(maskKey))
          : 1;
      return (data[base + offVal] & mask) != 0;
    }

    int getWord(String offKey) {
      Integer off = offsets.get(offKey);
      if (off == null)
        return 0;
      return ((data[base + off] & 0xFF) << 8) | (data[base + off + 1] & 0xFF);
    }

    String getString(String offKey, int maxChars) {
      Integer off = offsets.get(offKey);
      if (off == null)
        return "";
      int len = maxChars * 2;
      byte[] buf = Arrays.copyOfRange(data, base + off, base + off + len);
      String s = new String(buf, StandardCharsets.UTF_16BE);
      int i = s.indexOf('\0');
      return i >= 0 ? s.substring(0, i) : s;
    }

    float getFloat(String offKey) {
      Integer off = offsets.get(offKey);
      if (off == null || off + 3 >= data.length)
        return Float.NaN;
      int bits = ((data[base + off] & 0xFF) << 24)
          | ((data[base + off + 1] & 0xFF) << 16)
          | ((data[base + off + 2] & 0xFF) << 8)
          | (data[base + off + 3] & 0xFF);
      return Float.intBitsToFloat(bits);
    }

    int getFavorites() {
      int mask = 0;
      for (int bit = 0; bit < 32; bit++) {
        String key = "offFavorite" + bit;
        Integer off = offsets.get(key);
        if (off != null && (data[base + off] & 0xFF) != 0) {
          mask |= (1 << bit);
        }
      }
      return mask;
    }
  }

  // === 2) DVB-CT transponder → frequency table ===
  private static Map<Integer, BigDecimal> loadDvbTransponderFrequencies(ZipFile zip, Wini ini, String series)
      throws IOException {
    Map<Integer, BigDecimal> table = new LinkedHashMap<>();
    Section ser = ini.get(series);
    int ptcLength = ser.get("PTC", int.class, 12);

    // section holding offsets for PTC records
    Section ptcSect = ini.get("PTC:" + ptcLength);
    DataMapping mapping = new DataMapping(ptcSect);

    for (String fileName : List.of("PTCAIR", "PTCCABLE")) {
      ZipEntry entry = zip.getEntry(fileName);
      if (entry == null)
        continue;
      byte[] data = zip.getInputStream(entry).readAllBytes();

      int count = data.length / ptcLength;
      for (int i = 0; i < count; i++) {
        mapping.setDataPtr(data, i * ptcLength);
        int transp = mapping.getWord("offChannelTransponder");
        float freq = mapping.getFloat("offFrequency");
        table.put(transp, BigDecimal.valueOf(freq));
      }
    }
    return table;
  }

  // === 3) DVB service-provider names ===
  private static Map<Integer, String> loadServiceProviderNames(ZipFile zip, Wini ini, String series)
      throws IOException {
    Map<Integer, String> names = new LinkedHashMap<>();
    Section ser = ini.get(series);
    int spLen = ser.get("ServiceProvider", int.class, 108);

    Section spSect = ini.get("ServiceProvider:" + spLen);
    DataMapping mapping = new DataMapping(spSect);

    ZipEntry entry = zip.getEntry("ServiceProviders");
    if (entry == null)
      return names;
    byte[] data = zip.getInputStream(entry).readAllBytes();
    if (data.length % spLen != 0)
      return names;

    int offName = spSect.get("offName", int.class);
    int count = data.length / spLen;
    for (int i = 0; i < count; i++) {
      int base = i * spLen;
      mapping.setDataPtr(data, base);
      int source = mapping.getWord("offSignalSource");
      int index = mapping.getWord("offIndex");
      int len = Math.min(mapping.getWord("offLenName"), spLen - offName);
      String nm = len < 2
          ? ""
          : new String(data, base + offName, len, StandardCharsets.UTF_16BE);
      names.put((source << 16) | index, nm);
    }
    return names;
  }

  // === 4) Satellite list (name + orbital position) ===
  private static class Satellite {
    private final String name;
    private final String position;

    Satellite(String name, String position) {
      this.name = name;
      this.position = position;
    }

    public String getName() {
      return name;
    }

    public String getOperator() {
      return position;
    }
  }

  private static Map<Integer, Satellite> loadSatellites(ZipFile zip, Wini ini, String series) throws IOException {
    Map<Integer, Satellite> sats = new LinkedHashMap<>();
    Section ser = ini.get(series);
    int satLen = ser.get("SatDataBase.dat", int.class);

    ZipEntry entry = zip.getEntry("SatDataBase.dat");
    if (entry == null)
      return sats;
    byte[] data = zip.getInputStream(entry).readAllBytes();
    if (data.length < 4)
      return sats;

    // skip the 4-byte version header
    int count = data.length / satLen;
    for (int i = 0; i < count; i++) {
      int off = 4 + i * satLen;
      if (data[off] != 'U')
        continue;

      // little-endian ints
      int satNr = ByteBuffer.wrap(data, off + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
      int lonRaw = ByteBuffer.wrap(data, off + 141, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
      boolean isEast = ByteBuffer.wrap(data, off + 137, 4)
          .order(ByteOrder.LITTLE_ENDIAN).getInt() != 0;
      String pos = String.format("%d.%d%s", lonRaw / 10, lonRaw % 10, isEast ? "E" : "W");

      // UTF-16BE name, 128 chars at offset+9
      String name = new String(
          Arrays.copyOfRange(data, off + 9, off + 9 + 128),
          StandardCharsets.UTF_16BE);
      int z = name.indexOf('\0');
      if (z >= 0)
        name = name.substring(0, z);

      sats.put(satNr, new Satellite(name, pos));
    }
    return sats;
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

  private static void printTable(List<Map<String, String>> rows, String... columns) {
    List<String> cols = columns.length == 0 ? new ArrayList<>(rows.get(0).keySet()) : Arrays.asList(columns);
    if (!rows.isEmpty()) {
      System.out.println("Available columns: " + rows.get(0).keySet());
    }
    int[] widths = new int[cols.size()];
    // header widths
    for (int i = 0; i < cols.size(); i++) {
      widths[i] = cols.get(i).length();
    }
    // data widths
    for (Map<String, String> r : rows) {
      for (int i = 0; i < cols.size(); i++) {
        String v = r.getOrDefault(cols.get(i), "");
        widths[i] = Math.max(widths[i], v.length());
      }
    }
    // header
    for (int i = 0; i < cols.size(); i++) {
      System.out.print(pad(cols.get(i), widths[i]) + (i < cols.size() - 1 ? " | " : "\n"));
    }
    // separator
    for (int i = 0; i < cols.size(); i++) {
      System.out.print("-".repeat(widths[i]) + (i < cols.size() - 1 ? "-+-" : "\n"));
    }
    // rows
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
