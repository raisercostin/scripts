
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.fusesource.jansi.AnsiConsole;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Option;

public class mvn2gradle {
  private static final Logger logger = LoggerFactory.getLogger(mvn2gradle.class);

  public static void main(String... args) {
    AnsiConsole.systemInstall();
    int exitCode = new CommandLine(new Cli()).execute(args);
    AnsiConsole.systemUninstall();
    System.exit(exitCode);
  }

  @CommandLine.Command(name = "mvn2gradle", mixinStandardHelpOptions = true, description = "Convert Maven pom.xml in given directory to build.gradle.kts (single-module base)")
  public static class Cli implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Directory containing pom.xml")
    private File projectDir;

    @CommandLine.Option(names = { "--ignore-unknown" }, description = "Ignore unknown XML fields")
    boolean ignoreUnknown = false;

    @Option(names = "--use-effective-pom", description = "Use mvn help:effective-pom")
    public boolean useEffectivePom = false;

    @Option(names = "--use-pom-inheritance", description = "Use recursive pom.xml parent inheritance", defaultValue = "true")
    public boolean usePomInheritance = true;

    @Override
    public Integer call() throws Exception {
      configureLogging();
      Path pomPath = projectDir.toPath().resolve("pom.xml");
      Path gradlePath = projectDir.toPath().resolve("build.gradle.kts");

      if (!Files.exists(pomPath)) {
        logger.error("No pom.xml found at {}", pomPath);
        return 1;
      }

      logger.info("Parsing {}", pomPath);

      PomModel pom = GradleKtsGenerator.loadPom(projectDir, ignoreUnknown);

      logger.info("Generating build.gradle.kts for {}", pom.artifactId);
      String gradleKts = GradleKtsGenerator.generate(pom);

      Files.writeString(gradlePath, gradleKts);

      logger.info("Done: {}", gradlePath.toAbsolutePath());
      return 0;
    }

    private void configureLogging() {
      // Custom logback config if needed; otherwise uses default config.
    }
  }

  @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement(localName = "project")
  public static class PomModel {
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

    private PomModel() {
    }
  }

  // ----- Supporting classes for Maven POM -----

  public static class Parent {
    public String groupId;
    public String artifactId;
    public String version;
    public String relativePath;

    private Parent() {
    }
  }

  public static class Licenses {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "license")
    public java.util.List<License> license;

    private Licenses() {
    }
  }

  public static class License {
    public String name;
    public String url;
    public String distribution;
    public String comments;

    private License() {
    }
  }

  public static class Developers {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "developer")
    public java.util.List<Developer> developer;

    private Developers() {
    }
  }

  public static class Developer {
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

  public static class Scm {
    public String connection;
    public String developerConnection;
    public String url;
    public String tag;

    private Scm() {
    }
  }

  public static class IssueManagement {
    public String system;
    public String url;

    private IssueManagement() {
    }
  }

  public static class DistributionManagement {
    public DeploymentRepository repository;
    public DeploymentRepository snapshotRepository;
    public String site;

    private DistributionManagement() {
    }
  }

  public static class DeploymentRepository {
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

  public static class Organization {
    public String name;
    public String url;

    private Organization() {
    }
  }

  public static class DependencyManagement {
    public Dependencies dependencies;

    private DependencyManagement() {
    }
  }

  public static class Dependencies {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "dependency")
    public java.util.List<Dependency> dependency;

    private Dependencies() {
    }
  }

  public static class Dependency {
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

  public static class Exclusions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "exclusion")
    public java.util.List<Exclusion> exclusion;

    private Exclusions() {
    }
  }

  public static class Exclusion {
    public String groupId;
    public String artifactId;

    private Exclusion() {
    }
  }

  public static class Properties {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.TreeMap<String, String> any = new java.util.TreeMap<>();

    private Properties() {
    }
  }

  public static class Build {
    public String finalName;
    public Plugins plugins;
    public PluginManagement pluginManagement;
    public Extensions extensions;

    private Build() {
    }
  }

  public static class PluginManagement {
    public Plugins plugins;

    private PluginManagement() {
    }
  }

  public static class Plugins {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "plugin")
    public java.util.List<Plugin> plugin;

    private Plugins() {
    }
  }

  public static class Plugin {
    public String groupId;
    public String artifactId;
    public String version;
    public Executions executions;
    public Object configuration;
    public Dependencies dependencies;

    private Plugin() {
    }
  }

  public static class Executions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "execution")
    public java.util.List<Execution> execution;

    private Executions() {
    }
  }

  public static class Execution {
    public String id;
    public String phase;
    public Goals goals;
    public Object configuration;

    private Execution() {
    }
  }

  public static class Goals {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "goal")
    public java.util.List<String> goal;

    private Goals() {
    }
  }

  public static class Modules {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "module")
    public java.util.List<String> module;

    private Modules() {
    }
  }

  public static class Repositories {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "repository")
    public java.util.List<Repository> repository;

    private Repositories() {
    }
  }

  public static class Repository {
    public String id;
    public String name;
    public String url;
    public String layout;
    public String releases;
    public String snapshots;

    private Repository() {
    }
  }

  public static class PluginRepositories {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<PluginRepository> pluginRepository;

    private PluginRepositories() {
    }
  }

  public static class PluginRepository {
    public String id;
    public String name;
    public String url;
    public String layout;
    public RepositoryPolicy releases;
    public RepositoryPolicy snapshots;

    private PluginRepository() {
    }
  }

  public static class RepositoryPolicy {
    public String enabled;
    public String updatePolicy;
    public String checksumPolicy;

    private RepositoryPolicy() {
    }
  }

  public static class Extensions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<Extension> extension;

    private Extensions() {
    }
  }

  public static class Extension {
    public String groupId;
    public String artifactId;
    public String version;

    private Extension() {
    }
  }

  public static class Reporting {
    public Boolean excludeDefaults;
    public String outputDirectory;
    public ReportPlugins plugins;

    private Reporting() {
    }
  }

  public static class ReportPlugins {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<ReportPlugin> plugin;

    private ReportPlugins() {
    }
  }

  public static class ReportPlugin {
    public String groupId;
    public String artifactId;
    public String version;
    public ReportPluginExecutions executions;
    public ReportPluginConfiguration configuration;
    public ReportSets reportSets;

    private ReportPlugin() {
    }
  }

  public static class ReportPluginExecutions {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<ReportPluginExecution> execution;

    private ReportPluginExecutions() {
    }
  }

  public static class ReportPluginExecution {
    public String id;
    public String phase;
    public String goals; // can be a list in some schemas
    public ReportPluginConfiguration configuration;

    private ReportPluginExecution() {
    }
  }

  public static class ReportPluginConfiguration {
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

  public static class ReportSets {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<ReportSet> reportSet;

    private ReportSets() {
    }
  }

  public static class ReportSet {
    public String id;
    public Reports reports;
    public String inherited; // "true"/"false"

    private ReportSet() {
    }
  }

  public static class Reports {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<String> report;

    private Reports() {
    }
  }

  public static class Profiles {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<Profile> profile;

    private Profiles() {
    }
  }

  public static class Profile {
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

  public static class Activation {
    public ActivationProperty property;
    public String activeByDefault;
    public String jdk;
    public ActivationOs os;
    public ActivationFile file;
    public ActivationCustom custom;

    private Activation() {
    }
  }

  public static class ActivationProperty {
    public String name;
    public String value;

    private ActivationProperty() {
    }
  }

  public static class ActivationOs {
    public String name;
    public String family;
    public String arch;
    public String version;

    private ActivationOs() {
    }
  }

  public static class ActivationFile {
    public String exists;
    public String missing;

    private ActivationFile() {
    }
  }

  public static class ActivationCustom {
    // Could contain scripts, etc.
    private ActivationCustom() {
    }
  }

  public static class GradleKtsGenerator {
    public static PomModel loadPom(File projectDirOrPomFile, boolean ignoreUnknown) throws IOException {
      if (projectDirOrPomFile.isDirectory()) {
        Path pomPath = projectDirOrPomFile.toPath().resolve("pom.xml");
        return loadPomFromFile(pomPath.toFile(), ignoreUnknown);
      } else {
        return loadPomFromFile(projectDirOrPomFile, ignoreUnknown);
      }
    }

    private static PomModel loadPomFromFile(File pomFile, boolean ignoreUnknown) throws IOException {
      logger.info("Loading POM from {}", pomFile.getAbsolutePath());
      String xml = Files.readString(pomFile.toPath());
      XmlMapper xmlMapper = new XmlMapper();
      xmlMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
          !ignoreUnknown);
      PomModel pom = xmlMapper.readValue(xml, PomModel.class);

      // Recursively load parent (full chain)
      if (pom.parent != null) {
        File parentPomFile = findParentPomFile(pom, pomFile.getParentFile());
        if (parentPomFile != null && parentPomFile.exists()) {
          pom.parentPom = loadPom(parentPomFile, ignoreUnknown); // This now works for both dirs and files
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

    public static String generate(PomModel pom) {
      java.util.Set<String> usedProperties = collectUsedProperties(pom);
      String propertyBlock = emitGradleProperties(pom, usedProperties);
      DependencyEmitResult depResult = emitGradleDependenciesWithVars(pom, pom);

      return String.format("""
          %s%s
          plugins {
              java
          }

          group = "%s"
          version = "%s"

          repositories {
              mavenLocal()
              mavenCentral()
          }

          dependencies {
          %s}
          """,
          propertyBlock, depResult.variableBlock, pom.groupId, pom.version, depResult.dependencyBlock);
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

    private static DependencyEmitResult emitGradleDependenciesWithVars(PomModel pom, PomModel fullPom) {
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
            String extracted = extractVersion(d.groupId, d.artifactId, fullPom);
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
            versionExpr = ":unknown"; // Should never reach here now, but fallback
          }

          deps.append(String.format("    %s(\"%s:%s%s\")\n",
              conf, d.groupId, d.artifactId, versionExpr));
        }
      }
      return new DependencyEmitResult(varDecls.toString(), deps.toString());
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
              return dep.version;
            }
          }
        }
        current = current.parentPom; // walk up the parent chain
      }
      return null; // Only if not found anywhere
    }

    private static String toGradleConf(String scope) {
      if (scope == null || scope.isEmpty() || "compile".equals(scope))
        return "implementation";
      if ("provided".equals(scope) || "providedCompile".equals(scope))
        return "compileOnly";
      if ("runtime".equals(scope))
        return "runtimeOnly";
      if ("test".equals(scope) || "testCompile".equals(scope))
        return "testImplementation";
      return "implementation";
    }
  }
}
