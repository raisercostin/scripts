//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.slf4j:slf4j-api:2.0.9
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1

import picocli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fusesource.jansi.AnsiConsole;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

public class mvn2gradle {
  public static void main(String... args) {
    AnsiConsole.systemInstall();
    int exitCode = new CommandLine(new Cli()).execute(args);
    AnsiConsole.systemUninstall();
    System.exit(exitCode);
  }

  @CommandLine.Command(name = "mvn2gradle", mixinStandardHelpOptions = true, description = "Convert Maven pom.xml in given directory to build.gradle.kts (single-module base)")
  public static class Cli implements Callable<Integer> {
    private static final Logger LOG = LoggerFactory.getLogger(Cli.class);

    @CommandLine.Parameters(index = "0", description = "Directory containing pom.xml")
    private File projectDir;

    @CommandLine.Option(names = { "--ignore-unknown" }, description = "Ignore unknown XML fields")
    boolean ignoreUnknown = false;

    @Override
    public Integer call() throws Exception {
      configureLogging();
      Path pomPath = projectDir.toPath().resolve("pom.xml");
      Path gradlePath = projectDir.toPath().resolve("build.gradle.kts");

      if (!Files.exists(pomPath)) {
        LOG.error("No pom.xml found at {}", pomPath);
        return 1;
      }

      LOG.info("Parsing {}", pomPath);
      String xml = Files.readString(pomPath);
      XmlMapper xmlMapper = new XmlMapper();
      xmlMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
          !ignoreUnknown);

      PomModel pom = xmlMapper.readValue(xml, PomModel.class);

      LOG.info("Generating build.gradle.kts for {}", pom.artifactId);
      String gradleKts = GradleKtsGenerator.generate(pom);

      Files.writeString(gradlePath, gradleKts);

      LOG.info("Done: {}", gradlePath.toAbsolutePath());
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

  public static class GradleKtsGenerator {
    public static String generate(PomModel pom) {
      java.util.Set<String> usedProperties = collectUsedProperties(pom);
      java.util.List<String> inheritedVersionVars = new java.util.ArrayList<>();
      String propertyBlock = emitGradleProperties(pom, usedProperties);
      String dependenciesBlock = emitGradleDependencies(pom, usedProperties, inheritedVersionVars);

      // Join inherited version variables
      String inheritedVarsBlock = String.join("", inheritedVersionVars);

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
          propertyBlock, inheritedVarsBlock, pom.groupId, pom.version, dependenciesBlock);
    }

    // Now dependencies function collects variables in inheritedVersionVars:
    private static String emitGradleDependencies(PomModel pom, java.util.Set<String> usedProperties,
        java.util.List<String> inheritedVersionVars) {
      StringBuilder deps = new StringBuilder();

      if (pom.dependencies != null && pom.dependencies.dependency != null) {
        for (Dependency d : pom.dependencies.dependency) {
          String versionExpr = "";
          if (d.version != null && !d.version.isBlank()) {
            versionExpr = ":" + replaceMavenPropsWithKotlinVars(d.version);
          } else {
            // missing version—likely inherited, generate variable for it
            String varName = safeDepVarName(d.groupId, d.artifactId);
            versionExpr = ":$" + varName;
            inheritedVersionVars.add(
                String.format(
                    "val %s = extractVersion(\"%s:%s\") // FIXME: implement extraction for inherited version\n",
                    varName, d.groupId, d.artifactId));
          }
          String conf = toGradleConf(d.scope);
          deps.append(String.format("    %s(\"%s:%s%s\")\n",
              conf, d.groupId, d.artifactId, versionExpr));
        }
      }
      return deps.toString();
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

    private static String extractVersion(String groupId, String artifactId, PomModel pom) {
      // 1. Try dependencyManagement
      if (pom.dependencyManagement != null &&
          pom.dependencyManagement.dependencies != null &&
          pom.dependencyManagement.dependencies.dependency != null) {
        for (Dependency dep : pom.dependencyManagement.dependencies.dependency) {
          if (groupId.equals(dep.groupId) && artifactId.equals(dep.artifactId) && dep.version != null) {
            return dep.version;
          }
        }
      }
      // 2. Optionally: Check parent POM, if available
      // TODO: Implement recursive parent support, if needed
      return null;
    }

    private static String safeDepVarName(String groupId, String artifactId) {
      return "inherited" +
          Character.toUpperCase(groupId.charAt(0)) + groupId.substring(1).replaceAll("[^a-zA-Z0-9]", "") +
          Character.toUpperCase(artifactId.charAt(0)) + artifactId.substring(1).replaceAll("[^a-zA-Z0-9]", "") +
          "Version";
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
