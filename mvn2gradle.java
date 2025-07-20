// Description: Convert Maven pom.xml to Gradle build.gradle.kts
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
//DEPS one.util:streamex:0.8.2

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import org.fusesource.jansi.AnsiConsole;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import one.util.streamex.StreamEx;
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

    @Option(names = "--maven-compatible", description = "Generate settings.gradle.kts compatible with Maven:\n - generate inside target/gradle")
    boolean mavenCompatible = true;

    @Option(names = "--ignore-unknown-versions", description = "Ignore unknown dependencies versions (useful in debug)", defaultValue = "false", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    boolean ignoreUnknownVersions = false;

    @Option(names = "--ignore-unknown-java-version", description = "Ignore unknown Java version in pom.xml (useful in debug)", defaultValue = "true", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public boolean ignoreUnknownJavaVersion = true;

    @Option(names = "--force-generate-effective-pom", description = "Force regeneration of effective-pom.xml even if it exists", defaultValue = "true", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public boolean forceGenerateEffectivePom = true;

    @Override
    public Integer call() throws Exception {
      return GradleKtsGenerator.sync(this);
    }
  }

  public static class Projects {
    public List<Project> project = new ArrayList<>();
  }

  @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement(localName = "project")
  private static class Project {
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
    /**
     * The parent described above. Could exist locally in a parent or sibling dir or
     * not at all. A sibling is not standard in maven but could be useful when
     * transitioning to gradle from a multi-repo.
     */
    public transient Project parentPom;
    /***/
    public transient Project rootPom;
    /**
     * If the parent dir is a maven project. This could be different than the
     * inheritance parent.
     */
    public transient Project parentDirPom;
    public File pomFile;

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
    public Contributors contributors;
    public MailingLists mailingLists;
    public Prerequisites prerequisites;
    public CiManagement ciManagement;
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

    private Project() {
    }

    @Override
    public String toString() {
      return "Project[groupId='%s', artifactId='%s', version='%s']".formatted(groupId, artifactId, version);
    }

    public String id() {
      return "%s:%s:%s".formatted(groupId, artifactId, version != null ? version : "SNAPSHOT");
    }
  }

  private static class Parent {
    public String groupId;
    public String artifactId;
    public String version;
    public String relativePath;

    private Parent() {
    }

    @Override
    public String toString() {
      return "Parent[groupId='%s', artifactId='%s', version='%s', relativePath='%s']".formatted(groupId, artifactId,
          version, relativePath);
    }

    public String id() {
      return "%s:%s:%s".formatted(groupId, artifactId, version != null ? version : "SNAPSHOT");
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

  private static class Contributors {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "contributor")
    public List<Contributor> contributor;

    private Contributors() {
    }
  }

  private static class Contributor {
    public String name;
    public String email;
    public String url;
    public String organization;
    public String organizationUrl;
    public String roles;
    public String timezone;

    private Contributor() {
    }
  }

  private static class MailingLists {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "mailingList")
    public List<MailingList> mailingList;

    private MailingLists() {
    }
  }

  private static class MailingList {
    public String name;
    public String subscribe;
    public String unsubscribe;
    public String post;
    public String archive;
    public String otherArchives;
    public String otherArchivesSubscription;

    private MailingList() {
    }
  }

  private static class Prerequisites {
    public String maven;

    private Prerequisites() {
    }
  }

  private static class CiManagement {
    public String system;
    public String url;

    private CiManagement() {
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
    public String defaultGoal;
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

    public String getEffectiveGroupId() {
      return groupId != null ? groupId : "org.apache.maven.plugins";
    }
  }

  private static class PluginConfiguration {
    // maven-compiler-plugin - properties
    public String source;
    public String target;
    // checkstyle plugin
    public String skip;
    public String configLocation;

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.Map<String, Object> any = new java.util.HashMap<>();

    private PluginConfiguration() {
    }

    public boolean skip() {
      return skip != null && skip.equals("true") || skip.equals("skip");
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
    public PluginConfiguration configuration;
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
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.Map<String, Object> configuration = new java.util.HashMap<>();

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
    private static final Map<String, PluginConvertor> pluginConversionRegistry = new HashMap<>();

    // Helper record/class
    private static class PluginConvertor {
      private String mavenGroupAndArtifactId;
      public final String gradlePluginId;
      public final String gradlePluginVersion;
      // final String pluginDeclaration; // e.g., id("org.javacc.javacc") version
      // "4.0.1"
      public final BiFunction<PluginExecutionContext, Project, String> handler;

      PluginConvertor(String mavenKey, String gradlePluginId, String gradlePluginVersion,
          BiFunction<PluginExecutionContext, Project, String> handler) {
        this.mavenGroupAndArtifactId = mavenKey;
        this.gradlePluginId = gradlePluginId;
        this.gradlePluginVersion = gradlePluginVersion;
        this.handler = handler;
      }

      public boolean isEnabled(PluginExecutionContext ctx, Project pom) {
        return true;
      }
    }

    public static void register(PluginConvertor pluginConversion) {
      pluginConversionRegistry.put(pluginConversion.mavenGroupAndArtifactId, pluginConversion);
    }

    // Data structure for passing plugin execution info (add as static inner class)
    public static class PluginExecutionContext {
      public final Plugin plugin;
      public final String goal;
      public final PluginConfiguration configuration;

      public PluginExecutionContext(Plugin plugin, String goal, PluginConfiguration configuration) {
        this.plugin = plugin;
        this.goal = goal;
        this.configuration = configuration;
      }
    }

    static {
      register(new PluginConvertor("org.codehaus.mojo:javacc-maven-plugin:javacc", "org.javacc.javacc", "4.0.1",
          (ctx, pom) -> """
              tasks {
                  compileJavacc {
                      inputDirectory = file("src/main/javacc")
                      outputDirectory = file(layout.buildDirectory.dir("generated/javacc"))
                      arguments = mapOf("grammar_encoding" to "UTF-8", "static" to "false")
                  }
              }
              sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/javacc"))
              """));
      register(new PluginConvertor("org.apache.maven.plugins:maven-checkstyle-plugin:default", "checkstyle", null,
          (ctx, pom) -> ctx.configuration == null || ctx.configuration.skip() ? "//checkstyle skip" : """
              checkstyle {
                  isIgnoreFailures = %s // Equivalent to <skip>true</skip>
                  configFile = file("%s")
              }
              """.formatted(ctx.configuration.skip() ? "true" : "false", ctx.configuration.configLocation)) {
        @Override
        public boolean isEnabled(PluginExecutionContext ctx, Project pom) {
          return ctx.configuration == null || !ctx.configuration.skip();
        }
      });
      // ...and so on
      register(new PluginConvertor("org.apache.maven.plugins:maven-jar-plugin:test-jar", null, null, (ctx, pom) -> """
          tasks.register<Jar>("testJar") {
              archiveClassifier.set("tests")
              from(sourceSets.test.get().output)
          }
          configurations {
              create("testArtifacts")
          }
          artifacts {
              add("testArtifacts", tasks.named("testJar"))
          }
          """));
//      register(
//          new PluginConvertor("org.codehaus.mojo:build-helper-maven-plugin:add-source", null, null, (ctx, pom) -> """
//              sourceSets {
//                  main {
//                      java {
//                          srcDir("target/generated-sources/javacc")
//                      }
//                  }
//              }
//              """));
    }

    public static Integer sync(Cli cli) throws Exception {
      Projects effectivePom = null;
      if (cli.useEffectivePom) {
        Path effPomPath = cli.projectDir.toPath().resolve("target/effective-pom.xml");
        if (!Files.exists(effPomPath) || cli.forceGenerateEffectivePom) {
          log.info("Generating effective-pom.xml");
          generateEffectivePom(cli.projectDir, effPomPath);
        }
        effectivePom = loadEffectivePom(cli.ignoreUnknown, effPomPath);
      }

      Project rootPom = loadPom(null, null, cli.projectDir, cli.ignoreUnknown, effectivePom);

      // At top-level in sync
      Map<String, String> artifactIdToGradlePath = collectModuleArtifactIdToGradlePath(rootPom, cli.projectDir,
          cli.ignoreUnknown, effectivePom);

      // Generate settings.gradle.kts for root project and modules
      String settingsGradle = generateSettingsGradleKts(rootPom, cli.projectDir, cli.ignoreUnknown,
          cli.useEffectivePom ? rootPom.effectivePom : null);
      Files.writeString(cli.projectDir.toPath().resolve("settings.gradle.kts"), settingsGradle);
      log.info("Generated settings.gradle.kts");

      // Generate build.gradle.kts recursively for root and modules
      generateForModulesRecursively(cli.projectDir.toPath(), rootPom, cli, effectivePom, artifactIdToGradlePath, true);

      return 0;
    }

    public static String generateSettingsGradleKts(Project rootPom, File rootDir, boolean ignoreUnknown,
        Projects effectivePom) {
      String rootName = rootPom.artifactId != null ? rootPom.artifactId : "rootProject";
      List<String> includes = collectAllModulePaths(rootPom, "", rootDir, ignoreUnknown, effectivePom);

      String includesStr = StreamEx.of(includes).map(m -> "include(\"" + m + "\")").joining("\n");
      String mirrorsRepositoriesBlock = generateGradleRepositoriesFromMirrors(
          readMavenSettings(new File(System.getProperty("user.home"), ".m2/settings.xml")));

      return """
          rootProject.name = "%s"
          dependencyResolutionManagement {
              repositories {
                  mavenLocal()
                  %s
                  mavenCentral()
              }
          }
          %s
          """.formatted(rootName, mirrorsRepositoriesBlock, includesStr);
    }

    public static class MvnSettings {
      public Mirrors mirrors;

      public static class Mirrors {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Mirror> mirror;
      }

      public static class Mirror {
        public String id;
        public String mirrorOf;
        public String url;
      }
    }

    public static MvnSettings readMavenSettings(File settingsXml) {
      try {
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return xmlMapper.readValue(settingsXml, MvnSettings.class);
      } catch (IOException e) {
        throw new RuntimeException("Failed to read Maven settings.xml: " + settingsXml.getAbsolutePath(), e);
      }
    }

    public static String generateGradleRepositoriesFromMirrors(MvnSettings settings) {
      if (settings == null || settings.mirrors == null || settings.mirrors.mirror == null)
        return "";

      return StreamEx.of(settings.mirrors.mirror).map(m -> """
          maven {
              name = "%s"
              url = uri("%s")
          }
          """.formatted(m.id, m.url)).joining("\n");
    }

    // TODO refactor to use pom structure
    private static List<String> collectAllModulePaths(Project pom, String parentPath, File baseDir,
        boolean ignoreUnknown, Projects effectivePom) {
      if (pom.modules == null || pom.modules.module == null || pom.modules.module.isEmpty()) {
        return List.of();
      }

      return StreamEx.of(pom.modules.module).flatMap(moduleName -> {
        String fullPath = parentPath.isEmpty() ? moduleName : parentPath + ":" + moduleName;
        File moduleDir = baseDir.toPath().resolve(moduleName).toFile();
        Project childPom = loadPom(null, null, moduleDir, ignoreUnknown, effectivePom);
        List<String> nested = childPom != null
            ? collectAllModulePaths(childPom, fullPath, moduleDir, ignoreUnknown, effectivePom)
            : List.of();
        return StreamEx.of(fullPath).append(nested);
      }).toList();
    }

    // TODO refactor to use pom structure
    private static void generateForModulesRecursively(Path baseDir, Project pom, Cli cli, Projects effectivePom,
        Map<String, String> artifactIdToGradlePath, boolean isRoot) {
      log.info("Generating build.gradle.kts for {}", pom.artifactId);

      String gradleKts = generate(pom, effectivePom, artifactIdToGradlePath, cli);

      try {
        Files.writeString(baseDir.resolve("build.gradle.kts"), gradleKts);
      } catch (IOException e) {
        throw new RuntimeException("Failed to write build.gradle.kts for " + pom.artifactId, e);
      }

      if (pom.modules != null && pom.modules.module != null) {
        for (String moduleName : pom.modules.module) {
          Path moduleDir = baseDir.resolve(moduleName);
          if (Files.exists(moduleDir)) {
            Project modulePom = loadPom(null, null, moduleDir.toFile(), cli.ignoreUnknown, effectivePom);
            generateForModulesRecursively(moduleDir, modulePom, cli, effectivePom, artifactIdToGradlePath, false);
          } else {
            log.warn("Module directory not found: {}", moduleDir);
          }
        }
      }
      deleteAndWarnIfSettingsGradleKtsExists(baseDir, isRoot);
    }

    private static void deleteAndWarnIfSettingsGradleKtsExists(Path dir, boolean isRoot) {
      Path settingsGradle = dir.resolve("settings.gradle.kts");
      if (isRoot) {
        // If this is the root module, we expect settings.gradle.kts to be present
        if (!Files.exists(settingsGradle)) {
          log.warn("Expected settings.gradle.kts not found in root module: {}", dir);
        }
        return;
      }
      if (Files.exists(settingsGradle)) {
        int counter = 1;
        Path renamed = settingsGradle.resolveSibling("settings.gradle.kts.bak" + counter);
        while (Files.exists(renamed)) {
          counter++;
          renamed = settingsGradle.resolveSibling("settings.gradle.kts.bak" + counter);
        }
        try {
          Files.move(settingsGradle, renamed);
          log.warn("Renamed unexpected settings.gradle.kts in submodule: {} → {}", settingsGradle, renamed);
        } catch (IOException e) {
          log.error("Failed to rename stray settings.gradle.kts in {}: {}", dir, e.getMessage(), e);
        }
      }
    }

    private static final Map<String, Project> pomCache = new HashMap<>();

    public static Project loadPom(Project root, Project parentDirPom, File projectDirOrPomFile, boolean ignoreUnknown,
        Projects effectivePom) {
      try {
        File pomFile = projectDirOrPomFile.isDirectory() ? projectDirOrPomFile.toPath().resolve("pom.xml").toFile()
            : projectDirOrPomFile;

        String key = pomFile.getCanonicalPath();
        if (pomCache.containsKey(key)) {
          return pomCache.get(key);
        }
        if (!pomFile.exists()) {
          log.warn("POM file not found: {}", pomFile.getAbsolutePath());
          return null;
        }

        log.info("Loading POM from {}", pomFile.getAbsolutePath());
        Project pom = parsePom(pomFile, ignoreUnknown);
        pomCache.put(key, pom);

        pom.rootPom = root;
        pom.parentDirPom = parentDirPom;
        if (pom.parent != null) {
          pom.parentPom = findParentPomFile(pom, ignoreUnknown);
        }
        return pom;
      } catch (IOException e) {
        throw new RuntimeException("Failed to load POM from " + projectDirOrPomFile, e);
      }
    }

    private static void checkIsSame(Project pom) {
      Parent parent = pom.parent;
      Project localParent = pom.parentPom;
      if (parent == null && localParent == null) {
        return;
      }
      if (parent == null || localParent == null) {
        throw new RuntimeException("Parent POM mismatch: one is null while the other is not. Parent: " + parent
            + ", ParentPom: " + localParent);
      }
      if (!parent.groupId.equals(localParent.groupId) || !parent.artifactId.equals(localParent.artifactId)) {
        throw new RuntimeException(
            "In %s parent POM mismatch: %s vs %s".formatted(pom.id(), parent.id(), localParent.id()));
      }
      if (!parent.version.equals(localParent.version)) {
        log.warn("Parent POM version mismatch: {} vs {}", parent.version, localParent.version);
      }
//      if (parent.artifactId == null || parentPom.artifactId == null) {
//        return true; // artifactId can be null in some cases, e.g., parent POMs without artifactId
//      }
      if (!parent.artifactId.equals(localParent.artifactId)) {
        throw new RuntimeException(
            "Parent POM artifactId mismatch: " + parent.artifactId + " vs " + localParent.artifactId);
      }
    }

    private static Project findParentPomFile(Project pom, boolean ignoreUnknown) {
      var parent = findParentPomFileNoCheck(pom, ignoreUnknown);
      if (parent == pom) {
        // TODO remove this - needed for debug
        parent = findParentPomFileNoCheck(pom, ignoreUnknown);
        throw new RuntimeException("Parent POM is self-referential: " + pom.id());
      }
      if (pom.parentPom != null) {
        if (pom.groupId == null) {
          pom.groupId = pom.parentPom.groupId;
        }
      }
      checkIsSame(parent);
      return parent;
    }

    private static Project findParentPomFileNoCheck(Project pom, boolean ignoreUnknown) {
      File projectDir = pom.pomFile.getParentFile();
      String relPath = (pom.parent.relativePath == null || pom.parent.relativePath.isBlank()) ? "../pom.xml"
          : pom.parent.relativePath;
      relPath = relPath.endsWith("/pom.xml") ? relPath : relPath + "/pom.xml";
//      File parentPomFile = findParentPomFile(pom, pomFile.getParentFile());
//      if (parentPomFile != null && parentPomFile.exists()) {
//        pom.parentPom = loadPom(root, null, parentPomFile, ignoreUnknown, effectivePom);
//        if (pom.parentPom != null) {
//          if (pom.groupId == null) {
//            pom.groupId = pom.parentPom.groupId;
//          }
//        }
//        checkIsSame(pom);
//      }

      try {
        String candidateKey = new File(projectDir, relPath).getCanonicalPath();
        if (pomCache.containsKey(candidateKey)) {
          return pomCache.get(candidateKey);
        }

        Project parentPom = loadPom(pom.rootPom, pom.parentDirPom, projectDir, ignoreUnknown, pom.rootPom.effectivePom);
        if (parentPom != null)
          return parentPom;
        // If the parent POM is not found in the expected relative path, we fallback to
        // Maven repo lookup
        String groupPath = pom.parent.groupId.replace('.', '/');
        String artifactId = pom.parent.artifactId;
        String version = pom.parent.version;
        File m2 = new File(System.getProperty("user.home"), ".m2/repository");
        File repoPom = new File(m2,
            String.format("%s/%s/%s/%s-%s.pom", groupPath, artifactId, version, artifactId, version));
        Project parentPomFromRepo = loadPom(pom.rootPom, null, repoPom, ignoreUnknown, pom.rootPom.effectivePom);
        if (parentPomFromRepo != null)
          return parentPomFromRepo;
        throw new RuntimeException(
            "Parent POM not found: tried local [" + candidateKey + "] and Maven repo [" + repoPom + "]");
      } catch (IOException e) {
        throw new RuntimeException("Failed to resolve parent POM path for " + pom.parent.artifactId, e);
      }
    }

    private static Project parsePom(File pomFile, boolean ignoreUnknown) {
      try {
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            !ignoreUnknown);
        Project res = xmlMapper.readValue(pomFile, Project.class);
        res.pomFile = pomFile;
        if(res.groupId == null && res.parent!=null && res.parent.groupId != null) {
          res.groupId = res.parent.groupId;
        }
        if (res.version == null && res.parent != null && res.parent.version != null) {
          res.version = res.parent.version;
        }
        return res;
      } catch (IOException e) {
        throw new RuntimeException("Failed to parse POM file: " + pomFile.getAbsolutePath(), e);
      }
    }

    public static String generate(Project pom, Projects effectivePom, Map<String, String> artifactIdToGradlePath,
        Cli cli) {

      // 1. Emit Maven property variables for all referenced properties in dependency
      // versions
      String gradlePropertyBlock = emitReferencedMavenPropertiesKts(pom);

      // 2. Collect Gradle plugins and config snippets as before
      StringBuilder pluginConfigSnippets = new StringBuilder();
      Map<String, String> pluginsMap = collectGradlePluginsAndConfigs(pom, pluginConfigSnippets);

      // 3. Emit dependencies and any version variables
      DependencyEmitResult depResult = emitDeps(pom, effectivePom, artifactIdToGradlePath, cli);

      // 4. Standard project attributes
      String group = resolveProperties(pom.groupId, pom);
      String version = resolveProperties(pom.version, pom);
      String javaVersion = extractJavaVersionFromEffectivePom(pom, cli);

      if (depResult.hasLombok)
        pluginsMap.put("io.freefair.lombok", "8.6");

      String pluginsBlock = buildPluginsBlockKts(pluginsMap);

      return String.format("""
          %s
          %s

          plugins {
              id("java")
              id("eclipse")
              id("com.vanniktech.dependency.graph.generator") version "0.8.0"
              id ("project-report")
              %s
          }

          java {
              sourceCompatibility = JavaVersion.toVersion("%s")
              targetCompatibility = JavaVersion.toVersion("%s")
          }

          group = "%s"
          version = "%s"
          layout.buildDirectory.set(file("$projectDir/target/gradle"))

          repositories {
              mavenLocal()
              mavenCentral()
          }

          tasks.withType<JavaCompile> {
              //-Xlint:unchecked can be configured with -P-GCompiler-Xlint:unchecked -P-GCompiler-nowarn
              project.properties.keys
                  .filter { it.toString().startsWith("-GCompiler") }
                  .forEach { key ->
                      val arg = key.toString().removePrefix("-GCompiler")
                      logger.info("Adding compiler arg: $arg")
                      options.compilerArgs.add(arg)
                  }
              options.encoding = "UTF-8"
              destinationDirectory.set(
                  layout.buildDirectory.dir(
                      "classes/java/" + if (name.contains("Test", ignoreCase = true)) "test" else "main"
                  )
              )
          }
          eclipse {
              classpath {
                  defaultOutputDir = layout.buildDirectory.dir("eclipse/classes/java/main").get().asFile
                  file {
                      whenMerged {
                          val entries = (this as org.gradle.plugins.ide.eclipse.model.Classpath).entries
                          entries.filterIsInstance<org.gradle.plugins.ide.eclipse.model.ProjectDependency>()
                              .forEach { it.entryAttributes["without_test_code"] = "false" }
                          entries.filterIsInstance<org.gradle.plugins.ide.eclipse.model.SourceFolder>()
                              .filter { it.path.startsWith("/") }
                              .forEach { it.entryAttributes["without_test_code"] = "false" }
                      }
                      withXml {
                        val node = asNode()
                        node.appendNode("classpathentry", mapOf(
                            "kind" to "src",
                            "path" to "target/gradle/generated/javacc"
                        ))
                     }
                  }
              }
          }

          dependencies {
          %s
          }
          %s
          """, gradlePropertyBlock, depResult.variableBlock, pluginsBlock, javaVersion, javaVersion, group, version,
          depResult.dependencyBlock, pluginConfigSnippets);
    }

    static String toKotlinVar(String key) {
      return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String emitReferencedMavenPropertiesKts(Project pom) {
      // --- Control inclusion of property usages ---
      final boolean includeDependencies = true;
      final boolean includeDependencyManagement = false;
      final boolean includeProfileDependencies = true;
      // --- Control inclusion of property source ---
      final boolean includeOwnProperties = true;
      final boolean includeParentProperties = true;

      Set<String> referencedKeys = StreamEx.<String>empty()
          // Dependencies
          .append(includeDependencies && pom.dependencies != null && pom.dependencies.dependency != null
              ? StreamEx.of(pom.dependencies.dependency).flatMap(d -> {
                if (d.version == null)
                  return StreamEx.empty();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(d.version);
                List<String> keys = new ArrayList<>();
                while (m.find())
                  keys.add(m.group(1));
                return StreamEx.of(keys);
              })
              : StreamEx.empty())
          // DependencyManagement
          .append(includeDependencyManagement && pom.dependencyManagement != null
              && pom.dependencyManagement.dependencies != null
              && pom.dependencyManagement.dependencies.dependency != null
                  ? StreamEx.of(pom.dependencyManagement.dependencies.dependency).flatMap(d -> {
                    if (d.version == null)
                      return StreamEx.empty();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(d.version);
                    List<String> keys = new ArrayList<>();
                    while (m.find())
                      keys.add(m.group(1));
                    return StreamEx.of(keys);
                  })
                  : StreamEx.empty())
          // Profile Dependencies
          .append(includeProfileDependencies && pom.profiles != null && pom.profiles.profile != null
              ? StreamEx.of(pom.profiles.profile)
                  .flatMap(profile -> profile.dependencies != null && profile.dependencies.dependency != null
                      ? StreamEx.of(profile.dependencies.dependency).flatMap(d -> {
                        if (d.version == null)
                          return StreamEx.empty();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}")
                            .matcher(d.version);
                        List<String> keys = new ArrayList<>();
                        while (m.find())
                          keys.add(m.group(1));
                        return StreamEx.of(keys);
                      })
                      : StreamEx.empty())
              : StreamEx.empty())
          .distinct().toSet();

      Map<String, String> allProps = includeOwnProperties && pom.properties != null && pom.properties.any != null
          ? pom.properties.any
          : java.util.Collections.emptyMap();

      if (includeParentProperties && pom.parentPom != null) {
        allProps = new java.util.LinkedHashMap<>(allProps);
        Map<String, String> parentProps = pom.parentPom.properties != null && pom.parentPom.properties.any != null
            ? pom.parentPom.properties.any
            : java.util.Collections.emptyMap();
        allProps.putAll(parentProps); // child overrides parent
      }

      var finalProps = allProps;
      return StreamEx.of(referencedKeys).map(key -> {
        String value = finalProps.get(key);
        if (value == null)
          return null;
        String safeKey = toKotlinVar(key);
        return "val %s = \"%s\"".formatted(safeKey, value);
      }).nonNull().joining("\n");
    }

    // --- Helper: return all POMs from this one up the parent chain, root first
    private static java.util.List<Project> getAllPomsRecursive(Project pom) {
      java.util.LinkedList<Project> chain = new java.util.LinkedList<>();
      Project current = pom;
      while (current != null) {
        chain.addFirst(current);
        current = current.parentPom;
      }
      return chain;
    }

    private static String buildPluginsBlockKts(Map<String, String> pluginsMap) {
      String pluginsEntries = StreamEx.of(pluginsMap.entrySet())
          .map(e -> e.getValue() == null ? "    id(\"" + e.getKey() + "\")"
              : "    id(\"" + e.getKey() + "\") version \"" + e.getValue() + "\"")
          .joining("\n");
      return pluginsEntries;
    }

    // tasks.withType<Test> {
    // useJUnitPlatform()
    // }
    // tasks.withType<ProcessResources> {
    // filesMatching("**/*.properties") {
    // filter(org.apache.tools.ant.filters.ReplaceTokens, tokens: properties)
    // }
    // }
    // tasks.withType<ProcessResources> {
    // filesMatching("**/*.xml") {
    // filter(org.apache.tools.ant.filters.ReplaceTokens, tokens: properties)
    // }
    // }
    // tasks.withType<ProcessResources> {
    // filesMatching("**/*.txt") {
    // filter(org.apache.tools.ant.filters.ReplaceTokens, tokens: properties)
    // }
    // }

    private static DependencyEmitResult emitDeps(Project pom, Projects effectivePom,
        Map<String, String> artifactIdToGradlePath, Cli cli) {
      if (pom.dependencies == null || pom.dependencies.dependency == null) {
        return new DependencyEmitResult("", "", false);
      }
      pom.dependencies.dependency.sort(
          java.util.Comparator.comparing((Dependency d) -> toGradleConf(d.scope)).thenComparing(d -> d.artifactId));

      StringBuilder varDecls = new StringBuilder();
      StringBuilder deps = new StringBuilder();
      boolean hasLombok = false;

      for (Dependency dep : pom.dependencies.dependency) {
        String resolvedGroupId = resolveGroupId(dep, pom);
        String resolvedArtifactId = resolveProperties(dep.artifactId, pom);
        String version = dep.version;
        String conf = toGradleConf(dep.scope);
        boolean isTestJar = "test-jar".equals(dep.type) || "tests".equals(dep.classifier);
        boolean isNotTestScope = dep.scope == null || !"test".equals(dep.scope);

        if (isTestJar && isNotTestScope) {
          log.warn(
              "Dependency on {}:{} with classifier=test/type=test-jar is missing <scope>test</scope>-Gradle may not handle this as a test dependency.",
              dep.groupId, dep.artifactId);
        }
        if (artifactIdToGradlePath.containsKey(resolvedArtifactId)) {
          String gradlePath = artifactIdToGradlePath.get(resolvedArtifactId);
          // Handle test-jar module dependency
          if ("test".equals(conf) || "testImplementation".equals(conf)) {
            if (isTestJar) {
              deps.append(String.format(
                  "    testImplementation(project(path = \":%s\", configuration = \"testArtifacts\"))\n", gradlePath));
              continue;
            }
          }
          // Normal module dependency
          deps.append(String.format("    %s(project(\":%s\"))\n", conf, gradlePath));
          continue;
        }
        // ---- Lombok special case ----
        if ("org.projectlombok".equals(resolvedGroupId) && "lombok".equals(resolvedArtifactId)) {
          hasLombok = true;
          String lombokVersion = (version == null || version.isBlank())
              ? extractVersionFromProjects(resolvedGroupId, resolvedArtifactId, effectivePom)
              : version;
          if (lombokVersion == null || lombokVersion.isBlank())
            lombokVersion = "unknown";
          deps.append("    compileOnly(\"org.projectlombok:lombok:" + lombokVersion + "\")\n");
          deps.append("    annotationProcessor(\"org.projectlombok:lombok:" + lombokVersion + "\")\n");
          deps.append("    testCompileOnly(\"org.projectlombok:lombok:" + lombokVersion + "\")\n");
          deps.append("    testAnnotationProcessor(\"org.projectlombok:lombok:" + lombokVersion + "\")\n");
          // Optionally ensure plugin registration (not shown here)
          continue;
        }
        // -----------------------------

        // 1. Detect version, ignoring inline concept
        String resolvedVersion = resolveVersion(dep, pom, cli.useEffectivePom, effectivePom);
        String extractedVersion = extractVersionFromProjects(resolvedGroupId, resolvedArtifactId, effectivePom);
        String finalVersion = replaceMavenPropsWithKotlinVars(
            resolvedVersion != null && !resolvedVersion.isBlank() ? resolvedVersion
                : (extractedVersion != null && !extractedVersion.isBlank() ? extractedVersion : "unknown"));

        if (finalVersion.equals("unknown") || (finalVersion.startsWith("${") && finalVersion.endsWith("}"))) {
          if (cli.ignoreUnknownVersions) {
            log.warn("Dependency {}:{} has unknown version, skipping", resolvedGroupId, resolvedArtifactId);
            continue;
          }
          throw new RuntimeException("Failed to resolve version for dependency: %s:%s on %s".formatted(resolvedGroupId,
              resolvedArtifactId, pom.artifactId));
        }

        String versionExpr;
        if (cli.inlineVersions) {
          versionExpr = finalVersion;
        } else {
          String varName = createVersionPropertyName(dep, resolvedGroupId, resolvedArtifactId);
          varDecls.append(String.format("val %s = \"%s\"\n", varName, finalVersion));
          versionExpr = "$" + varName;
        }
        log.info("Adding dependency: {} {}:{}:{}", conf, resolvedGroupId, resolvedArtifactId, versionExpr);

        String depCoordinate = String.format("%s:%s:%s", resolvedGroupId, resolvedArtifactId, versionExpr);
        String classifierPart = (dep.classifier != null && !dep.classifier.isBlank()) ? ":" + dep.classifier : "";
        String typePart = (dep.type != null && !dep.type.isBlank() && !"jar".equals(dep.type)) ? "@" + dep.type : "";
        depCoordinate += classifierPart + typePart;

        // Exclusions
        if (dep.exclusions != null && dep.exclusions.exclusion != null && !dep.exclusions.exclusion.isEmpty()) {
          deps.append(String.format("    %s(\"%s\") {\n", conf, depCoordinate));
          for (Exclusion excl : dep.exclusions.exclusion) {
            deps.append(
                String.format("        exclude(group = \"%s\", module = \"%s\")\n", excl.groupId, excl.artifactId));
          }
          deps.append("    }\n");
        } else {
          deps.append(String.format("    %s(\"%s\")\n", conf, depCoordinate));
        }
      }

      return new DependencyEmitResult(varDecls.toString(), deps.toString(), hasLombok);
    }

    private static String createVersionPropertyName(Dependency dep, String resolvedGroupId, String resolvedArtifactId) {
      String scopePart = (dep.scope == null || dep.scope.isBlank()) ? ""
          : "_" + dep.scope.replaceAll("[^a-zA-Z0-9]", "_");
      String typePart = (dep.type == null || dep.type.isBlank()) ? "" : "_" + dep.type.replaceAll("[^a-zA-Z0-9]", "_");
      String classifierPart = (dep.classifier == null || dep.classifier.isBlank()) ? ""
          : "_" + dep.classifier.replaceAll("[^a-zA-Z0-9]", "_");
      String varName = "ver_" + resolvedGroupId.replaceAll("[^a-zA-Z0-9]", "_") + "_"
          + resolvedArtifactId.replaceAll("[^a-zA-Z0-9]", "_") + scopePart + typePart + classifierPart;
      return varName;
    }

    private static Map<String, String> collectModuleArtifactIdToGradlePath(Project rootPom, File rootDir,
        boolean ignoreUnknown, Projects effectivePom) {
      Map<String, String> artifactIdToGradlePath = new HashMap<>();
      collectModulesRecursivelyWithPaths(rootPom, rootPom, rootDir, ignoreUnknown, effectivePom, "",
          artifactIdToGradlePath);
      return artifactIdToGradlePath;
    }

    private static void collectModulesRecursivelyWithPaths(Project root, Project pom, File baseDir,
        boolean ignoreUnknown, Projects effectivePom, String parentGradlePath,
        Map<String, String> artifactIdToGradlePath) {
      if (pom == null || pom.modules == null || pom.modules.module == null)
        return;

      for (String moduleName : pom.modules.module) {
        File moduleDir = new File(baseDir, moduleName);
        Project childPom = loadPom(root, pom, moduleDir, ignoreUnknown, effectivePom);
        if (childPom != null && childPom.artifactId != null) {
          String fullPath = parentGradlePath.isEmpty() ? moduleName : parentGradlePath + ":" + moduleName;
          artifactIdToGradlePath.put(childPom.artifactId, fullPath);
          collectModulesRecursivelyWithPaths(root, childPom, moduleDir, ignoreUnknown, effectivePom, fullPath,
              artifactIdToGradlePath);
        }
      }
    }

    private static List<PluginExecutionContext> getPluginExecutions(Plugin plugin, Project pom) {
      List<PluginExecutionContext> result = new ArrayList<>();
      Set<String> seenExecKeys = new HashSet<>();
      collectPluginExecutionsRecursive(plugin, pom, result, seenExecKeys);
      return result;
    }

    private static void collectPluginExecutionsRecursive(Plugin plugin, Project pom,
        List<PluginExecutionContext> result, Set<String> seenExecKeys) {
      if (pom == null || pom.build == null || pom.build.plugins == null || pom.build.plugins.plugin == null)
        return;
      for (Plugin p : pom.build.plugins.plugin) {
        if (plugin.getEffectiveGroupId().equals(p.getEffectiveGroupId()) && plugin.artifactId.equals(p.artifactId)) {
          if (p.executions != null && p.executions.execution != null) {
            for (Execution exec : p.executions.execution) {
              if (exec.inherited != null && Boolean.FALSE.equals(exec.inherited))
                continue;
              if (exec.goals != null && exec.goals.goal != null) {
                for (String goal : exec.goals.goal) {
                  // Compose a unique key for this execution
                  String execId = exec.id != null ? exec.id : "";
                  String execKey = execId + ":" + goal;
                  if (!seenExecKeys.add(execKey))
                    continue; // Already included from child, skip parent
                  PluginConfiguration config = exec.configuration != null ? exec.configuration : p.configuration;
                  result.add(new PluginExecutionContext(p, goal, config));
                }
              }
            }
          }
          if ((p.executions == null || p.executions.execution == null || p.executions.execution.isEmpty())
              && seenExecKeys.isEmpty()) {
            result.add(new PluginExecutionContext(p, "default", p.configuration));
          }
        }
      }
      collectPluginExecutionsRecursive(plugin, pom.parentDirPom, result, seenExecKeys);
    }

    private static Map<String, String> collectGradlePluginsAndConfigs(Project pom, StringBuilder pluginConfigSnippets) {
      Map<String, String> pluginsMap = new LinkedHashMap<>();
      Set<String> visited = new HashSet<>(); // to avoid duplicates when traversing parents

      collectGradlePluginsAndConfigsRecursive(pom, pluginConfigSnippets, pluginsMap, visited);

      return pluginsMap;
    }

    private static void collectGradlePluginsAndConfigsRecursive(Project pom, StringBuilder pluginConfigSnippets,
        Map<String, String> pluginsMap, Set<String> visited) {

      if (pom == null)
        return;

      if (pom.build != null && pom.build.plugins != null && pom.build.plugins.plugin != null) {
        for (Plugin plugin : pom.build.plugins.plugin) {
          String pluginUniqueKey = plugin.getEffectiveGroupId() + ":" + plugin.artifactId;
          if (!visited.add(pluginUniqueKey))
            continue; // already seen in child, so skip
          // --- Call executions for the plugin, on this POM only! ---
          List<PluginExecutionContext> execs = getPluginExecutions(plugin, pom);
          for (PluginExecutionContext ctx : execs) {
            String pluginKey = ctx.plugin.getEffectiveGroupId() + ":" + ctx.plugin.artifactId + ":" + ctx.goal;
            log.info("Processing plugin execution: {}", pluginKey);
            PluginConvertor conv = pluginConversionRegistry.get(pluginKey);
            if (conv != null && conv.isEnabled(ctx, pom)) {
              String version = conv.gradlePluginVersion;
              if (conv.gradlePluginId != null)
                if (!pluginsMap.containsKey(conv.gradlePluginId) || version != null) {
                  pluginsMap.put(conv.gradlePluginId, version);
                }
              String config = conv.handler.apply(ctx, pom);
              if (config != null && !config.isBlank()) {
                pluginConfigSnippets.append("\n").append(config.trim()).append("\n");
              }
            }
          }
        }
      }

      // Always recurse to parent, even if current has no plugins
      collectGradlePluginsAndConfigsRecursive(pom.parentDirPom, pluginConfigSnippets, pluginsMap, visited);
    }

    private static String extractJavaVersionFromEffectivePom(Project pom, Cli cli) {
      String rawVersion = null;

      if (pom.build != null && pom.build.plugins != null && pom.build.plugins.plugin != null) {
        for (Plugin plugin : pom.build.plugins.plugin) {
          if ("maven-compiler-plugin".equals(plugin.artifactId) && plugin.configuration != null) {
            try {
              String source = plugin.configuration.source;
              String target = plugin.configuration.target;
              if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
                rawVersion = source.equals(target) ? source : source; // prefer source if different
                break;
              } else if (source != null && !source.isBlank()) {
                rawVersion = source;
                break;
              } else if (target != null && !target.isBlank()) {
                rawVersion = target;
                break;
              }
            } catch (Exception e) {
              throw new RuntimeException("Failed to read compiler plugin configuration", e);
            }
          }
        }
      }

      if (rawVersion == null && pom.properties != null && pom.properties.any != null) {
        for (String key : new String[] { "maven.compiler.source", "maven.compiler.target", "java.version" }) {
          String val = pom.properties.any.get(key);
          if (val != null && !val.isBlank()) {
            rawVersion = val;
            break;
          }
        }
      }

      if (rawVersion == null && pom.parentPom != null) {
        return extractJavaVersionFromEffectivePom(pom.parentPom, cli);
      }

      if (rawVersion == null) {
        if (cli.ignoreUnknownJavaVersion) {
          log.warn("No Java version found in POM " + (pom.artifactId != null ? pom.artifactId : ""));
          return "1.8";
        } else
          throw new RuntimeException(
              "Java version property not found in POM " + (pom.artifactId != null ? pom.artifactId : ""));
      }

      String resolvedVersion = resolveProperties(rawVersion, pom);

      if (resolvedVersion == null || resolvedVersion.isBlank() || resolvedVersion.contains("${")) {
        throw new RuntimeException("Failed to resolve Java version property fully in POM %s: [%s] => [%s]"
            .formatted(pom.artifactId != null ? pom.artifactId : "", rawVersion, resolvedVersion));
      }

      return resolvedVersion;
    }

    public static String resolveVersion(Dependency d, Project pom, boolean useEffectivePom, Projects effectivePom) {
      if (d.version != null && !d.version.isBlank()) {
        return d.version;
      }
      if (useEffectivePom && effectivePom != null) {
        // Search effectivePom projects for matching dependency version
        for (Project epPom : effectivePom.project) {
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

    private static void generateEffectivePom(File projectDir, Path effPomPath)
        throws IOException, InterruptedException, TimeoutException {
      String mavenCmd = isWindows() ? "mvn.cmd" : "mvn";
      int exit;
      try {
        exit = new ProcessExecutor().directory(projectDir)
            .command(mavenCmd, "help:effective-pom", "-Doutput=" + effPomPath).redirectOutput(System.out)
            .redirectError(System.err).exitValues(0) // Only allow zero exit value
            .execute().getExitValue();
      } catch (InvalidExitValueException e) {
        throw new IOException("Maven failed with exit code: " + e.getExitValue(), e);
      }
      if (exit != 0) {
        throw new IOException("Maven failed to generate " + effPomPath + " (exit " + exit + ")");
      }
    }

    private static boolean isWindows() {
      return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static Projects loadEffectivePom(boolean ignoreUnknown, Path effPomPath) throws IOException {
      if (!Files.exists(effPomPath)) {
        throw new RuntimeException("File not found at " + effPomPath);
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
        if (e.getMessage().contains("Unrecognized field \"project\"") && e.getMessage().contains("PomModel")) {
          Project singlePom = xmlMapper.readValue(xml, Project.class);
          Projects projects = new Projects();
          projects.project.add(singlePom);
          return projects;
        }
        throw e;
      }
    }

    private static String emitGradleProperties(Project pom, java.util.Set<String> usedProperties) {
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
      private boolean hasLombok;

      DependencyEmitResult(String variableBlock, String dependencyBlock, boolean hasLombok) {
        this.variableBlock = variableBlock;
        this.dependencyBlock = dependencyBlock;
        this.hasLombok = hasLombok;
      }
    }

    private static String resolveGroupId(Dependency dep, Project pom) {
      if (dep != null && dep.groupId != null && !dep.groupId.isBlank()) {
        return resolveProperties(dep.groupId, pom);
      }
      Project current = pom;
      while (current != null) {
        if (current.groupId != null && !current.groupId.isBlank()) {
          return resolveProperties(current.groupId, current);
        }
        current = current.parentPom;
      }
      return null;
    }

    private static String resolveGroupIdForPom(Project pom) {
      return resolveGroupId(null, pom);
    }

    private static String extractVersionFromProjects(String groupId, String artifactId, Projects projects) {
      if (projects == null || projects.project == null) {
        return null;
      }
      for (Project pom : projects.project) {
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
        String safeKey = toKotlinVar(m.group(1));
        m.appendReplacement(sb, "\\$" + safeKey);
      }
      m.appendTail(sb);
      return sb.toString();
    }

    private static String extractVersion(String groupId, String artifactId, Project pom) {
      Project current = pom;
      while (current != null) {
        if (current.dependencyManagement != null && current.dependencyManagement.dependencies != null
            && current.dependencyManagement.dependencies.dependency != null) {
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

    private static String resolveProperties(String value, Project pom) {
      if (value == null)
        return null;

      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(value);
      StringBuffer sb = new StringBuffer();

      while (m.find()) {
        String key = m.group(1);
        String replacement = null;

        // Special cases for common Maven properties
        if ("project.groupId".equals(key)) {
          replacement = pom.groupId != null ? pom.groupId : "";
        } else if ("project.version".equals(key)) {
          replacement = pom.version != null ? pom.version : "";
        } else {
          // Lookup in properties recursively
          Project current = pom;
          while (current != null && replacement == null) {
            if (current.properties != null && current.properties.any != null) {
              replacement = current.properties.any.get(key);
            }
            current = current.parentPom;
          }
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
