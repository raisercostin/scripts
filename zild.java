//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.slf4j:slf4j-api:2.0.12
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS one.util:streamex:0.8.2
//SOURCES com/namekis/utils/RichLogback.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.namekis.utils.RichLogback;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class zild {
  public static void main(String... args) {
    AnsiConsole.systemInstall();
    try {
      int exitCode = new CommandLine(new ZildRoot()).execute(args);
      System.exit(exitCode);
    } finally {
      AnsiConsole.systemUninstall();
    }
  }

  public static abstract class CommonOptions {
    @Option(names = { "-v", "--verbose" }, description = "Increase verbosity. Use multiple (-vvv)")
    boolean[] verbosity = new boolean[0];

    @Option(names = { "-q", "--quiet" }, description = "Silence all output except errors")
    boolean quiet = false;

    @Option(names = { "-c",
        "--color" }, description = "Enable colored output (default: true)", defaultValue = "true", showDefaultValue = Visibility.ALWAYS)
    boolean color = true;
  }

  @Command(name = "zild", mixinStandardHelpOptions = true, subcommands = { Lock.class, Run.class, Explain.class,
      Scan.class }, version = "zild 0.1")
  public static class ZildRoot extends CommonOptions implements Runnable {
    static final Logger log = LoggerFactory.getLogger(ZildRoot.class);

    @Override
    public void run() {
      RichLogback.configureLogbackByVerbosity(verbosity.length, quiet, color);
      log.info("Use subcommands: lock | scan | run <task> | explain");
    }
  }

  @Command(name = "lock", description = "Generate zild.lock.yaml from zild.yaml")
  public static class Lock extends CommonOptions implements Callable<Integer> {
    static final Logger log = LoggerFactory.getLogger(Lock.class);
    final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity.length, quiet, color);
      ZildSpec spec = yaml.readValue(Files.newInputStream(Path.of("zild.yaml")), ZildSpec.class);
      ZildLock lock = new ZildLock();
      lock.version = "0.1.0";
      lock.generated = Instant.now().toString();
      lock.source = "zild.yaml";
      lock.project = new ZildLock.Project("com.example:auto", spec.root);

      for (ZildSpec.ModuleRef ref : spec.modules) {
        ZildLock.ResolvedModule mod = new ZildLock.ResolvedModule(ref.path, "auto:" + ref.path,
            String.join(",", ref.template));
        ZildSpec.Template template = spec.templates.get(ref.template.get(0));
        mod.target = template.target;
        mod.layout = new ZildLock.Layout(ref.path + "/" + template.layout.source,
            ref.path + "/" + template.layout.output);
        mod.tasks = template.tasks;
        mod.artifacts = template.artifacts;
        lock.modules.put(ref.path, mod);
      }

      Files.writeString(Path.of("zild.lock.yaml"), yaml.writerWithDefaultPrettyPrinter().writeValueAsString(lock));
      log.info("Wrote zild.lock.yaml");
      return 0;
    }
  }

  @Command(name = "run", description = "Run a task from zild.lock.yaml")
  public static class Run extends CommonOptions implements Callable<Integer> {
    static final Logger log = LoggerFactory.getLogger(Run.class);
    final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Parameters(index = "0")
    String taskName;
    @Option(names = "--module", defaultValue = "core")
    String module;

    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity.length, quiet, color);
      ZildLock lock = yaml.readValue(Files.newInputStream(Path.of("zild.lock.yaml")), ZildLock.class);
      var mod = lock.modules.get(module);
      if (mod == null || !mod.tasks.containsKey(taskName)) {
        log.error("Task '{}' not found in module '{}'", taskName, module);
        return 1;
      }

      var task = mod.tasks.get(taskName);
      Path input = Path.of(".zild", "plugin-input.yaml");
      Files.createDirectories(input.getParent());
      Files.writeString(input, yaml.writerWithDefaultPrettyPrinter().writeValueAsString(task));

      List<String> cmd = List.of(task.tool, task.script);
      log.info("Running task '{}': {}", taskName, cmd);
      Process proc = new ProcessBuilder(cmd).inheritIO().start();
      return proc.waitFor();
    }
  }

  @Command(name = "explain", description = "Prints task per module from lock file")
  public static class Explain extends CommonOptions implements Callable<Integer> {
    final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity.length, quiet, color);
      ZildLock lock = yaml.readValue(Files.newInputStream(Path.of("zild.lock.yaml")), ZildLock.class);
      for (var entry : lock.modules.entrySet()) {
        System.out.printf("Module: %s (%s)\n", entry.getKey(), entry.getValue().template);
        entry.getValue().tasks
            .forEach((k, v) -> System.out.printf("  task: %s via %s\n", k, entry.getValue().template));
      }
      return 0;
    }
  }

  @Command(name = "scan", description = "Scan current directory for Maven modules (pom.xml) and generate zild.yaml")
  public static class Scan extends CommonOptions implements Callable<Integer> {
    static final Logger log = LoggerFactory.getLogger(Scan.class);
    final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity.length, quiet, color);

      ZildSpec spec = new ZildSpec();
      spec.root = true;
      spec.templates = new HashMap<>();

      // Default java-lib template
      ZildSpec.Template javaLib = new ZildSpec.Template();
      javaLib.id = "java-lib";
      javaLib.language = "java";
      javaLib.target = "jvm";
      ZildSpec.Layout layout = new ZildSpec.Layout();
      layout.source = "src/main/java";
      layout.output = "target/classes";
      javaLib.layout = layout;
      javaLib.tasks = new HashMap<>();
      spec.templates.put("java-lib", javaLib);

      // Find all pom.xml (excluding root if desired)
      spec.modules = new ArrayList<>();
      Path rootPom = Paths.get("pom.xml").toAbsolutePath().normalize();
      try (var stream = Files.walk(Paths.get("."))) {
        stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals("pom.xml"))
            .filter(p -> !p.toAbsolutePath().normalize().equals(rootPom)) // exclude root if desired
            .forEach(p -> {
              Path moduleDir = p.getParent();
              String modPath = moduleDir.toString().replace("\\", "/").replaceAll("^\\./", "");
              ZildSpec.ModuleRef mod = new ZildSpec.ModuleRef();
              mod.path = modPath;
              mod.template = List.of("java-lib");
              spec.modules.add(mod);
            });
      }
      if (spec.modules.isEmpty()) {
        log.warn("No modules found (no pom.xml detected except root).");
      } else {
        Files.writeString(Path.of("zild.yaml"), yaml.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
        log.info("zild.yaml written with {} modules (from pom.xml).", spec.modules.size());
      }
      return 0;
    }
  }

  public static class ZildSpec {
    public boolean root = false;
    public Map<String, Template> templates = new HashMap<>();
    public List<ModuleRef> modules = new ArrayList<>();

    public static class Template {
      public String id;
      @JsonProperty("extends")
      public String extends_;
      public String language, target;
      public Layout layout;
      public Map<String, ZildLock.Task> tasks = new HashMap<>();
      public List<ZildLock.Artifact> artifacts = new ArrayList<>();
    }

    public static class ModuleRef {
      public String path;
      public List<String> template;
    }

    public static class Layout {
      public String source, output;
    }
  }

  public static class ZildLock {
    public String version, generated, source;
    public Project project;
    public Map<String, ResolvedModule> modules = new LinkedHashMap<>();

    public static class Project {
      public String id;
      public boolean root;

      public Project(String id, boolean root) {
        this.id = id;
        this.root = root;
      }
    }

    public static class ResolvedModule {
      public String path, id, template, target;
      public Layout layout;
      public Map<String, Task> tasks = new LinkedHashMap<>();
      public List<Artifact> artifacts = new ArrayList<>();

      public ResolvedModule(String path, String id, String template) {
        this.path = path;
        this.id = id;
        this.template = template;
      }
    }

    public static class Layout {
      public String source, output;

      public Layout(String s, String o) {
        source = s;
        output = o;
      }
    }

    public static class Task {
      public String tool, script, mainClass;
      public List<String> inputs, outputs, flags;
      public Map<String, String> env;
      public Boolean optional;
    }

    public static class Artifact {
      public String type, input, classifier;
    }
  }
}
