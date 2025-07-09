
//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.slf4j:slf4j-api:2.0.9
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.17.1
//DEPS org.slf4j:slf4j-api:2.0.12
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.zeroturnaround:zt-exec:1.12

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import org.fusesource.jansi.AnsiConsole;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import picocli.CommandLine;
import picocli.CommandLine.Option;

public class mvn2gradle {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(mvn2gradle.class);

  public static void main(String... args) {
    AnsiConsole.systemInstall();
    int exitCode = new CommandLine(new Cli()).execute(args);
    AnsiConsole.systemUninstall();
    System.exit(exitCode);
  }

  @CommandLine.Command(name = "mvn2gradle", mixinStandardHelpOptions = true, description = "Convert Maven pom.xml in given directory to build.gradle.kts (single-module base)")
  private static class Cli implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Directory containing pom.xml")
    private File projectDir;

    @CommandLine.Option(names = { "--ignore-unknown" }, description = "Ignore unknown XML fields")
    boolean ignoreUnknown = false;

    @Option(names = "--use-effective-pom", description = "Use mvn help:effective-pom")
    public boolean useEffectivePom = false;

    @Option(names = "--use-pom-inheritance", description = "Use recursive pom.xml parent inheritance", defaultValue = "true")
    public boolean usePomInheritance = true;
    @Option(names = "--inline-versions", description = "Inline dependency versions instead of using val variables")
    boolean inlineVersions = false;

    @Override
    public Integer call() throws Exception {
      return GradleKtsGenerator.sync(this);
    }
  }

  public static class Projects {
    public List<PomModel> project = new ArrayList<>();
  }

  @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement(localName = "project")
  private static class PomModel {
    // --- Root attributes (namespace, schema) ---
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(isAttribute = true, localName = "xmlns")
    public String xmlns;

    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(isAttribute = true, localName = "xsi", namespace = "http://www.w3.org/2000/xmlns/")
    public String xmlnsXsi;

    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(isAttribute = true, localName = "schemaLocation", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public String schemaLocation;

    // --- Standard Maven POM fields ---
    public String modelVersion;
    public Parent parent;
    public transient PomModel parentPom; // The actual loaded parent model (transient, not serialized)
    public String groupId;
    public String artifactId;
    public String version;
    public String packaging;
    public String name;
    public String description;
    public String url;
    public String inceptionYear;
    public Licenses licenses;
    public Developers developers;
    public Scm scm;
    public IssueManagement issueManagement;
    public DistributionManagement distributionManagement;
    public Organization organization;
    public Dependencies dependencies;
    public DependencyManagement dependencyManagement;
    public Properties properties;
    public Build build;
    public Modules modules;
    public Repositories repositories;
    public PluginRepositories pluginRepositories;
    public Reporting reporting;
    public Profiles profiles;

    public Projects effectivePom;

    private PomModel() {
    }
  }

  // ----- Supporting classes for Maven POM -----

  private static class Parent {
    public String groupId;
    public String artifactId;
    public String version;
    public String relativePath;

    private Parent() {
    }
  }

  private static class Licenses {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "license")
    public java.util.List<License> license;

    private Licenses() {
    }
  }

  private static class License {
    public String name;
    public String url;
    public String distribution;
    public String comments;

    private License() {
    }
  }

  private static class Developers {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "developer")
    public java.util.List<Developer> developer;

    private Developers() {
    }
  }

  private static class Developer {
    public String id;
    public String name;
    public String email;
    public String url;
    public String organization;
    public String organizationUrl;
    public String roles;
    public String timezone;

    private Developer() {
    }
  }

  private static class Scm {
    public String connection;
    public String developerConnection;
    public String url;
    public String tag;

    private Scm() {
    }
  }

  private static class IssueManagement {
    public String system;
    public String url;

    private IssueManagement() {
    }
  }

  private static class DistributionManagement {
    public DeploymentRepository repository;
    public DeploymentRepository snapshotRepository;
    public String site;

    private DistributionManagement() {
    }
  }

  private static class DeploymentRepository {
    public String id;
    public String name;
    public String url;
    public String layout;
    public String uniqueVersion;
    // public String checksumPolicy;
    // public String updatePolicy;
    // public String enabled;
    // public String provider;

    private DeploymentRepository() {
    }
  }

  private static class Organization {
    public String name;
    public String url;

    private Organization() {
    }
  }

  private static class DependencyManagement {
    public Dependencies dependencies;

    private DependencyManagement() {
    }
  }

  private static class Dependencies {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "dependency")
    public java.util.List<Dependency> dependency;

    private Dependencies() {
    }
  }

  private static class Dependency {
    public String groupId;
    public String artifactId;
    public String version;
    public String scope;
    public String type;
    public String classifier;
    public Exclusions exclusions;
    public String optional;

    private Dependency() {
    }
  }

  private static class Exclusions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "exclusion")
    public java.util.List<Exclusion> exclusion;

    private Exclusions() {
    }
  }

  private static class Exclusion {
    public String groupId;
    public String artifactId;

    private Exclusion() {
    }
  }

  private static class Properties {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.TreeMap<String, String> any = new java.util.TreeMap<>();

    private Properties() {
    }
  }

  private static class Build {
    public String finalName;
    public String sourceDirectory;
    public String testSourceDirectory;
    public String scriptSourceDirectory;
    public String testScriptSourceDirectory;
    public String outputDirectory;
    public String testOutputDirectory;
    public String directory;

    public Resources resources;
    public TestResources testResources;

    public Plugins plugins;
    public PluginManagement pluginManagement;
    public Extensions extensions;

    private Build() {
    }
  }

  private static class Resources {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "resource")
    public java.util.List<Resource> resource;

    private Resources() {
    }
  }

  private static class TestResources {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "testResource")
    public java.util.List<Resource> testResource;

    private TestResources() {
    }
  }

  private static class Resource {
    public String directory;
    public String targetPath;
    public Boolean filtering;
    public Includes includes;
    public Excludes excludes;

    // ...other fields if you want, such as mergeId, etc.
    private Resource() {
    }
  }

  private static class Includes {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<String> include;

    private Includes() {
    }
  }

  private static class Excludes {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<String> exclude;

    private Excludes() {
    }
  }

  private static class PluginManagement {
    public Plugins plugins;

    private PluginManagement() {
    }
  }

  private static class Plugins {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "plugin")
    public java.util.List<Plugin> plugin;

    private Plugins() {
    }
  }

  private static class Plugin {
    public String groupId;
    public String artifactId;
    public String version;
    public Boolean inherited;
    public Executions executions;
    public Dependencies dependencies;
    public PluginConfiguration configuration;

    private Plugin() {
    }
  }

  private static class PluginConfiguration {
    // maven-compiler-plugin - properties
    public String source;
    public String target;

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.Map<String, Object> any = new java.util.HashMap<>();

    private PluginConfiguration() {
    }
  }

  private static class Executions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "execution")
    public java.util.List<Execution> execution;

    private Executions() {
    }
  }

  private static class Execution {
    public String id;
    public String phase;
    public Goals goals;
    public Object configuration;
    public Boolean inherited;

    private Execution() {
    }
  }

  private static class Goals {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "goal")
    public java.util.List<String> goal;

    private Goals() {
    }
  }

  private static class Modules {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "module")
    public java.util.List<String> module;

    private Modules() {
    }
  }

  private static class Repositories {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "repository")
    public java.util.List<Repository> repository;

    private Repositories() {
    }
  }

  private static class Repository {
    public String id;
    public String name;
    public String url;
    public String layout;
    public String releases;
    public String snapshots;

    private Repository() {
    }
  }

  private static class PluginRepositories {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<PluginRepository> pluginRepository;

    private PluginRepositories() {
    }
  }

  private static class PluginRepository {
    public String id;
    public String name;
    public String url;
    public String layout;
    public RepositoryPolicy releases;
    public RepositoryPolicy snapshots;

    private PluginRepository() {
    }
  }

  private static class RepositoryPolicy {
    public String enabled;
    public String updatePolicy;
    public String checksumPolicy;

    private RepositoryPolicy() {
    }
  }

  private static class Extensions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<Extension> extension;

    private Extensions() {
    }
  }

  private static class Extension {
    public String groupId;
    public String artifactId;
    public String version;

    private Extension() {
    }
  }

  private static class Reporting {
    public Boolean excludeDefaults;
    public String outputDirectory;
    public ReportPlugins plugins;

    private Reporting() {
    }
  }

  private static class ReportPlugins {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<ReportPlugin> plugin;

    private ReportPlugins() {
    }
  }

  private static class ReportPlugin {
    public String groupId;
    public String artifactId;
    public String version;
    public ReportPluginExecutions executions;
    public ReportPluginConfiguration configuration;
    public ReportSets reportSets;

    private ReportPlugin() {
    }
  }

  private static class ReportPluginExecutions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<ReportPluginExecution> execution;

    private ReportPluginExecutions() {
    }
  }

  private static class ReportPluginExecution {
    public String id;
    public String phase;
    public String goals; // can be a list in some schemas
    public ReportPluginConfiguration configuration;

    private ReportPluginExecution() {
    }
  }

  private static class ReportPluginConfiguration {
    private java.util.Map<String, Object> any = new java.util.HashMap<>();

    @JsonAnySetter
    public void set(String name, Object value) {
      any.put(name, value);
    }

    public java.util.Map<String, Object> getAny() {
      return any;
    }

    private ReportPluginConfiguration() {
    }
  }

  private static class ReportSets {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<ReportSet> reportSet;

    private ReportSets() {
    }
  }

  private static class ReportSet {
    public String id;
    public Reports reports;
    public String inherited; // "true"/"false"

    private ReportSet() {
    }
  }

  private static class Reports {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<String> report;

    private Reports() {
    }
  }

  private static class Profiles {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<Profile> profile;

    private Profiles() {
    }
  }

  private static class Profile {
    public String id;
    public Activation activation;
    public Properties properties;
    public DependencyManagement dependencyManagement;
    public Dependencies dependencies;
    public Build build;
    public Reporting reporting;
    public DistributionManagement distributionManagement;
    public Repositories repositories;
    public PluginRepositories pluginRepositories;
    public Modules modules;
    public Scm scm;
    public Organization organization;

    // ... add other Maven profile fields as needed ...
    private Profile() {
    }
  }

  private static class Activation {
    public ActivationProperty property;
    public String activeByDefault;
    public String jdk;
    public ActivationOs os;
    public ActivationFile file;
    public ActivationCustom custom;

    private Activation() {
    }
  }

  private static class ActivationProperty {
    public String name;
    public String value;

    private ActivationProperty() {
    }
  }

  private static class ActivationOs {
    public String name;
    public String family;
    public String arch;
    public String version;

    private ActivationOs() {
    }
  }

  private static class ActivationFile {
    public String exists;
    public String missing;

    private ActivationFile() {
    }
  }

  private static class ActivationCustom {
    // Could contain scripts, etc.
    private ActivationCustom() {
    }
  }

  private static class GradleKtsGenerator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GradleKtsGenerator.class);

    public static Integer sync(Cli cli) throws Exception {
      Projects effectivePom = null;
      if (cli.useEffectivePom) {
        Path effPomPath = cli.projectDir.toPath().resolve("effective-pom.xml");
        if (!Files.exists(effPomPath)) {
          log.info("Generating effective-pom.xml");
          GradleKtsGenerator.generateEffectivePom(cli.projectDir);
        }
        effectivePom = loadEffectivePom(cli.projectDir, cli.ignoreUnknown);
      }

      PomModel rootPom = loadPom(cli.projectDir, cli.ignoreUnknown, effectivePom);

      generateForModulesRecursively(cli.projectDir.toPath(), rootPom, cli, effectivePom);

      return 0;
    }

    private static void generateForModulesRecursively(Path baseDir, PomModel pom, Cli cli, Projects effectivePom)
        throws IOException {
      log.info("Generating build.gradle.kts for {}", pom.artifactId);

      String gradleKts = generate(pom, cli.useEffectivePom, cli.inlineVersions, effectivePom);

      Files.writeString(baseDir.resolve("build.gradle.kts"), gradleKts);

      if (pom.modules != null && pom.modules.module != null) {
        for (String moduleName : pom.modules.module) {
          Path moduleDir = baseDir.resolve(moduleName);
          if (Files.exists(moduleDir)) {
            PomModel modulePom = GradleKtsGenerator.loadPom(moduleDir.toFile(), cli.ignoreUnknown, effectivePom);
            generateForModulesRecursively(moduleDir, modulePom, cli, effectivePom);
          } else {
            log.warn("Module directory not found: {}", moduleDir);
          }
        }
      }
    }

    public static PomModel loadPom(File projectDirOrPomFile, boolean ignoreUnknown, Projects effectivePom)
        throws IOException {
      PomModel pom;
      if (projectDirOrPomFile.isDirectory()) {
        Path pomPath = projectDirOrPomFile.toPath().resolve("pom.xml");
        pom = loadPomFromFile(pomPath.toFile(), ignoreUnknown, effectivePom);
      } else {
        pom = loadPomFromFile(projectDirOrPomFile, ignoreUnknown, effectivePom);
      }

      // Attach effectivePom reference for fallback usage
      pom.effectivePom = effectivePom;

      // Recursively load parents as before (unchanged)
      if (pom.parent != null) {
        File parentPomFile = findParentPomFile(pom,
            projectDirOrPomFile.isDirectory() ? projectDirOrPomFile : projectDirOrPomFile.getParentFile());
        if (parentPomFile != null && parentPomFile.exists()) {
          pom.parentPom = loadPom(parentPomFile, ignoreUnknown, effectivePom);
        }
      }
      return pom;
    }

    private static PomModel loadPomFromFile(File pomFile, boolean ignoreUnknown, Projects effectivePom)
        throws IOException {
      log.info("Loading POM from {}", pomFile.getAbsolutePath());
      PomModel pom = parsePom(pomFile, ignoreUnknown);

      // Recursively load parent (full chain)
      if (pom.parent != null) {
        File parentPomFile = findParentPomFile(pom, pomFile.getParentFile());
        if (parentPomFile != null && parentPomFile.exists()) {
          // TODO review if parent POM should be loaded with effectivePom
          log.info("Loading parent POM from {}", parentPomFile.getAbsolutePath());
          pom.parentPom = loadPom(parentPomFile, ignoreUnknown, effectivePom);
        }
      }
      return pom;
    }

    private static File findParentPomFile(PomModel child, File projectDir) throws IOException {
      // 1. Try <relativePath> or default to "../pom.xml" (local parent, not in .m2)
      String relPath = (child.parent.relativePath == null || child.parent.relativePath.isBlank())
          ? "../pom.xml"
          : child.parent.relativePath;
      File localParent = new File(projectDir, relPath).getCanonicalFile();
      if (localParent.exists()) {
        return localParent;
      }

      // 2. Try Maven local repository layout (correct way)
      String groupPath = child.parent.groupId.replace('.', '/');
      String artifactId = child.parent.artifactId;
      String version = child.parent.version;
      File m2 = new File(System.getProperty("user.home"), ".m2/repository");
      File repoPom = new File(m2, String.format(
          "%s/%s/%s/%s-%s.pom", groupPath, artifactId, version, artifactId, version));
      if (repoPom.exists()) {
        return repoPom;
      }

      // 3. Not found
      throw new FileNotFoundException(
          "Parent POM not found: tried local [" + localParent + "] and Maven repo [" + repoPom + "]");
    }

    private static PomModel parsePom(File pomFile, boolean ignoreUnknown) {
      try {
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            !ignoreUnknown);
        return xmlMapper.readValue(pomFile, PomModel.class);
      } catch (IOException e) {
        throw new RuntimeException("Failed to parse POM file: " + pomFile.getAbsolutePath(), e);
      }
    }

    public static String generate(PomModel pom, boolean useEffectivePom, boolean inlineVersions,
        Projects effectivePom) {
      DependencyEmitResult depResult;
      if (pom.dependencies == null || pom.dependencies.dependency == null) {
        depResult = new DependencyEmitResult("", "");
      } else {
        pom.dependencies.dependency.sort(java.util.Comparator
            .comparing((Dependency d) -> toGradleConf(d.scope))
            .thenComparing(d -> d.artifactId));
        if (inlineVersions) {
          String depBlock = pom.dependencies.dependency.stream()
              .map(dep -> String.format("    implementation(\"%s:%s:%s\")",
                  dep.groupId,
                  dep.artifactId,
                  resolveVersion(dep, pom, useEffectivePom, effectivePom) != null
                      ? resolveVersion(dep, pom, useEffectivePom, effectivePom)
                      : "unknown"))
              .collect(java.util.stream.Collectors.joining("\n"));
          depResult = new DependencyEmitResult("", depBlock);
        } else {
          depResult = emitGradleDependenciesWithVars(pom, effectivePom);
        }
      }
      String group = resolveProperties(pom.groupId, pom);
      String version = resolveProperties(pom.version, pom);

      String javaVersion = extractJavaVersionFromEffectivePom(pom);
      return String.format("""
          %s

          plugins {
              java
          }

          java {
              sourceCompatibility = JavaVersion.toVersion("%s")
              targetCompatibility = JavaVersion.toVersion("%s")
          }

          group = "%s"
          version = "%s"

          repositories {
              mavenLocal()
              mavenCentral()
          }

          tasks.withType<JavaCompile> {
              options.compilerArgs.add("-Xlint:unchecked")
          }

          dependencies {
          %s
          }
          """,
          depResult.variableBlock,
          javaVersion, javaVersion,
          group,
          version,
          depResult.dependencyBlock);
    }

    private static String extractJavaVersionFromEffectivePom(PomModel pom) {
      if (pom.build != null && pom.build.plugins != null && pom.build.plugins.plugin != null) {
        for (Plugin plugin : pom.build.plugins.plugin) {
          if ("maven-compiler-plugin".equals(plugin.artifactId) && plugin.configuration != null) {
            try {
              String source = plugin.configuration.source;
              String target = plugin.configuration.target;
              if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
                if (source.equals(target)) {
                  return source;
                }
                return source; // prefer source if different
              } else if (source != null && !source.isBlank()) {
                return source;
              } else if (target != null && !target.isBlank()) {
                return target;
              }
            } catch (Exception e) {
              throw new RuntimeException("Failed to read compiler plugin configuration", e);
            }
          }
        }
      }

      // fallback: check properties for common keys
      if (pom.properties != null && pom.properties.any != null) {
        for (String key : new String[] { "maven.compiler.source", "maven.compiler.target", "java.version" }) {
          String val = pom.properties.any.get(key);
          if (val != null && !val.isBlank()) {
            return val;
          }
        }
      }

      return "1.8";
    }

    public static String resolveVersion(Dependency d, PomModel pom, boolean useEffectivePom, Projects effectivePom) {
      if (d.version != null && !d.version.isBlank()) {
        return d.version;
      }
      if (useEffectivePom && effectivePom != null) {
        // Search effectivePom projects for matching dependency version
        for (PomModel epPom : effectivePom.project) {
          if (d.groupId.equals(epPom.groupId) && epPom.dependencies != null && epPom.dependencies.dependency != null) {
            for (Dependency ed : epPom.dependencies.dependency) {
              if (d.artifactId.equals(ed.artifactId) && ed.version != null) {
                return ed.version;
              }
            }
          }
        }
        return null;
      } else {
        return extractVersion(d.groupId, d.artifactId, pom);
      }
    }

    private static void generateEffectivePom(File projectDir)
        throws IOException, InterruptedException, TimeoutException {
      String mavenCmd = isWindows() ? "mvn.cmd" : "mvn";
      int exit;
      try {
        exit = new ProcessExecutor()
            .directory(projectDir)
            .command(mavenCmd, "help:effective-pom", "-Doutput=effective-pom.xml")
            .redirectOutput(System.out)
            .redirectError(System.err)
            .exitValues(0) // Only allow zero exit value
            .execute()
            .getExitValue();
      } catch (InvalidExitValueException e) {
        throw new IOException("Maven failed with exit code: " + e.getExitValue(), e);
      }
      if (exit != 0) {
        throw new IOException("Maven failed to generate effective-pom.xml (exit " + exit + ")");
      }
    }

    private static boolean isWindows() {
      return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static Projects loadEffectivePom(File projectDir, boolean ignoreUnknown) throws IOException {
      Path effPomPath = projectDir.toPath().resolve("effective-pom.xml");
      if (!Files.exists(effPomPath)) {
        throw new FileNotFoundException("No effective-pom.xml found at " + effPomPath +
            ". Generate one by running: mvn help:effective-pom -Doutput=effective-pom.xml");
      }

      JacksonXmlModule module = new JacksonXmlModule();
      module.setDefaultUseWrapper(false); // optional depending on your XML structure

      XmlMapper xmlMapper = new XmlMapper(module);
      xmlMapper.setDefaultUseWrapper(false);
      xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, !ignoreUnknown);
      String xml = Files.readString(effPomPath);

      try {
        // Try parse as Projects (multi-module)
        return xmlMapper.readValue(xml, Projects.class);
      } catch (UnrecognizedPropertyException e) {
        // If failed because no 'project' wrapper, parse single PomModel then wrap it
        if (e.getMessage().contains("Unrecognized field") && e.getMessage().contains("project")) {
          PomModel singlePom = xmlMapper.readValue(xml, PomModel.class);
          Projects projects = new Projects();
          projects.project.add(singlePom);
          return projects;
        }
        throw e;
      }
    }

    private static java.util.Set<String> collectUsedProperties(PomModel pom) {
      java.util.Set<String> used = new java.util.LinkedHashSet<>();
      if (pom.dependencies != null && pom.dependencies.dependency != null) {
        for (Dependency d : pom.dependencies.dependency) {
          if (d.version != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(d.version);
            while (m.find()) {
              used.add(m.group(1));
            }
          }
        }
      }
      return used;
    }

    private static String emitGradleProperties(PomModel pom, java.util.Set<String> usedProperties) {
      StringBuilder propDefs = new StringBuilder();
      if (pom.properties != null && pom.properties.any != null) {
        for (String key : usedProperties) {
          String value = pom.properties.any.get(key);
          if (value != null) {
            String safeKey = key.replace('.', '_');
            propDefs.append(String.format("val %s = \"%s\"\n", safeKey, value));
          }
        }
      }
      return propDefs.toString();
    }

    private static class DependencyEmitResult {
      final String variableBlock;
      final String dependencyBlock;

      DependencyEmitResult(String variableBlock, String dependencyBlock) {
        this.variableBlock = variableBlock;
        this.dependencyBlock = dependencyBlock;
      }
    }

    private static DependencyEmitResult emitGradleDependenciesWithVars(PomModel pom, Projects effectivePom) {
      StringBuilder deps = new StringBuilder();
      StringBuilder varDecls = new StringBuilder();

      if (pom.dependencies != null && pom.dependencies.dependency != null) {
        for (Dependency d : pom.dependencies.dependency) {
          String version = d.version;
          String versionExpr;
          String conf = toGradleConf(d.scope);

          if ((version == null || version.isBlank()) && d.groupId != null && d.artifactId != null) {
            // Need to extract and emit variable
            String varName = "ver_" + d.groupId.replaceAll("[^a-zA-Z0-9]", "_")
                + "_" + d.artifactId.replaceAll("[^a-zA-Z0-9]", "_");
            String extracted = extractVersionFromProjects(d.groupId, d.artifactId, effectivePom);
            if (extracted == null || extracted.isBlank()) {
              varDecls.append(String.format("val %s = \"unknown\" // FIXME: version missing for %s:%s\n", varName,
                  d.groupId, d.artifactId));
            } else {
              varDecls.append(String.format("val %s = \"%s\"\n", varName, replaceMavenPropsWithKotlinVars(extracted)));
            }
            versionExpr = ":$" + varName;
          } else if (version != null && !version.isBlank()) {
            versionExpr = ":" + replaceMavenPropsWithKotlinVars(version);
          } else {
            versionExpr = ":unknown"; // fallback
          }

          log.info("Adding dependency: {} {}:{}{}", conf, d.groupId, d.artifactId, versionExpr);

          deps.append(String.format("    %s(\"%s:%s%s\")\n",
              conf, d.groupId, d.artifactId, versionExpr));
        }
      }
      return new DependencyEmitResult(varDecls.toString(), deps.toString());
    }

    private static String extractVersionFromProjects(String groupId, String artifactId, Projects projects) {
      if (projects == null || projects.project == null) {
        return null;
      }
      for (PomModel pom : projects.project) {
        String version = extractVersion(groupId, artifactId, pom);
        if (version != null && !version.isBlank()) {
          return version;
        }
      }
      return null;
    }

    private static String replaceMavenPropsWithKotlinVars(String value) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(value);
      StringBuffer sb = new StringBuffer();
      while (m.find()) {
        String safeKey = m.group(1).replace('.', '_');
        m.appendReplacement(sb, "\\$" + safeKey);
      }
      m.appendTail(sb);
      return sb.toString();
    }

    private static String extractVersion(String groupId, String artifactId, PomModel pom) {
      PomModel current = pom;
      while (current != null) {
        if (current.dependencyManagement != null &&
            current.dependencyManagement.dependencies != null &&
            current.dependencyManagement.dependencies.dependency != null) {
          for (Dependency dep : current.dependencyManagement.dependencies.dependency) {
            if (groupId.equals(dep.groupId) && artifactId.equals(dep.artifactId) && dep.version != null) {
              return resolveProperties(dep.version, current);
            }
          }
        }
        current = current.parentPom;
      }
      return null;
    }

    private static String resolveProperties(String value, PomModel pom) {
      if (value == null)
        return null;

      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(value);
      StringBuffer sb = new StringBuffer();

      while (m.find()) {
        String key = m.group(1);
        String replacement = null;

        // Try to find the property value in pom properties recursively
        PomModel current = pom;
        while (current != null && replacement == null) {
          if (current.properties != null && current.properties.any != null) {
            replacement = current.properties.any.get(key);
          }
          current = current.parentPom;
        }

        if (replacement == null) {
          replacement = "${" + key + "}"; // leave unresolved if not found
          log.info("Unresolved property: {} in {}", key, pom.artifactId);
        }
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
      }
      m.appendTail(sb);
      return sb.toString();
    }

    private static String toGradleConf(String scope) {
      if (scope == null || scope.isBlank() || "compile".equals(scope) || "compile+runtime".equals(scope)) {
        return "implementation";
      }
      if ("provided".equals(scope) || "providedCompile".equals(scope)) {
        return "compileOnly";
      }
      if ("runtime".equals(scope)) {
        return "runtimeOnly";
      }
      if ("test".equals(scope) || "testCompile".equals(scope) || "testRuntime".equals(scope)) {
        return "testImplementation";
      }
      if ("system".equals(scope)) {
        return "compileOnly";
      }
      return "implementation";
    }
  }
}
