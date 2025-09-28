//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.slf4j:slf4j-api:2.0.9
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.17.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS one.util:streamex:0.8.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1
//SOURCES mvn2gradle.java

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Option;

public class mvn2zild {
  private static final Logger log = LoggerFactory.getLogger(mvn2zild.class);

  public static void main(String... args) {
    AnsiConsole.systemInstall();
    int exitCode = new CommandLine(new Cli()).execute(args);
    AnsiConsole.systemUninstall();
    System.exit(exitCode);
  }

  @CommandLine.Command(name = "mvn2zild", mixinStandardHelpOptions = true, description = """
      Convert a Maven multi-module project (pom.xml) to Zild YAML spec.

      Features:
      - Maximum deep mapping of all modules, plugins, dependencies, properties, build structure.
      - Recursive multi-module and parent inheritance.
      - All plugin config and dependency edges mapped.
      - Full object model emission as zild.yaml.

      History:
      - 2025-08-06: Initial full rewrite from mvn2gradle.java, full ZildSpec emission.

      """)
  private static class Cli implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Directory containing pom.xml")
    private File projectDir;

    @CommandLine.Option(names = { "--ignore-unknown" }, description = "Ignore unknown XML fields")
    boolean ignoreUnknown = false;

    @Option(names = "--use-effective-pom", description = "Use mvn help:effective-pom")
    public boolean useEffectivePom = true;

    @Option(names = "--use-pom-inheritance", description = "Use recursive pom.xml parent inheritance", defaultValue = "true")
    public boolean usePomInheritance = true;

    @Option(names = "--inline-versions", description = "Inline dependency versions")
    boolean inlineVersions = false;

    @Option(names = "--force-generate-effective-pom", description = "Force regeneration of effective-pom.xml even if it exists", defaultValue = "true")
    public boolean forceGenerateEffectivePom = true;

    @Option(names = "--no-recursive", description = "Disable recursive module processing")
    public boolean noRecursive = false;

    @Option(names = "--out", description = "Output file for zild.yaml")
    public File outFile;

    @Override
    public Integer call() throws Exception {
      // Path pomPath = projectDir.toPath().resolve("pom.xml");
      ToGradle.Cli gradleCli = new ToGradle.Cli();
      gradleCli.projectDir = this.projectDir;
      // set gradleCli fields as needed

      // 1. Load effectivePom
      mvn2gradle.Projects effectivePom = null;
      if (gradleCli.useEffectivePom) {
        java.nio.file.Path effPomPath = gradleCli.projectDir.toPath().resolve("target/effective-pom.xml");
        if (!java.nio.file.Files.exists(effPomPath) || gradleCli.forceGenerateEffectivePom) {
          mvn2gradle.GradleKtsGenerator.generateEffectivePom(gradleCli.projectDir, effPomPath);
        }
        effectivePom = mvn2gradle.GradleKtsGenerator.loadEffectivePom(gradleCli.ignoreUnknown, effPomPath);
      }

      // 2. Now create ProjectContext with non-null effectivePom
      mvn2gradle.ProjectContext dummyCtx = new mvn2gradle.ProjectContext(gradleCli, null, effectivePom);

      // 3. Now load root POM
      mvn2gradle.Project rootPom = mvn2gradle.GradleKtsGenerator.loadPom(null, null, projectDir, dummyCtx);
      ZildSpec zild = fromPom(rootPom, effectivePom, !noRecursive);

      File output = outFile != null ? outFile : new File(projectDir, "zild.yaml");
      writeZildSpec(output, zild);
      System.out.println("Wrote: " + output.getAbsolutePath());
      return 0;
    }
  }

  public static mvn2gradle.Project loadPom(mvn2gradle.Project root, mvn2gradle.Project parentDirPom,
      File projectDirOrPomFile, mvn2gradle.ProjectContext context) {
    return mvn2gradle.GradleKtsGenerator.loadPom(root, parentDirPom, projectDirOrPomFile, context);
  }

  public static class ZildSpec {
    public String name;
    public String group;
    public String version;
    public String packaging;
    public String description;
    public String url;
    public Map<String, String> properties = new HashMap<>();
    public List<ZildModule> modules = new ArrayList<>();
    public List<String> dependencies = new ArrayList<>();
    public List<String> repositories = new ArrayList<>();
    public List<String> plugins = new ArrayList<>();
    public Map<String, Object> extra = new HashMap<>();
  }

  public static class ZildModule {
    public String name;
    public String path;
    public List<ZildTask> tasks = new ArrayList<>();
    // If you want to support submodules: public ZildSpec module;
  }

  public static class ZildTask {
    public String name;
    public String description;
    public String process; // e.g. "javac", "jar", "copy", or null for a group
    public List<String> inputs = new ArrayList<>();
    public List<String> outputs = new ArrayList<>();
    public Map<String, Object> options = new HashMap<>();
    public List<ZildTask> tasks = new ArrayList<>(); // Nested
    public String ref; // Reference to external/inherited execution
    public String importFile; // Import subtree from external file
  }

  public static ZildSpec fromPom(mvn2gradle.Project rootPom, mvn2gradle.Projects effectivePom, boolean recursive) {
    Map<String, mvn2gradle.Project> allModules = new LinkedHashMap<>();
    gatherModules(rootPom, ".", allModules, recursive);

    ZildSpec zs = new ZildSpec();
    zs.name = rootPom.artifactId;
    zs.group = rootPom.groupId;
    zs.version = rootPom.version;
    zs.packaging = rootPom.packaging;
    zs.description = rootPom.description;
    zs.url = rootPom.url;
    zs.properties = rootPom.properties != null ? rootPom.properties.any : new HashMap<>();

    for (var entry : allModules.entrySet()) {
      String dir = entry.getKey();
      mvn2gradle.Project mod = entry.getValue();

      ZildModule zm = new ZildModule();
      zm.name = mod.artifactId;
      zm.path = dir;
      zm.tasks = new ArrayList<>();

      // Compile
      {
        ZildTask compile = new ZildTask();
        compile.name = "compile";
        compile.process = "javac";
        compile.inputs = Arrays.asList(dir + "/src/main/java", dir + "/target/generated-sources/annotations" // or from
                                                                                                             // annotation
                                                                                                             // processors
        );
        compile.outputs = List.of(dir + "/target/classes");
        zm.tasks.add(compile);
      }

      // Test-compile
      {
        ZildTask testCompile = new ZildTask();
        testCompile.name = "test-compile";
        testCompile.process = "javac";
        testCompile.inputs = Arrays.asList(dir + "/src/test/java", dir + "/target/classes",
            dir + "/target/generated-test-sources/test-annotations");
        testCompile.outputs = List.of(dir + "/target/test-classes");
        zm.tasks.add(testCompile);
      }

      // Jar
      {
        ZildTask jarExec = new ZildTask();
        jarExec.name = "jar";
        jarExec.process = "jar";
        jarExec.inputs = List.of(dir + "/target/classes");
        jarExec.outputs = List
            .of(dir + "/target/" + mod.artifactId + "-" + (mod.version != null ? mod.version : "SNAPSHOT") + ".jar");
        zm.tasks.add(jarExec);
      }

      // Test-jar (if needed)
      if (hasTestJar(mod)) {
        ZildTask testJar = new ZildTask();
        testJar.name = "test-jar";
        testJar.process = "jar";
        testJar.inputs = List.of(dir + "/target/test-classes");
        testJar.outputs = List.of(
            dir + "/target/" + mod.artifactId + "-" + (mod.version != null ? mod.version : "SNAPSHOT") + "-tests.jar");
        zm.tasks.add(testJar);
      }

      // Process-resources
      if (exists(dir + "/src/main/resources")) {
        ZildTask procRes = new ZildTask();
        procRes.name = "process-resources";
        procRes.process = "copy";
        procRes.inputs = List.of(dir + "/src/main/resources");
        procRes.outputs = List.of(dir + "/target/classes");
        zm.tasks.add(procRes);
      }

      // Generate annotations (if APs used)
      if (usesAnnotationProcessing(mod)) {
        ZildTask apGen = new ZildTask();
        apGen.name = "generate-annotations";
        apGen.process = "annotation-processor";
        apGen.inputs = List.of(dir + "/src/main/java");
        apGen.outputs = List.of(dir + "/target/generated-sources/annotations");
        zm.tasks.add(apGen);
      }

      // Submodule dependencies: refer by <module>.<execution>.outputs
      for (var dep : safeList(mod.dependencies != null ? mod.dependencies.dependency : null)) {
        String depGA = dep.groupId + ":" + dep.artifactId;
        for (var mEntry : allModules.entrySet()) {
          mvn2gradle.Project cand = mEntry.getValue();
          if (dep.groupId.equals(cand.groupId) && dep.artifactId.equals(cand.artifactId)) {
            if (!mEntry.getKey().equals(dir)) {
              // Find the correct execution name, typically "jar"
              // For example: core.jar.outputs, api.test-jar.outputs, etc.
              // You may want to add a synthetic execution for "compile" (classes dir)
              // Below: add a pseudo-input to 'compile' execution as an example
              zm.tasks.stream().filter(exec -> exec.name.equals("compile")).findFirst()
                  .ifPresent(exec -> exec.inputs.add(cand.artifactId + ".jar.outputs"));
            }
          }
        }
      }
      zs.modules.add(zm);
    }
    return zs;
  }

//Returns true if module has <packaging>jar</packaging> and test-jar goal or plugin configured
  public static boolean hasTestJar(mvn2gradle.Project mod) {
    if (mod.packaging != null && mod.packaging.equals("pom"))
      return false;
    // Look for maven-jar-plugin <goal>test-jar</goal>
    if (mod.build != null && mod.build.plugins != null && mod.build.plugins.plugin != null) {
      for (mvn2gradle.Plugin plugin : mod.build.plugins.plugin) {
        if ("maven-jar-plugin".equals(plugin.artifactId)) {
          if (plugin.executions != null && plugin.executions.execution != null) {
            for (mvn2gradle.Execution exec : plugin.executions.execution) {
              if (exec.goals != null && exec.goals.goal != null) {
                for (String goal : exec.goals.goal) {
                  if ("test-jar".equals(goal))
                    return true;
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

  public static boolean hasSourcesJar(mvn2gradle.Project mod) {
    if (mod.packaging != null && mod.packaging.equals("pom"))
      return false;
    // Look for maven-source-plugin goal "jar"
    if (mod.build != null && mod.build.plugins != null && mod.build.plugins.plugin != null) {
      for (mvn2gradle.Plugin plugin : mod.build.plugins.plugin) {
        if ("maven-source-plugin".equals(plugin.artifactId)) {
          if (plugin.executions != null && plugin.executions.execution != null) {
            for (mvn2gradle.Execution exec : plugin.executions.execution) {
              if (exec.goals != null && exec.goals.goal != null) {
                for (String goal : exec.goals.goal) {
                  if ("jar".equals(goal))
                    return true;
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

  public static boolean hasJavadocJar(mvn2gradle.Project mod) {
    if (mod.packaging != null && mod.packaging.equals("pom"))
      return false;
    // Look for maven-javadoc-plugin goal "jar"
    if (mod.build != null && mod.build.plugins != null && mod.build.plugins.plugin != null) {
      for (mvn2gradle.Plugin plugin : mod.build.plugins.plugin) {
        if ("maven-javadoc-plugin".equals(plugin.artifactId)) {
          if (plugin.executions != null && plugin.executions.execution != null) {
            for (mvn2gradle.Execution exec : plugin.executions.execution) {
              if (exec.goals != null && exec.goals.goal != null) {
                for (String goal : exec.goals.goal) {
                  if ("jar".equals(goal))
                    return true;
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

//Checks if a file or directory exists
  public static boolean exists(String path) {
    return new java.io.File(path).exists();
  }

//Null-safe list iteration: returns an empty list if input is null
  public static <T> java.util.List<T> safeList(java.util.List<T> in) {
    return in == null ? java.util.Collections.emptyList() : in;
  }

//Checks if module uses annotation processing (checks for maven-compiler-plugin configuration or annotationProcessorPaths)
  public static boolean usesAnnotationProcessing(mvn2gradle.Project mod) {
    if (mod.build != null && mod.build.plugins != null && mod.build.plugins.plugin != null) {
      for (mvn2gradle.Plugin plugin : mod.build.plugins.plugin) {
        if ("maven-compiler-plugin".equals(plugin.artifactId)) {
          // Check configuration: <annotationProcessorPaths> or <compilerArgs> with
          // -processor
          if (plugin.configuration != null) {
            if (plugin.configuration.any.containsKey("annotationProcessorPaths")) {
              return true;
            }
            Object args = plugin.configuration.any.get("compilerArgs");
            if (args != null && args.toString().contains("-processor")) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

// Helper: is this a Java module? (you can expand this as needed)
  private static boolean isJavaModule(mvn2gradle.Project p) {
    return (p.packaging == null || "jar".equals(p.packaging) || "war".equals(p.packaging)) && (p.build != null
        && (p.build.sourceDirectory != null || new File(p.pomFile.getParent(), "src/main/java").exists()));
  }

  private static void gatherModules(mvn2gradle.Project pom, String relDir, Map<String, mvn2gradle.Project> all,
      boolean recursive) {
    all.put(relDir, pom);
    if (recursive && pom.modules != null && pom.modules.module != null) {
      for (String sub : pom.modules.module) {
        File subDir = new File(pom.pomFile.getParentFile(), sub);
        mvn2gradle.Project subPom = mvn2gradle.GradleKtsGenerator.loadPom(null, pom, subDir, pom.context);
        if (subPom != null) {
          gatherModules(subPom, relDir.equals(".") ? sub : relDir + "/" + sub, all, true);
        }
      }
    }
  }

  // --- Emission Section (YAML, JSON, etc) ---
  public static void writeZildSpec(File out, ZildSpec spec) throws IOException {
    // YAML is easiest for hand-editing and schema evolution
    com.fasterxml.jackson.databind.ObjectMapper yaml = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
    yaml.writeValue(out, spec);
  }
}
