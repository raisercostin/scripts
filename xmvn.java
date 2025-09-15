//usr/bin/env jbang "$0" "$@" ; exit $?
//Description: Convert Multimodule Maven pom.xml to Gradle build.gradle.kts
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
//DEPS com.google.guava:guava:32.1.2-jre
//SOURCES com/namekis/utils/RichLogback.java
//FILES xmvn-graph.html

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.fusesource.jansi.AnsiConsole;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.google.common.graph.Traverser;
import com.namekis.utils.RichLogback;

import one.util.streamex.StreamEx;
import picocli.CommandLine;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;

public class xmvn {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(xmvn.class);

  public static void main(String... args) {
    AnsiConsole.systemInstall();
    int exitCode = new CommandLine(new XmvnRoot()).execute(args);
    AnsiConsole.systemUninstall();
    System.exit(exitCode);
  }

  @CommandLine.Command(name = "xmvn", mixinStandardHelpOptions = true, version = "0.1", description = """
      xmvn - extract Maven models and emit them in other formats.""", subcommands = { xmvn.ToGradle.class, xmvn.ToArangoGraph.class,
      xmvn.ToGraph.class })
  static class XmvnRoot implements Runnable {
    @Override
    public void run() {
      log.info("XMvn root command invoked without subcommand. Use --help for available commands.");
      new CommandLine(this).usage(System.out);
    }
  }

  public abstract static class CommonOptions {
    @Option(names = { "-v", "--verbose" }, description = "Increase verbosity. Specify multiple times to increase (-vvv).")
    boolean[] verbosity = new boolean[0];

    @Option(names = { "-q", "--quiet" }, description = "Suppress all output except errors.")
    boolean quiet = false;

    @Option(names = { "-c",
        "--color" }, description = "Enable colored output (default: true).", defaultValue = "true", showDefaultValue = Visibility.ALWAYS)
    public boolean color = true;

    @Option(names = { "-d", "--debug" }, description = "Enable debug (default: false).", defaultValue = "false", showDefaultValue = Visibility.ALWAYS)
    public boolean debug = false;
  }

  public abstract static class LoadPomOptions extends CommonOptions {
    @CommandLine.Parameters(index = "0", description = "Directory containing pom.xml")
    public File projectDir;
    @CommandLine.Option(names = { "--ignore-unknown" }, description = "Ignore unknown XML fields")
    public boolean ignoreUnknown = false;

    @Option(names = "--use-effective-pom", description = "Use mvn help:effective-pom")
    public boolean useEffectivePom = true;

    @Option(names = "--use-pom-inheritance", description = "Use recursive pom.xml parent inheritance", defaultValue = "true")
    public boolean usePomInheritance = true;

    @Option(names = "--force-generate-effective-pom", description = "Force regeneration of effective-pom.xml even if it exists", defaultValue = "true", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public boolean forceGenerateEffectivePom = true;

    @Option(names = "--debug-repositories", description = "Debug info on repositories", defaultValue = "false", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public boolean debugRepositories = false;
  }

  @CommandLine.Command(name = "2graph", mixinStandardHelpOptions = true, description = """
      Generate Sigma.js visualization from Maven multi-module project.
      Produces:
      - graph-data.json : Graphology JSON with nodes and edges (unless --no-embed-data)
      - graph.html      : HTML viewer using Sigma.js
      """)
  public static class ToGraph extends LoadPomOptions implements Callable<Integer> {
    @Option(names = "--embed-data", negatable = true, description = "Embed JSON data directly in HTML (default: true)", defaultValue = "true")
    private boolean embedData;

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      Project rootPom = xmvn.PomLoader.loadRootPom(this);
      generateGraphFiles(rootPom);
      return 0;
    }

    public class GraphData {
      public SortedMap<String, Node> nodes = new TreeMap<>();
      public SortedMap<String, Edge> edges = new TreeMap<>();
      public Map<String, Object> attributes = new LinkedHashMap<>();
    }

    public static class Node {
      public final String key;
      public final NodeAttributes attributes;

      public Node(String key, NodeAttributes attributes) {
        this.key = key;
        this.attributes = attributes;
      }
    }

    // SigmaJS-compatible node/edge model for graph export
    public static class NodeAttributes {
      public final double x, y, size;
      public final String color;
      public final String label;
      // Optionally: image, shape, etc.
      public final NodeMeta meta;

      public NodeAttributes(String label, String color, double x, double y, double size, NodeMeta meta) {
        this.label = label;
        this.color = color;
        this.x = x;
        this.y = y;
        this.size = size;
        this.meta = meta;
      }
    }

    public static class NodeMeta {
      public final String nodeType;     // e.g. "module", "library", "pom", "bom", etc.
      public final String buildTool;    // e.g. "maven", "gradle", "make", etc.
      public final String artifactType; // e.g. "jar", "war", "ear", etc.
      public final String groupId;
      public final String artifactId;
      public final String version;

      /**
       * All fields explicit; pass null for what is not set.
       * @param nodeType      Logical type: "module", "library", "pom", etc. (REQUIRED)
       * @param buildTool     Origin: "maven", "gradle", "make", ...
       * @param artifactType  Artifact type: "jar", "war", etc.
       * @param groupId       Maven group
       * @param artifactId    Maven artifact
       * @param version       Version
       */
      public NodeMeta(String nodeType, String buildTool, String artifactType, String groupId, String artifactId, String version) {
        this.nodeType = nodeType;
        this.buildTool = buildTool;
        this.artifactType = artifactType;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
      }
    }

    public static class Edge {
      public final String key, source, target;
      public final EdgeAttributes attributes;

      public Edge(String key, String source, String target, EdgeAttributes attributes) {
        this.key = key;
        this.source = source;
        this.target = target;
        this.attributes = attributes;
      }
    }

    public static class EdgeAttributes {
      public final double size;
      public final String color;
      public final String label;
      public final EdgeMeta meta;

      public EdgeAttributes(double size, String color, String label, EdgeMeta meta) {
        this.size = size;
        this.color = color;
        this.label = label;
        this.meta = meta;
      }
    }

    public static class EdgeMeta {
      public final String edgeType;   // e.g. "compile", "runtime", "parent", etc.
      public final String scope;      // e.g. "test", "compile", etc.
      public final String protocol;   // e.g. "rest", "soap", "graphql"
      public final String relation;   // e.g. "parent"
      public final String classifier;
      public final String type;

      /**
       * All fields explicit; pass null for what is not set.
       * @param edgeType   Logical edge type: "compile", "parent", etc. (REQUIRED)
       * @param scope      Dependency scope
       * @param protocol   Service protocol
       * @param relation   Parent/child or other relationship
       * @param classifier Maven classifier
       * @param type       Maven type (jar, war, pom, ...)
       */
      public EdgeMeta(String edgeType, String scope, String protocol, String relation, String classifier, String type) {
        this.edgeType = edgeType;
        this.scope = scope;
        this.protocol = protocol;
        this.relation = relation;
        this.classifier = classifier;
        this.type = type;
      }
    }

    private void generateGraphFiles(xmvn.Project rootPom) throws IOException {
      GraphData graph = new GraphData();
      //      Traverser<xmvn.Project> traverser = Traverser.forTree(p -> {
      //        if (p.modules != null && p.modules.module != null) {
      //          return p.modules.module.stream().map(m -> xmvn.PomLoader.loadPom(null, p, new File(p.pomFile.getParentFile(), m), p.context))
      //              .filter(Objects::nonNull).toList();
      //        }
      //        return List.of();
      //      });
      //      traverser.depthFirstPreOrder(rootPom).forEach(p -> emitProjectAndDeps(p, graph));
      rootPom.effectivePomOrThis().context.effectivePom.project.forEach(ep -> emitProjectAndDeps(ep, graph));
      log.info("Graph has {} nodes and {} edges", graph.nodes.size(), graph.edges.size());
      ObjectMapper om = new ObjectMapper();
      Map<String, Object> g = Map.of("nodes", new ArrayList<>(graph.nodes.values()), "edges", new ArrayList<>(graph.edges.values()));
      String jsonString = om.writerWithDefaultPrettyPrinter().writeValueAsString(g);

      // --- Load HTML template from classpath ---
      String html;
      try (InputStream in = getClass().getResourceAsStream("/xmvn-graph.html")) {
        if (in == null)
          throw new FileNotFoundException("Resource not found: /xmvn-graph.html");
        html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }

      String replaced = html.replaceAll("(?s)/\\* XMVN_DATA_START \\*/.*?/\\* XMVN_DATA_END \\*/",
          "/* XMVN_DATA_START */\n" + jsonString + "\n/* XMVN_DATA_END */");

      File htmlFile = new File(projectDir, "xmvn-graph.html");
      Files.writeString(htmlFile.toPath(), replaced, java.nio.charset.StandardCharsets.UTF_8);
      log.info("Sigma v3 HTML with filters+highlight written to {}", htmlFile.getAbsolutePath());
    }

    private void emitProjectAndDeps(xmvn.Project project, GraphData graph) {
      project = project.effectivePomOrThis();
      String projKey = project.ga();

      // ---- Project Node ----
      NodeAttributes projAttrs = new NodeAttributes(project.ga(), nodeColor("project"), 0, 0, 7.0,
          new NodeMeta("project", "maven", null, project.groupId, project.artifactId, project.version));
      addNode(graph.nodes, projKey, projAttrs);

      // ---- Dependencies ----
      if (project.dependencies != null && project.dependencies.dependency != null) {
        for (var dep : project.dependencies.dependency) {
          String depKey = dep.ga();

          // Node attributes & meta for library/dependency
          String depType = dep.scope;//("pom".equals(dep.type) && "import".equals(dep.scope)) ? "bom" : "library";
          String depColor = nodeColor(depType);
          NodeAttributes depAttrs = new NodeAttributes(dep.ga(), depColor, 0, 0, 6.0, new NodeMeta(depType, null,   // buildTool
              dep.type, // artifactType
              dep.groupId, dep.artifactId, dep.version));
          addNode(graph.nodes, depKey, depAttrs);

          // Edge attributes & meta
          String edgeType = dep.scope;//"bom".equals(depType) ? "bom-import" : "lib";
          String edgeColor = depColor;
          String edgeScope = dep.scope != null ? dep.scope : "compile";
          EdgeAttributes edgeAttrs = new EdgeAttributes(1.0, edgeColor, edgeScope, new EdgeMeta(edgeType, edgeScope, null, // protocol
              null, // relation
              dep.classifier, dep.type));
          String edgeKey = projKey + "/" + depKey;
          addEdge(graph.edges, edgeKey, projKey, depKey, edgeAttrs);
        }
      }

      // ---- Parent Relation ----
      if (project.parentPom != null) {
        var parent = project.parentPom.effectivePomOrThis();
        String parentKey = parent.ga();
        NodeAttributes parentAttrs = new NodeAttributes(parent.groupId + ":" + parent.artifactId, nodeColor("parent"), 0, 0, 6.0,
            new NodeMeta("parent", null, "pom", parent.groupId, parent.artifactId, parent.version));
        addNode(graph.nodes, parentKey, parentAttrs);

        EdgeAttributes parentEdgeAttrs = new EdgeAttributes(1.0, nodeColor("parent"), "parent",
            new EdgeMeta("parent", null, null, "parent", null, "pom"));
        String edgeKey = projKey + "_parent_" + parentKey;
        addEdge(graph.edges, edgeKey, projKey, parentKey, parentEdgeAttrs);
      }
      // ---- Parent Relation ----
      if (project.parentDirPom != null) {
        var parent = project.parentDirPom.effectivePomOrThis();
        String parentKey = parent.ga();
        NodeAttributes parentAttrs = new NodeAttributes(parent.groupId + ":" + parent.artifactId, nodeColor("parentDir"), 0, 0, 6.0,
            new NodeMeta("parentDir", null, "pom", parent.groupId, parent.artifactId, parent.version));
        addNode(graph.nodes, parentKey, parentAttrs);

        EdgeAttributes parentEdgeAttrs = new EdgeAttributes(1.0, nodeColor("parentDir"), "parentDir",
            new EdgeMeta("parentDir", null, null, "parentDir", null, "pom"));
        String edgeKey = projKey + "_parentDir_" + parentKey;
        addEdge(graph.edges, edgeKey, projKey, parentKey, parentEdgeAttrs);
      }
    }

    private static Node addNode(SortedMap<String, Node> nodes, String key, NodeAttributes attributes) {
      Node existing = nodes.get(key);
      if (existing == null) {
        Node n = new Node(key, attributes);
        nodes.put(key, n);
        return n;
      }

      // Priority: project > others
      if (!"project".equals(existing.attributes.meta.nodeType) && "project".equals(attributes.meta.nodeType)) {
        Node n = new Node(key, attributes);
        nodes.put(key, n);
        return n;
      }

      return existing;
    }

    private static Edge addEdge(SortedMap<String, Edge> edges, String key, String source, String target, EdgeAttributes attributes) {
      Edge existing = edges.get(key);
      if (existing == null) {
        Edge e = new Edge(key, source, target, attributes);
        edges.put(key, e);
        return e;
      }
      return existing;
    }

    private String nodeColor(String scope) {
      //log.info("Color for scope/type {}", scope);
      return switch (scope) {
      case "project" -> "#1f77b4";   // blue
      case "compile" -> "red";         // red
      case "provided" -> "#17becf";  // cyan
      case "runtime" -> "#2ca02c";   // green
      case "test" -> "#ff7f0e";      // orange
      case "system" -> "#9467bd";    // purple
      case "import" -> "#8c564b";    // brown
      case "bom" -> "#e377c2";       // pink
      case "parentDir" -> "#e3A7c2"; // light pink 
      default -> "#7f7f7f";          // grey
      };
    }
  }

  @CommandLine.Command(name = "2arango", mixinStandardHelpOptions = true, description = """
      Generate ArangoDB graph from Maven multi-module project.
      """)
  public static class ToArangoGraph extends LoadPomOptions implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      Project rootPom = xmvn.PomLoader.loadRootPom(this);
      generateArangoGraph(rootPom);
      return 0;
    }

    private void generateArangoGraph(xmvn.Project rootPom) {
      List<String> projectDocs = new ArrayList<>();
      List<String> dependencyDocs = new ArrayList<>();
      List<String> edgeDocs = new ArrayList<>();

      StringBuilder script = new StringBuilder();
      script.append("""
          // AQL script for ArangoDB
          //echo Check password
          //docker logs arangodb-instance
          //===========================================
          //GENERATED ROOT PASSWORD: ***
          //===========================================
          //echo Connect to arangodb
          //docker exec -it arangodb-instance arangosh
          //echo Create database xmvn
          //use xmvn
          """);

      Traverser<xmvn.Project> traverser = Traverser.forTree(p -> {
        if (p.modules != null && p.modules.module != null) {
          return p.modules.module.stream().map(m -> xmvn.PomLoader.loadPom(null, p, new File(p.pomFile.getParentFile(), m), p.context))
              .filter(Objects::nonNull).toList();
        }
        return List.of();
      });

      traverser.depthFirstPreOrder(rootPom).forEach(p -> emitProjectAndDeps(p, projectDocs, dependencyDocs, edgeDocs));

      if (!projectDocs.isEmpty()) {
        script.append("""
            FOR doc IN [
            %s
            ] INSERT doc INTO projects OPTIONS { overwrite: true }

            """.formatted(String.join(",\n", projectDocs)));
      }
      if (!dependencyDocs.isEmpty()) {
        script.append("""
            FOR doc IN [
            %s
            ] INSERT doc INTO dependencies OPTIONS { overwrite: true }

            """.formatted(String.join(",\n", dependencyDocs)));
      }
      if (!edgeDocs.isEmpty()) {
        script.append("""
            FOR doc IN [
            %s
            ] INSERT doc INTO edges OPTIONS { overwrite: true }

            """.formatted(String.join(",\n", edgeDocs)));
      }

      System.out.println(script.toString());
    }

    private void emitProjectAndDeps(xmvn.Project project, List<String> projects, List<String> dependencies, List<String> edges) {
      String projKey = (project.groupId + "_" + project.artifactId).replaceAll("[^a-zA-Z0-9_]", "_");

      projects.add("""
          { _key: "%s", groupId: "%s", artifactId: "%s", version: "%s", packaging: "%s" }
          """.formatted(projKey, project.groupId, project.artifactId, project.version != null ? project.version : "unknown",
          project.packaging != null ? project.packaging : "jar"));

      if (project.dependencies != null && project.dependencies.dependency != null) {
        for (var dep : project.dependencies.dependency) {
          String depKey = (dep.groupId + "_" + dep.artifactId).replaceAll("[^a-zA-Z0-9_]", "_");

          dependencies.add("""
              { _key: "%s", groupId: "%s", artifactId: "%s", version: "%s", packaging: "%s" }
              """.formatted(depKey, dep.groupId, dep.artifactId, dep.version != null ? dep.version : "unknown", dep.type != null ? dep.type : "jar"));

          edges.add("""
              { _from: "projects/%s", _to: "dependencies/%s", scope: "%s" }
              """.formatted(projKey, depKey, dep.scope != null ? dep.scope : "compile"));
        }
      }
    }

  }

  @CommandLine.Command(name = "2gradle", mixinStandardHelpOptions = true, description = "Convert a maven multi-module base to equivalent gradle build.", footer = """
      Convert a maven multi-module base to equivalent gradle build.

      ## Features

      ## History

      - 2025-02-08
        - new: lombok compatibility
        - new: inheritance of dependencies from parent
        - new: inheritance of plugin configs from parent
        - new: use testJars dependencies directly at compile - no jar build necessary
        - new: use submodules dependencies independent of location (as long as they are locally)
        - new: annotation processor are handled as well: immutable, mapstruct, etc.
        - new: antlr4 support
        - new: compile excludes support
        - new: proper config of mavenLocal
        - new: compiler can be configured with `-P-GCompiler-Xlint:unchecked -P-GCompiler-nowarn`
      - 2025-08-17
        - new: add jaxb jxc plugin configs

      ## TODO

      - self-check that generates same artifacts as mvn - same content and size but faster
      - cache runs of gradle build. do not ovewrite if is identical
      - add a front build that behinds generates gradle build files and runs gradle build
      - add a build to eclipse standard projects (not dependent on gradle or maven or other natures) but properly configures dependencies
        between submodules and generates eclipse project files
      """)
  public static class ToGradle extends LoadPomOptions implements Callable<Integer> {
    @Option(names = "--inline-versions", description = "Inline dependency versions instead of using val variables")
    public boolean inlineVersions = false;

    @Option(names = "--maven-compatible", description = "Generate settings.gradle.kts compatible with Maven:\n - generate inside target/gradle")
    public boolean mavenCompatible = true;

    @Option(names = "--ignore-unknown-versions", description = "Ignore unknown dependencies versions (useful in debug)", defaultValue = "false", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public boolean ignoreUnknownVersions = false;

    @Option(names = "--ignore-unknown-java-version", description = "Ignore unknown Java version in pom.xml (useful in debug)", defaultValue = "true", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public boolean ignoreUnknownJavaVersion = true;

    @Option(names = "--use-implementation-dependencies", description = "Use implementation dependencies instead of api. Use this if you want to compile only against explicit dependencies.", defaultValue = "false", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
    public Boolean useImplementationDependencies = null;

    @Option(names = "--use-api-dependencies", description = "Use api dependencies instead of implementation. Dependencies appearing in the api configurations will be transitively exposed to consumers of the library, and as such will appear on the compile classpath of consumers. This is the default since maven offers only this.", defaultValue = "true", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    public Boolean useApiDependencies = null;

    @Option(names = "--force-provided-for-tests", description = "Force compileOnly+testImplementation for the maven scope=provided libraries [:group:artifact1:,:group2:artifact2:]", defaultValue = ":org.apache.maven:maven-compat:", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    public String forceProvidedForTests = ":org.apache.maven:maven-compat:";

    @Override
    public Integer call() throws Exception {
      return GradleKtsGenerator.sync(this);
    }

    public boolean useApiDependencies() {
      if (useApiDependencies == useImplementationDependencies) {
        throw new IllegalArgumentException("Cannot use both --use-api-dependencies and --use-implementation-dependencies at the same time.");
      }
      return useApiDependencies && !useImplementationDependencies;
    }
  }

  public static class EffectivePom extends Project {
  }

  public static class Projects {
    public List<EffectivePom> project = new ArrayList<>();
  }

  public static class ProjectContext {
    public final LoadPomOptions cli;
    public final Project root;
    public final Projects effectivePom;

    public ProjectContext(LoadPomOptions cli, Project root, Projects effectivePom) {
      this.cli = cli;
      this.root = root;
      this.effectivePom = effectivePom;
      log.info("Store context in each effective pom");
      effectivePom.project.forEach(p -> p.context = this);
    }

    public ProjectContext withRoot(Project newRoot) {
      return new ProjectContext(cli, newRoot, effectivePom);
    }
  }

  @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement(localName = "project")
  public static class Project {
    // --- Root attributes (namespace, schema) ---
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(isAttribute = true, localName = "xmlns")
    public String xmlns;

    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(isAttribute = true, localName = "xsi", namespace = "http://www.w3.org/2000/xmlns/")
    public String xmlnsXsi;

    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(isAttribute = true, localName = "schemaLocation", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public String schemaLocation;

    // --- Standard Maven POM fields ---
    public String modelVersion;
    @JsonProperty("parent")
    public Gav parentGav;
    /**
     * The parent described above. Could exist locally in a parent or sibling dir or
     * not at all. A sibling is not standard in maven but could be useful when
     * transitioning to gradle from a multi-repo.
     */
    public transient Project parentPom;
    public transient ProjectContext context;
    public transient AtomicReference<Project> effectivePom;
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

    private Project() {
    }

    @Override
    public String toString() {
      return "Project[groupId='%s', artifactId='%s', version='%s']".formatted(groupId, artifactId, version);
    }

    public String id() {
      return "%s:%s:%s".formatted(groupId, artifactId, version != null ? version : "SNAPSHOT");
    }

    public String idAndPath() {
      return "%s:%s:%s@[%s]".formatted(groupId, artifactId, version != null ? version : "SNAPSHOT", pomFile.getAbsolutePath());
    }

    public Project effectivePom() {
      if (effectivePom != null)
        // could also be null
        return effectivePom.get();
      if (context == null) {
        throw new RuntimeException("oh");
      }
      if (context.cli.useEffectivePom && context.effectivePom != null)
        effectivePom = new AtomicReference<>(findEffectivePomFor(this, context.effectivePom));
      return effectivePom.get();
    }

    public static EffectivePom findEffectivePomFor(Project pom, Projects effectivePom) {
      if (effectivePom == null || effectivePom.project == null) {
        return null;
      }
      for (EffectivePom epPom : effectivePom.project) {
        if (pom.groupId != null && pom.groupId.equals(epPom.groupId) && pom.artifactId != null && pom.artifactId.equals(epPom.artifactId)) {
          epPom.pomFile = pom.pomFile;
          return epPom;
        }
      }
      return null;
    }

    public String ga() {
      return "%s:%s".formatted(groupId, artifactId);
    }

    public Project effectivePomOrThis() {
      var effectivePom = effectivePom();
      return effectivePom != null ? effectivePom : this;
    }

    public Plugin compilerPlugin() {
      if (build != null && build.plugins != null && build.plugins.plugin != null) {
        for (Plugin plugin : build.plugins.plugin) {
          if ("maven-compiler-plugin".equals(plugin.artifactId) && plugin.configuration != null) {
            return plugin;
          }
        }
      }
      return new Plugin();
    }
  }

  public static class Gav {
    public String groupId;
    public String artifactId;
    public String version;
    public String relativePath;

    private Gav() {
    }

    @Override
    public String toString() {
      return "Parent[groupId='%s', artifactId='%s', version='%s', relativePath='%s']".formatted(groupId, artifactId, version, relativePath);
    }

    public String id() {
      return "%s:%s:%s".formatted(groupId, artifactId, version != null ? version : "SNAPSHOT");
    }

    public String ga() {
      return "%s:%s".formatted(groupId, artifactId);
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

  public static class Contributors {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "contributor")
    public List<Contributor> contributor;

    private Contributors() {
    }
  }

  public static class Contributor {
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

  public static class MailingLists {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "mailingList")
    public List<MailingList> mailingList;

    private MailingLists() {
    }
  }

  public static class MailingList {
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

  public static class Prerequisites {
    public String maven;

    private Prerequisites() {
    }
  }

  public static class CiManagement {
    public String system;
    public String url;

    private CiManagement() {
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
    public Boolean optional;
    public Exclusions exclusions;

    private Dependency() {
    }

    public String ga() {
      return "%s:%s".formatted(groupId, artifactId);
    }

    @Override
    public String toString() {
      return "%s:%s:%s:%s:%s%s:%s:%s']".formatted(groupId, artifactId, version, scope, type, classifier, optional, exclusions);
    }

    /** Ga with prefix and suffix to match start and end. */
    public String gaFull() {
      return ":%s:%s:".formatted(groupId, artifactId);
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

  public static class Resources {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "resource")
    public java.util.List<Resource> resource;

    private Resources() {
    }
  }

  public static class TestResources {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty(localName = "testResource")
    public java.util.List<Resource> testResource;

    private TestResources() {
    }
  }

  public static class Resource {
    public String directory;
    public String targetPath;
    public Boolean filtering;
    public Includes includes;
    public Excludes excludes;

    // ...other fields if you want, such as mergeId, etc.
    private Resource() {
    }
  }

  public static class Includes {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<String> include;

    private Includes() {
    }
  }

  public static class Excludes {
    @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
    public java.util.List<String> exclude;

    private Excludes() {
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
    public Boolean inherited;
    public Boolean extensions;
    public Executions executions;
    public Dependencies dependencies;
    public PluginConfiguration configuration = new PluginConfiguration();

    private Plugin() {
    }

    public String getEffectiveGroupId() {
      return groupId != null ? groupId : "org.apache.maven.plugins";
    }

    @Override
    public String toString() {
      return "%s:%s:%s".formatted(getEffectiveGroupId(), artifactId, version != null ? version : "");
    }
  }

  public static class PluginConfiguration {
    // maven-compiler-plugin - properties
    public String source;
    public String target;
    @JacksonXmlElementWrapper(localName = "excludes")
    @JacksonXmlProperty(localName = "exclude")
    public java.util.List<String> excludes;
    // checkstyle plugin
    public String skip;
    public String configLocation;

    // jaxb plugin
    public String jaxbVersion = "${jaxb.version}";
    public String generatePackage = "";
    public String generateDirectory = "target/generated-sources-jaxb";
    public String schemaDirectory = "src/main/resources";

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.Map<String, Object> any = new java.util.HashMap<>();

    private PluginConfiguration() {
    }

    public boolean skip() {
      return skip != null && (skip.equals("true") || skip.equals("skip"));
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
    public PluginConfiguration configuration;
    public Boolean inherited;

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
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public java.util.Map<String, Object> configuration = new java.util.HashMap<>();

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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GradleKtsGenerator.class);
    private static final Map<String, PluginConvertor> pluginConversionRegistry = new HashMap<>();

    // Helper record/class
    public static class PluginConvertor {
      public String name;
      private String mavenGroupAndArtifactId;
      public final String gradlePluginId;
      public final String gradlePluginVersion;
      // final String pluginDeclaration; // e.g., id("org.javacc.javacc") version
      // "4.0.1"
      public final BiFunction<PluginExecutionContext, Project, String> handler;

      PluginConvertor(String name, String mavenKey, String gradlePluginId, String gradlePluginVersion,
          BiFunction<PluginExecutionContext, Project, String> handler) {
        this.name = name;
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
      register(new PluginConvertor("javacc", "org.codehaus.mojo:javacc-maven-plugin:javacc", "org.javacc.javacc", "4.0.1", (ctx, pom) -> """
          tasks {
              compileJavacc {
                  inputDirectory = file("src/main/javacc")
                  outputDirectory = file(layout.buildDirectory.dir("generated/javacc"))
                  arguments = mapOf("grammar_encoding" to "UTF-8", "static" to "false")
              }
          }
          sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/javacc"))
          """));
      register(new PluginConvertor("checkstyle", "org.apache.maven.plugins:maven-checkstyle-plugin:default", "checkstyle", null, (ctx, pom) -> {
        if (ctx.configuration == null || ctx.configuration.skip()) {
          return "//checkstyle skip";
        }
        java.nio.file.Path configPath = java.nio.file.Paths.get(ctx.configuration.configLocation);
        if (!java.nio.file.Files.exists(configPath)) {
          return "//checkstyle config file not found: " + ctx.configuration.configLocation;
        }
        return """
            checkstyle {
                isIgnoreFailures = %s // Equivalent to <skip>true</skip>
                configFile = file("%s")
            }
            """.formatted(ctx.configuration.skip() ? "true" : "false", ctx.configuration.configLocation);
      }) {
        @Override
        public boolean isEnabled(PluginExecutionContext ctx, Project pom) {
          return ctx.configuration == null || !ctx.configuration.skip();
        }
      });
      // ...and so on
      register(new PluginConvertor("testJars", "org.apache.maven.plugins:maven-jar-plugin:test-jar", null, null, (ctx, pom) -> """
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
      // register(
      // new PluginConvertor("org.codehaus.mojo:build-helper-maven-plugin:add-source",
      // null, null, (ctx, pom) -> """
      // sourceSets {
      // main {
      // java {
      // srcDir("target/generated-sources/javacc")
      // }
      // }
      // }
      // """));
      register(new PluginConvertor("antlr", "org.antlr:antlr4-maven-plugin:antlr4", null, null, (ctx, pom) -> {
        String version = ctx.plugin.version != null ? ctx.plugin.version : "unknown";
        return """
            //antlr4 tooling
            val grammarRoot = file("src/main/antlr4")
            val outputDir = file("target/generated-sources/antlr4")

            tasks.register("generateGrammarSource") {
                group = "antlr"
                description = "Generate ANTLR sources - one invocation per .g4 file with correct working dir"

                doLast {
                    val files = grammarRoot.walkTopDown()
                        .filter { it.isFile && it.extension == "g4" }
                        .toList()

                    files.forEach { g4file ->
                        val relativePath = g4file.parentFile.relativeTo(grammarRoot).invariantSeparatorsPath
                        val packageName = if (relativePath.isEmpty()) "" else relativePath.replace('/', '.')

                        javaexec {
                            workingDir = grammarRoot
                            classpath = configurations.annotationProcessor.get()
                            mainClass.set("org.antlr.v4.Tool")

                            val argsList = mutableListOf("-visitor", "-listener", "-o", outputDir.absolutePath)
                            if (packageName.isNotEmpty()) {
                                argsList.addAll(listOf("-package", packageName))
                            }
                            // Provide grammar path relative to grammarRoot because workingDir is grammarRoot
                            argsList.add(g4file.relativeTo(grammarRoot).path)

                            args = argsList

                            println("Running ANTLR on ${g4file.relativeTo(file(".")).path} with package: $packageName")
                        }
                    }
                }
            }

            tasks.named("compileJava") {
                dependsOn("generateGrammarSource")
            }

            sourceSets["main"].java.srcDir(outputDir)

            dependencies {
                implementation("org.antlr:antlr4-runtime:%s")
                annotationProcessor("org.antlr:antlr4:%s")
            }
            """.formatted(version, version);
      }));
      register(new PluginConvertor("jaxb", "org.jvnet.jaxb:jaxb-maven-plugin:generate", null, null, (ctx, pom) -> {
        // resolve ctx.configuration.schemaDirectory relative to pom as path
        Path schemaDirPath = Paths.get(pom.pomFile.getParent(), ctx.configuration.schemaDirectory).toAbsolutePath();

        // schema directory contains xsd files
        if (ctx.configuration.schemaDirectory == null || !Files.exists(schemaDirPath)) {
          return "//jaxb plugin disabled - no schema directory " + schemaDirPath;
        }

        // check xsd files by walking the directory via Path api
        try {
          long count = Files.walk(schemaDirPath).filter(FileSystems.getDefault().getPathMatcher("glob:**/*.xsd")::matches).count();
          if (count == 0) {
            return "//jaxb plugin disabled - no xsd files found in " + schemaDirPath;
          }
        } catch (IOException e) {
          throw new RuntimeException("Error checking xsd files in " + schemaDirPath, e);
        }
        return jbangAndXjcPlugin.formatted("0.128.7", "4.0.5", ctx.configuration.generatePackage,
            // linuxPath(ctx.configuration.generateDirectory),
            linuxPath(ctx.configuration.schemaDirectory));
      }));
    }

    private static String linuxPath(String dir) {
      return dir.replace('\\', '/').replaceAll("/$", "");
    }

    public static Integer sync(ToGradle cli) throws Exception {
      log.info("Sync ...");
      Project rootPom = PomLoader.loadRootPom(cli);
      generateGradle(rootPom);
      log.info("Sync done.");
      return 0;
    }

    private static void generateGradle(Project rootPom) throws IOException {
      ToGradle cli2 = (xmvn.ToGradle) rootPom.context.cli;
      // At top-level in sync
      GradleModules gradleModules = collectModuleArtifactIdToGradlePath(rootPom, cli2.projectDir, cli2.ignoreUnknown, rootPom.context.effectivePom);

      // Generate settings.gradle.kts for root project and modules
      String settingsGradle = generateSettingsGradleKts(rootPom);
      Files.writeString(cli2.projectDir.toPath().resolve("settings.gradle.kts"), settingsGradle);
      log.info("Generated settings.gradle.kts");

      // Generate build.gradle.kts recursively for root and modules
      generateForModulesRecursively(cli2.projectDir.toPath(), rootPom, cli2, rootPom.context.effectivePom, gradleModules, true);
    }

    public static String generateSettingsGradleKts(Project rootPom) {
      File rootDir = rootPom.context.cli.projectDir;
      String rootName = rootPom.artifactId != null ? rootPom.artifactId : "rootProject";
      List<String> includes = collectAllModulePaths(rootPom, "", rootDir);

      String includesStr = StreamEx.of(includes).map(m -> "include(\"" + m + "\")").joining("\n");
      String mirrorsRepositoriesBlock = generateGradleRepositoriesFromMirrors(
          readMavenSettings(new File(System.getProperty("user.home"), ".m2/settings.xml")));
      return """
          rootProject.name = "%s"
          dependencyResolutionManagement {
            %s
          }
          %s
          %s
          """.formatted(rootName, prefixLines(repositories(mirrorsRepositoriesBlock), "", "  "), includesStr,
          rootPom.context.cli.debugRepositories ? debugRepositories() : "");
    }

    private static String prefixLines(String allLines, String firstLinePrefix, String restPrefix) {
      return new StringJoiner("\n").add(firstLinePrefix + allLines.lines().findFirst().orElse(""))
          .add(allLines.lines().skip(1).map(line -> restPrefix + line).collect(Collectors.joining("\n"))).toString();
    }

    private static String repositories(String additionalRepositoriesBlock) {
      return """
          repositories {
            //Do not use just mavenLocal() as ignores files if maven-metadata-local.xml is not found - https://discuss.gradle.org/t/mavenlocal-headaches/39104/7
            mavenLocal {
              metadataSources {
                mavenPom()
                artifact()
              }
            }
            %s
            mavenCentral()
          }
          """
          .formatted(prefixLines(additionalRepositoriesBlock, "", "  "));
    }

    private static String debugRepositories() {
      String debugRepositories = """
          gradle.projectsEvaluated {
              repositories.forEach {
                println("${it.name} resolves to: ${(it as org.gradle.api.artifacts.repositories.MavenArtifactRepository).url} and is ${it}")
              }
          }
          /**
          configurations.all {
              resolutionStrategy.eachDependency {
                  println("? [${requested.group}:${requested.name}:${requested.version}]")
              }
          }
          gradle.projectsEvaluated {
              repositories.forEach { repo ->
                  if (repo is org.gradle.api.artifacts.repositories.MavenArtifactRepository) {
                      println("==== ${repo.name} (${repo.javaClass.simpleName}) ====")
                      // Print common public properties
                      println("  url: ${repo.url}")
                      println("  contentFilter: ${repo.contentFilter}")
                      println("  metadataSources: ${repo.metadataSources}")
                      println("  allowInsecureProtocol: ${repo.isAllowInsecureProtocol}")
                      println("  authentication: ${repo.authentication}")
                      // Print all getter methods via reflection
                      repo.javaClass.methods
                          .filter { it.name.startsWith("get") && it.parameterCount == 0 }
                          .forEach { method ->
                              try {
                                  println("  ${method.name}: ${method.invoke(repo)}")
                              } catch (e: Exception) {
                                  println("  ${method.name}: <error: ${e.message}>")
                              }
                          }
                      println()
                  }
              }
          }

          gradle.projectsEvaluated {
              repositories.forEach { repo ->
                  if (repo is MavenArtifactRepository) {
                      println("==== ${repo.name} (${repo.javaClass.simpleName}) ====")
                      println("  url: ${repo.url}")
                      println("  metadataSources: ${repo.metadataSources}")
                      println("  allowInsecureProtocol: ${repo.isAllowInsecureProtocol}")
                      println("  authentication: ${repo.authentication}")

                      // Optional: print all public getter methods and their values
                      repo.javaClass.methods
                          .filter { it.name.startsWith("get") && it.parameterCount == 0}
                          .forEach { method ->
                              try {
                                  println("  ${method.name}: ${method.invoke(repo)}")
                              } catch (e: Exception) {
                                  println("  ${method.name}: <error: ${e.message}>")
                              }
                          }
                      println()
                  }
              }
          }
          */
          val ignoreProperties = setOf(
              "getRootComponentProperty",
              "getClass",
              "getAuthentication",
              "getAuthenticationSchemes",
              "getConfiguredAuthentication",
              "getCredentials"
          )

          gradle.projectsEvaluated {
              repositories.forEach { repo ->
                  println("==== ${repo.name} (${repo.javaClass.simpleName}) ====")
                  repo.javaClass.methods
                      .filter {
                          it.name.startsWith("get") &&
                          it.parameterCount == 0 &&
                          it.name !in ignoreProperties
                      }
                      .forEach { method ->
                          try {
                              println("  ${method.name}: ${method.invoke(repo)}")
                          } catch (e: Throwable) {
                            throw RuntimeException("Error on calling ${method.name}:", e)
                          }
                      }
                  println()
              }
          }
          """;
      return debugRepositories;
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
    private static List<String> collectAllModulePaths(Project pom, String parentPath, File baseDir) {
      if (pom.modules == null || pom.modules.module == null || pom.modules.module.isEmpty()) {
        return List.of();
      }
      return StreamEx.of(pom.modules.module).flatMap(moduleName -> {
        String fullPath = parentPath.isEmpty() ? moduleName : parentPath + ":" + moduleName;
        File moduleDir = baseDir.toPath().resolve(moduleName).toFile();
        Project childPom = PomLoader.loadPom(null, null, moduleDir, pom.context);
        List<String> nested = childPom != null ? collectAllModulePaths(childPom, fullPath, moduleDir) : List.of();
        return StreamEx.of(fullPath).append(nested);
      }).toList();
    }

    // TODO refactor to use pom structure
    private static void generateForModulesRecursively(Path baseDir, Project pom, ToGradle cli, Projects effectivePom, GradleModules gradleModules,
        boolean isRoot) {
      log.info("Generating build.gradle.kts for {}", pom.artifactId);

      String gradleKts = generate(pom, effectivePom, gradleModules, cli);

      try {
        Files.writeString(baseDir.resolve("build.gradle.kts"), gradleKts);
      } catch (IOException e) {
        throw new RuntimeException("Failed to write build.gradle.kts for " + pom.artifactId, e);
      }

      if (pom.modules != null && pom.modules.module != null) {
        for (String moduleName : pom.modules.module) {
          Path moduleDir = baseDir.resolve(moduleName);
          if (Files.exists(moduleDir)) {
            Project modulePom = PomLoader.loadPom(null, null, moduleDir.toFile(), pom.context);
            generateForModulesRecursively(moduleDir, modulePom, cli, effectivePom, gradleModules, false);
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

    public static String generate(Project pom, Projects effectivePom, GradleModules gradleModules, ToGradle cli) {

      // 1. Emit Maven property variables for all referenced properties in dependency
      // versions
      // String gradlePropertyBlock = emitReferencedMavenPropertiesKts(pom);
      // NOTE disabled because the gradle doesn't use unresolved properties anymore
      String gradlePropertyBlock = "";

      // 2. Collect Gradle plugins and config snippets as before
      StringBuilder pluginConfigSnippets = new StringBuilder();
      Map<String, String> pluginsMap = collectGradlePluginsAndConfigs(pom, pluginConfigSnippets, cli);

      // 3. Emit dependencies and any version variables
      DependencyEmitResult depResult = emitDeps(pom, effectivePom, gradleModules, cli);

      // 4. Standard project attributes
      String group = resolveProperties(pom.groupId, pom);
      String version = resolveProperties(pom.version, pom);
      String javaVersion = extractJavaVersionFromEffectivePom(pom, cli);

      if (depResult.hasLombok)
        pluginsMap.put("io.freefair.lombok", "8.6");

      String pluginsBlock = buildPluginsBlockKts(pluginsMap);
      List<String> excludes = pom.effectivePomOrThis().compilerPlugin().configuration.excludes;
      String excludesBlock = excludes != null && excludes.size() > 0 ? """
          tasks.withType<JavaCompile> {
            %s
          }
          """.formatted(prefixLines(generateExcludesBlock(excludes), "", "  ")) : "";
      return String.format("""
          %s
          %s

          plugins {
              id("java")
              id("java-library") //allows api dependencies
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

          %s

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

          //Force declared dependencies to be used. Gradle would use the maximum version and that is not compatible with maven
          val forcedDeps = configurations
            .flatMap { it.dependencies }
            .filter { it.version != null }
            .map { "${it.group}:${it.name}:${it.version}" }
            .distinct()
          configurations.all {
            resolutionStrategy {
              force(forcedDeps)
            }
          }

          %s
          %s
          """, gradlePropertyBlock, depResult.variableBlock, pluginsBlock, javaVersion, javaVersion, group, version, "", // do
          // not
          // render
          // local
          // repositories
          // -
          // are
          // configured
          // in
          // settings
          // repositories("")
          depResult.dependencyBlock, pluginConfigSnippets, excludesBlock);
    }

    public static String generateExcludesBlock(List<String> excludes) {
      if (excludes == null || excludes.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder();
      for (String exclude : excludes) {
        sb.append("    excludes.add(\"").append(exclude.replace("\"", "\\\"")).append("\")\n");
      }
      return sb.toString();
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
          .append(includeDependencyManagement && pom.dependencyManagement != null && pom.dependencyManagement.dependencies != null
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
              ? StreamEx.of(pom.profiles.profile).flatMap(profile -> profile.dependencies != null && profile.dependencies.dependency != null
                  ? StreamEx.of(profile.dependencies.dependency).flatMap(d -> {
                    if (d.version == null)
                      return StreamEx.empty();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(d.version);
                    List<String> keys = new ArrayList<>();
                    while (m.find())
                      keys.add(m.group(1));
                    return StreamEx.of(keys);
                  })
                  : StreamEx.empty())
              : StreamEx.empty())
          .distinct().toSet();

      Map<String, String> allProps = includeOwnProperties && pom.properties != null && pom.properties.any != null ? pom.properties.any
          : java.util.Collections.emptyMap();

      if (includeParentProperties && pom.parentPom != null) {
        allProps = new java.util.LinkedHashMap<>(allProps);
        Map<String, String> parentProps = pom.parentPom.properties != null && pom.parentPom.properties.any != null ? pom.parentPom.properties.any
            : java.util.Collections.emptyMap();
        allProps.putAll(parentProps); // child overrides parent
      }

      var finalProps = allProps;
      return StreamEx.of(referencedKeys).map(key -> {
        String value = finalProps.get(key);
        if (value == null)
          return null;
        if (value.startsWith("$")) {
          log.warn("Referenced property '%s' in POM %s has a value that starts with '$': '%s'. "
              + "This is not supported in Gradle Kotlin DSL.".formatted(key, pom.id(), value));
          // If the value is a property reference, we can skip it
          throw new RuntimeException("Referenced property '%s' in POM %s has a value that is another property reference: '%s'. "
              + "This is not supported in Gradle Kotlin DSL.".formatted(key, pom.id(), value));
        }
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
          .map(e -> e.getValue() == null ? "    id(\"" + e.getKey() + "\")" : "    id(\"" + e.getKey() + "\") version \"" + e.getValue() + "\"")
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

    static class GradleModules {
      Map<String, String> modules = new TreeMap<>();

      public String findByMavenGroupAndArtifact(String resolvedGroupId, String resolvedArtifactId) {
        String ga = resolvedGroupId + ":" + resolvedArtifactId;
        return modules.get(ga);
      }

      public void addGradleModule(String ga, String gradlePath) {
        if (modules.containsKey(ga)) {
          log.warn("Gradle module {} already exists for {}. Overwriting with {}", ga, modules.get(ga), gradlePath);
          throw new RuntimeException("Gradle module " + ga + " already exists for " + modules.get(ga) + ". Overwriting with " + gradlePath);
        }
        modules.put(ga, gradlePath);
      }
    }

    private static DependencyEmitResult emitDeps(Project pom, Projects effectivePom, GradleModules gradleModules, ToGradle cli) {
      pom = pom.effectivePomOrThis();

      if (pom.dependencies == null || pom.dependencies.dependency == null) {
        return new DependencyEmitResult("", "", false);
      }
      pom.dependencies.dependency.sort(
          java.util.Comparator.comparing((Dependency d) -> toGradleConf(cli, d, d.scope, cli.useApiDependencies())).thenComparing(d -> d.gaFull()));

      StringBuilder varDecls = new StringBuilder();
      StringBuilder deps = new StringBuilder();
      boolean hasLombok = false;

      for (Dependency dep : pom.dependencies.dependency) {
        String resolvedGroupId = resolveGroupId(dep, pom);
        String resolvedArtifactId = resolveProperties(dep.artifactId, pom);
        String version = dep.version;
        String conf = toGradleConf(cli, dep, dep.scope, cli.useApiDependencies());
        boolean isTestJar = "test-jar".equals(dep.type) || "tests".equals(dep.classifier);
        boolean isNotTestScope = dep.scope == null || !"test".equals(dep.scope);

        if (isTestJar && isNotTestScope) {
          log.warn(
              "Dependency on {}:{} with classifier=test/type=test-jar is missing <scope>test</scope>-Gradle may not handle this as a test dependency.",
              dep.groupId, dep.artifactId);
        }
        String gradlePath = gradleModules.findByMavenGroupAndArtifact(resolvedGroupId, resolvedArtifactId);
        if (gradlePath != null) {
          // Handle test-jar module dependency
          if ("test".equals(conf) || "testImplementation".equals(conf)) {
            if (isTestJar) {
              deps.append(String.format("    testImplementation(project(path = \":%s\", configuration = \"testArtifacts\"))\n", gradlePath));
              if (GRADLE_COMPILE_ONLY_PLUS_TEST_IMPLEMENTATION.equals(conf)) {
                deps.append(String.format("    compileOnly(project(path = \":%s\", configuration = \"testArtifacts\"))\n", gradlePath));
              }
              continue;
            }
          }
          // Normal module dependency
          if (GRADLE_COMPILE_ONLY_PLUS_TEST_IMPLEMENTATION.equals(conf)) {
            deps.append(String.format("    compileOnly(project(\":%s\"))\n", gradlePath));
            deps.append(String.format("    testImplementation(project(\":%s\"))\n", gradlePath));
          } else {
            deps.append(String.format("    %s(project(\":%s\"))\n", conf, gradlePath));
          }
          var searchAgain = gradleModules.findByMavenGroupAndArtifact(resolvedGroupId, resolvedArtifactId);
          continue;
        }
        // ---- Lombok special case ----
        if ("org.projectlombok".equals(resolvedGroupId)) {
          if (!"lombok".equals(resolvedArtifactId)) {
            log.warn("Strange lombok dependency {} in {}. Should have lombok artifactId. Check it manually.", dep, pom.idAndPath());
            if ("lombok-maven-plugin".equals(resolvedArtifactId)) {
              version = "1.18.22";
            }
          }
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
          continue;
        }
        if (handleAnnotationProcessorDependency(dep, resolvedGroupId, resolvedArtifactId, version, pom, effectivePom, deps)) {
          continue;
        }
        // -----------------------------

        // 1. Detect version
        String resolvedVersion = resolveVersion(dep, pom, cli.useEffectivePom, effectivePom);
        String extractedVersion = extractVersionFromProjects(resolvedGroupId, resolvedArtifactId, effectivePom);
        String finalVersion = replaceMavenPropsWithKotlinVars(resolvedVersion != null && !resolvedVersion.isBlank() ? resolvedVersion
            : (extractedVersion != null && !extractedVersion.isBlank() ? extractedVersion : "unknown"));

        if (finalVersion.equals("unknown") || finalVersion.startsWith("$") || (finalVersion.startsWith("${") && finalVersion.endsWith("}"))) {
          if (cli.ignoreUnknownVersions) {
            log.warn("Dependency {}:{} has unknown version, skipping", resolvedGroupId, resolvedArtifactId);
            continue;
          }
          throw new RuntimeException("Failed to resolve version for dependency: %s:%s:%s on %s".formatted(resolvedGroupId, resolvedArtifactId,
              finalVersion, pom.idAndPath()));
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
          if (GRADLE_COMPILE_ONLY_PLUS_TEST_IMPLEMENTATION.equals(conf)) {
            deps.append(String.format("    compileOnly(\"%s\") {\n", depCoordinate));
            for (Exclusion excl : dep.exclusions.exclusion) {
              deps.append(String.format("        exclude(group = \"%s\", module = \"%s\")\n", excl.groupId, excl.artifactId));
            }
            deps.append("    }\n");
            deps.append(String.format("    testImplementation(\"%s\") {\n", depCoordinate));
            for (Exclusion excl : dep.exclusions.exclusion) {
              deps.append(String.format("        exclude(group = \"%s\", module = \"%s\")\n", excl.groupId, excl.artifactId));
            }
            deps.append("    }\n");
          } else {
            deps.append(String.format("    %s(\"%s\") {\n", conf, depCoordinate));
            for (Exclusion excl : dep.exclusions.exclusion) {
              deps.append(String.format("        exclude(group = \"%s\", module = \"%s\")\n", excl.groupId, excl.artifactId));
            }
            deps.append("    }\n");
          }
        } else {
          if (GRADLE_COMPILE_ONLY_PLUS_TEST_IMPLEMENTATION.equals(conf)) {
            deps.append(String.format("    compileOnly(\"%s\")\n", depCoordinate));
            deps.append(String.format("    testImplementation(\"%s\")\n", depCoordinate));
          } else {
            deps.append(String.format("    %s(\"%s\")\n", conf, depCoordinate));
          }
        }
      }

      return new DependencyEmitResult(varDecls.toString(), deps.toString(), hasLombok);
    }

    private static boolean handleAnnotationProcessorDependency(Dependency dep, String resolvedGroupId, String resolvedArtifactId, String version,
        Project pom, Projects effectivePom, StringBuilder deps) {

      // Example known processors map or set
      Map<String, String> annotationProcessors = Map.ofEntries(Map.entry("org.projectlombok:lombok", "lombok"), //
          Map.entry("org.mapstruct:mapstruct", "mapstruct"), //
          Map.entry("org.immutables:value", "value"), //
          Map.entry("com.google.auto.service:auto-service", "auto-service"), //
          Map.entry("com.google.auto.value:auto-value", "auto-value"), //
          Map.entry("com.google.auto.value:auto-value-annotations", "auto-value-annotations"), //
          Map.entry("com.google.dagger:dagger-compiler", "dagger-compiler"), //
          Map.entry("com.google.dagger:dagger-producers", "dagger-producers"), //
          Map.entry("com.google.dagger:dagger-android-processor", "dagger-android-processor"), //
          Map.entry("com.google.dagger:dagger-android-support", "dagger-android-support") //
      );

      String ga = resolvedGroupId + ":" + resolvedArtifactId;
      if (!annotationProcessors.containsKey(ga)) {
        return false;
      }

      String depVersion = (version == null || version.isBlank()) ? extractVersionFromProjects(resolvedGroupId, resolvedArtifactId, effectivePom)
          : version;

      if (depVersion == null || depVersion.isBlank())
        depVersion = "unknown";

      deps.append("    compileOnly(\"" + ga + ":" + depVersion + "\")\n");
      deps.append("    annotationProcessor(\"" + ga + ":" + depVersion + "\")\n");
      deps.append("    testCompileOnly(\"" + ga + ":" + depVersion + "\")\n");
      deps.append("    testAnnotationProcessor(\"" + ga + ":" + depVersion + "\")\n");

      return true;
    }

    private static String createVersionPropertyName(Dependency dep, String resolvedGroupId, String resolvedArtifactId) {
      String scopePart = (dep.scope == null || dep.scope.isBlank()) ? "" : "_" + dep.scope.replaceAll("[^a-zA-Z0-9]", "_");
      String typePart = (dep.type == null || dep.type.isBlank()) ? "" : "_" + dep.type.replaceAll("[^a-zA-Z0-9]", "_");
      String classifierPart = (dep.classifier == null || dep.classifier.isBlank()) ? "" : "_" + dep.classifier.replaceAll("[^a-zA-Z0-9]", "_");
      String varName = "ver_" + resolvedGroupId.replaceAll("[^a-zA-Z0-9]", "_") + "_" + resolvedArtifactId.replaceAll("[^a-zA-Z0-9]", "_") + scopePart
          + typePart + classifierPart;
      return varName;
    }

    private static GradleModules collectModuleArtifactIdToGradlePath(Project rootPom, File rootDir, boolean ignoreUnknown, Projects effectivePom) {
      GradleModules gradleModules = new GradleModules();
      collectModulesRecursivelyWithPaths(rootPom, rootPom, rootDir, "", gradleModules);
      return gradleModules;
    }

    private static void collectModulesRecursivelyWithPaths(Project root, Project pom, File baseDir, String parentGradlePath,
        GradleModules gradleModules) {
      if (pom == null || pom.modules == null || pom.modules.module == null)
        return;

      for (String moduleName : pom.modules.module) {
        File moduleDir = new File(baseDir, moduleName);
        Project childPom = PomLoader.loadPom(root, pom, moduleDir, root.context);
        if (childPom != null && childPom.artifactId != null) {
          String fullPath = parentGradlePath.isEmpty() ? moduleName : parentGradlePath + ":" + moduleName;
          gradleModules.addGradleModule(childPom.ga(), fullPath);
          collectModulesRecursivelyWithPaths(root, childPom, moduleDir, fullPath, gradleModules);
        }
      }
    }

    private static Map<String, String> collectGradlePluginsAndConfigs(Project pom, StringBuilder pluginConfigSnippets, xmvn.ToGradle cli) {
      Map<String, String> pluginsMap = new LinkedHashMap<>();
      Set<String> visited = new HashSet<>(); // to avoid duplicates when traversing parents
      collectGradlePluginsAndConfigsRecursive(pom, pluginConfigSnippets, pluginsMap, visited);
      return pluginsMap;
    }

    private static void collectGradlePluginsAndConfigsRecursive(Project pom, StringBuilder pluginConfigSnippets, Map<String, String> pluginsMap,
        Set<String> visited) {
      if (pom == null)
        return;
      // Use effectivePom if configured
      ToGradle cli = (xmvn.ToGradle) pom.context.cli;
      Project epPom = pom.effectivePom();
      if (epPom != null) {
        collectPluginsFromPom(epPom, pluginConfigSnippets, pluginsMap, visited);
      } else {
        collectPluginsFromPom(pom, pluginConfigSnippets, pluginsMap, visited);

        // recurse to parent raw pom
        collectGradlePluginsAndConfigsRecursive(pom.parentDirPom, pluginConfigSnippets, pluginsMap, visited);
      }
    }

    private static void collectPluginsFromPom(Project pom, StringBuilder pluginConfigSnippets, Map<String, String> pluginsMap, Set<String> visited) {
      if (pom.build == null || pom.build.plugins == null || pom.build.plugins.plugin == null)
        return;

      for (Plugin plugin : pom.build.plugins.plugin) {
        String pluginUniqueKey = plugin.getEffectiveGroupId() + ":" + plugin.artifactId;
        if (!visited.add(pluginUniqueKey))
          continue;

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
              pluginConfigSnippets.append("""
                  // -------------------------------------------------------------------
                  // Plugin %s ... triggerd by maven %s%s
                  %s
                  // Plugin %s end.

                  """.formatted(conv.name, pluginKey, conv.gradlePluginId != null ? " and using gradle " + conv.gradlePluginId : "", config.trim(),
                  conv.name));
            }
          }
        }
      }
    }

    private static List<PluginExecutionContext> getPluginExecutions(Plugin plugin, Project pom) {
      Project epPom = pom.effectivePom();
      if (epPom != null) {
        return collectPluginExecutionsFromEffectivePom(plugin, epPom);
      }
      List<PluginExecutionContext> result = new ArrayList<>();
      Set<String> seenExecKeys = new HashSet<>();
      collectPluginExecutionsRecursive(plugin, pom, result, seenExecKeys);
      return result;
    }

    private static List<PluginExecutionContext> collectPluginExecutionsFromEffectivePom(Plugin plugin, Project epPom) {
      List<PluginExecutionContext> result = new ArrayList<>();
      if (epPom == null || epPom.build == null || epPom.build.plugins == null || epPom.build.plugins.plugin == null) {
        return result;
      }
      for (Plugin p : epPom.build.plugins.plugin) {
        if (plugin.getEffectiveGroupId().equals(p.getEffectiveGroupId()) && plugin.artifactId.equals(p.artifactId)) {
          if (p.executions != null && p.executions.execution != null) {
            for (Execution exec : p.executions.execution) {
              if (exec.goals != null && exec.goals.goal != null) {
                for (String goal : exec.goals.goal) {
                  PluginConfiguration config = exec.configuration != null ? exec.configuration : p.configuration;
                  result.add(new PluginExecutionContext(p, goal, config));
                }
              }
            }
          }
          if ((p.executions == null || p.executions.execution == null || p.executions.execution.isEmpty())) {
            result.add(new PluginExecutionContext(p, "default", p.configuration));
          }
        }
      }
      return result;
    }

    private static void collectPluginExecutionsRecursive(Plugin plugin, Project pom, List<PluginExecutionContext> result, Set<String> seenExecKeys) {
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
                  String execId = exec.id != null ? exec.id : "";
                  String execKey = execId + ":" + goal;
                  if (!seenExecKeys.add(execKey))
                    continue;
                  PluginConfiguration config = exec.configuration != null ? exec.configuration : p.configuration;
                  result.add(new PluginExecutionContext(p, goal, config));
                }
              }
            }
          }
          if ((p.executions == null || p.executions.execution == null || p.executions.execution.isEmpty()) && seenExecKeys.isEmpty()) {
            result.add(new PluginExecutionContext(p, "default", p.configuration));
          }
        }
      }
      collectPluginExecutionsRecursive(plugin, pom.parentPom, result, seenExecKeys);
    }

    private static String extractJavaVersionFromEffectivePom(Project pom, ToGradle cli) {
      String rawVersion = null;

      Plugin plugin = pom.compilerPlugin();
      if (plugin != null) {
        try {
          String source = plugin.configuration.source;
          String target = plugin.configuration.target;
          if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
            rawVersion = source.equals(target) ? source : source; // prefer source if different
          } else if (source != null && !source.isBlank()) {
            rawVersion = source;
          } else if (target != null && !target.isBlank()) {
            rawVersion = target;
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to read compiler plugin configuration", e);
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
          throw new RuntimeException("Java version property not found in POM " + (pom.artifactId != null ? pom.artifactId : ""));
      }

      String resolvedVersion = resolveProperties(rawVersion, pom);

      if (resolvedVersion == null || resolvedVersion.isBlank() || resolvedVersion.contains("${")) {
        throw new RuntimeException("Failed to resolve Java version property fully in POM %s: [%s] => [%s]"
            .formatted(pom.artifactId != null ? pom.artifactId : "", rawVersion, resolvedVersion));
      }

      return resolvedVersion;
    }

    public static String resolveVersion(Dependency d, Project pom, boolean useEffectivePom, Projects effectivePom) {
      if (d.version != null && !d.version.isBlank() && !d.version.contains("$")) {
        return d.version;
      }
      Project epPom = pom.effectivePom();
      if (epPom != null) {
        // String version = resolveProperties(d.version, epPom);
        if (epPom.dependencies != null && epPom.dependencies.dependency != null) {
          for (Dependency ed : epPom.dependencies.dependency) {
            if (d.groupId.equals(ed.groupId) && d.artifactId.equals(ed.artifactId) && ed.version != null) {
              return ed.version;
            }
          }
        }
        return null;
      } else {
        return extractVersion(d.groupId, d.artifactId, pom);
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

    public static class DependencyEmitResult {
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

    private static final String GRADLE_COMPILE_ONLY_PLUS_TEST_IMPLEMENTATION = "compileOnly+testImplementation";

    private static String toGradleConf(ToGradle cli, Dependency dep, String scope, boolean useApiDependencies) {
      if (scope == null || scope.isBlank() || "compile".equals(scope) || "compile+runtime".equals(scope)) {
        return useApiDependencies ? "api" : "implementation";
      }
      if ("provided".equals(scope) || "providedCompile".equals(scope)) {
        if (cli.forceProvidedForTests.contains(dep.gaFull())) {
          return GRADLE_COMPILE_ONLY_PLUS_TEST_IMPLEMENTATION;
        }
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
      log.warn("Unknown Maven scope '{}', defaulting to 'implementation'", scope);
      return "implementation";
    }
  }

  private static final String jbangVersion = "0.128.7";
  private static final String jbangAndXjcPlugin = """
      val jbangVersion = "%s"
      val jaxbVersion = "%s"
      val outputPackage = "%s"
      val schemaDirectory = "%s"

      val extraDeps = listOf(
          "org.glassfish.jaxb:jaxb-xjc:"+jaxbVersion,
          "org.glassfish.jaxb:jaxb-runtime:"+jaxbVersion,
          "org.jvnet.jaxb:jaxb-plugins:4.0.0",
          "org.jvnet.jaxb:jaxb-plugin-annotate:4.0.0",
          "org.slf4j:slf4j-simple:1.7.36"
      )

      // providers, do not resolve at configuration time
      val outputDir = layout.buildDirectory.dir("generated-sources-jaxb")
      val toolsDir = layout.buildDirectory.dir("tools")

      // wire generated sources
      sourceSets["main"].java.srcDir(outputDir)

      // compile depends on generation
      tasks.named("compileJava") { dependsOn("runJbangXjcFull") }

      // download and unpack task
      tasks.register("downloadAndUnpackJbang") {
          val jbangInstallDir = toolsDir.map { it.dir("jbang-$jbangVersion") }
          outputs.dir(jbangInstallDir)

          doLast {
              performDownloadAndUnpackJBang(
                  jbangVersion = jbangVersion,
                  toolsRoot = toolsDir.get().asFile,
                  jbangZipUrl = "https://github.com/jbangdev/jbang/releases/download/v$jbangVersion/jbang-$jbangVersion.zip"
              )
          }
      }

      // xjc task with incremental inputs and outputs
      tasks.register("runJbangXjcFull") {
          group = "codegen"
          description = "Run xjc via jbang.jar, print all outputs colorfully, and fail-fast on errors."
          dependsOn("downloadAndUnpackJbang")

          val schemas = fileTree(schemaDirectory) {
              include("**/*.xsd", "**/*.wsdl", "**/*.dtd", "**/*.xjb")
          }
          inputs.files(schemas)
          inputs.property("jaxbVersion", jaxbVersion)
          inputs.property("outputPackage", outputPackage)
          inputs.property("extraDeps", extraDeps)
          outputs.dir(outputDir)
          onlyIf { schemas.files.isNotEmpty() }

          doLast {
              performRunJbangXjcFull(
                  jbangJar = `java.io`.File(toolsDir.get().asFile, "jbang-$jbangVersion/bin/jbang.jar"),
                  jaxbVersion = jaxbVersion,
                  outputDir = outputDir.get().asFile,
                  outputPackage = outputPackage,
                  schemaDirectory = file(schemaDirectory),
                  extraDeps = extraDeps,
                  javaCmd = "java",
                  verbose = true
              )
          }
      }

      // ===================== PURE FUNCTIONS (TOP-DOWN) =====================

      fun performDownloadAndUnpackJBang(jbangVersion: String, toolsRoot: `java.io`.File, jbangZipUrl: String): `java.io`.File {
          val zipFile = `java.io`.File(toolsRoot, "jbang-$jbangVersion.zip")
          val installDir = `java.io`.File(toolsRoot, "jbang-$jbangVersion")
          val jbangJar = `java.io`.File(installDir, "bin/jbang.jar")

          if (!zipFile.exists()) downloadTo(jbangZipUrl, zipFile)
          if (!jbangJar.exists()) unzip(zipFile, toolsRoot)
          check(jbangJar.exists()) { "jbang.jar not found at $jbangJar after unzip" }
          return jbangJar
      }

      fun unzip(zipFile: `java.io`.File, destDir: `java.io`.File) {
          ensureDir(destDir)
          (`java.util.zip`.ZipInputStream(zipFile.inputStream())).use { zip ->
              var e = zip.nextEntry
              while (e != null) {
                  val outPath = `java.io`.File(destDir, e.name)
                  if (e.isDirectory) {
                      ensureDir(outPath)
                  } else {
                      ensureDir(outPath.parentFile)
                      outPath.outputStream().use { out -> zip.copyTo(out) }
                  }
                  e = zip.nextEntry
              }
          }
      }

      fun downloadTo(url: String, dest: `java.io`.File) {
          ensureDir(dest.parentFile)
          (`java.net`.URL(url)).openStream().use { input ->
              dest.outputStream().use { out -> input.copyTo(out) }
          }
      }

      fun ensureDir(dir: `java.io`.File): `java.io`.File {
          if (!dir.exists()) check(dir.mkdirs()) { "failed to create: ${dir.absolutePath}" }
          return dir
      }

      fun runProcess(args: List<String>): Triple<Int, String, String> {
          val pb = (`java.lang`.ProcessBuilder(args))
          pb.redirectErrorStream(false)
          val proc = pb.start()
          val out = `java.io`.ByteArrayOutputStream()
          val err = `java.io`.ByteArrayOutputStream()
          val tOut = kotlin.concurrent.thread(start = true) { proc.inputStream.copyTo(out) }
          val tErr = kotlin.concurrent.thread(start = true) { proc.errorStream.copyTo(err) }
          val exit = proc.waitFor()
          tOut.join(); tErr.join()
          return Triple(exit, out.toString(Charsets.UTF_8).trim(), err.toString(Charsets.UTF_8).trim())
      }

      fun performRunJbangXjcFull(
          jbangJar: `java.io`.File,
          jaxbVersion: String,
          outputDir: `java.io`.File,
          outputPackage: String,
          schemaDirectory: `java.io`.File,
          extraDeps: List<String>,
          javaCmd: String = "java",
          verbose: Boolean = true,
          workingDir: `java.io`.File? = null
      ) {
          val ansiReset = "\\u001B[0m"
          val ansiRed = "\\u001B[31m"
          val ansiGreen = "\\u001B[32m"
          val ansiCyan = "\\u001B[36m"
          val ansiYellow = "\\u001B[33m"

          fun quoteForDisplay(s: String): String =
              if (s.any { it.isWhitespace() || it == '"' || it == '\\'' }) "\\"${s.replace("\\"", "\\\\\\"")}\\"" else s

          ensureDir(outputDir)
          check(outputPackage.isNotBlank()) { "outputPackage is required" }
          check(schemaDirectory.exists()) { "schemaDirectory not found: ${schemaDirectory.absolutePath}" }

          val depsCsv = extraDeps.joinToString(",")

          val jbangArgs = listOf(
              "--java", "17",
              if (verbose) "--verbose" else "--quiet",
              "--deps", depsCsv,
              "org.glassfish.jaxb:jaxb-xjc:$jaxbVersion",
              "-extension", "-Xannotate", "-Xinheritance", "-Xcopyable", "-XtoString", "-Xequals", "-XhashCode",
              "-d", outputDir.absolutePath,
              "-p", outputPackage,
              schemaDirectory.absolutePath
          )

          // STEP 1: show the exact command sent to jbang and capture its output + exit
          val cmd1 = listOf(javaCmd, "-classpath", jbangJar.absolutePath, "dev.jbang.Main") + jbangArgs
          val cmd1Display = cmd1.joinToString(" ") { quoteForDisplay(it) }
          println("${ansiCyan}STEP 1: Running jbang (dev.jbang.Main) to prepare XJC...$ansiReset")
          println("${ansiYellow}CMD:$ansiReset $cmd1Display")

          val (exit1, out1, err1) = runProcess(cmd1, workingDir = workingDir)
          val all1 = (out1 + "\\n" + err1).trim()
          if (all1.isNotEmpty()) println("${ansiYellow}JBang output:$ansiReset\\n$all1")
          println("${ansiYellow}JBang exit:$ansiReset $exit1")

          val xjcCmd = parseXjcCmd(out1)
          if (xjcCmd == null) {
              // only hard-fail if we could not extract the XJC command
              throw IllegalStateException("Failed to extract XJC exec command from jbang output.")
          }
          if (exit1 != 0) {
              // proceed anyway (this mirrors your original behavior), but make it visible
              println("${ansiYellow}Warning:$ansiReset jbang returned non-zero ($exit1) but an XJC command was found. Continuing...")
          }

          // STEP 2: run the printed XJC command exactly; show cmd, stdout, stderr, and exit code
          println("${ansiGreen}STEP 2: Running computed XJC command:$ansiReset")
          println("${ansiCyan}$xjcCmd$ansiReset")

          val args2 = antTranslate(xjcCmd)
          val cmd2Display = args2.joinToString(" ") { quoteForDisplay(it) }
          println("${ansiYellow}CMD:$ansiReset $cmd2Display")

          val (exit2, out2, err2) = runProcess(args2, workingDir = workingDir)
          if (out2.isNotEmpty()) println("${ansiGreen}XJC STDOUT:$ansiReset\\n$out2")
          if (err2.isNotEmpty()) println("${ansiRed}XJC STDERR:$ansiReset\\n$err2")
          println("${ansiYellow}XJC exit:$ansiReset $exit2")
          check(exit2 == 0) { "XJC failed (exit $exit2). See output above." }

          println("${ansiGreen}XJC completed successfully!$ansiReset")
      }


      fun parseXjcCmd(stdOut: String): String? =
          stdOut.lineSequence().firstOrNull { it.contains("java") && it.contains("XJCFacade") }?.trim()

      fun antTranslate(cmd: String): List<String> =
          org.apache.tools.ant.types.Commandline.translateCommandline(cmd).toList()

      fun runProcess(args: List<String>, workingDir: `java.io`.File? = null): Triple<Int, String, String> {
          val pb = (`java.lang`.ProcessBuilder(args))
          if (workingDir != null) pb.directory(workingDir)
          pb.redirectErrorStream(false)
          val proc = pb.start()
          val out = `java.io`.ByteArrayOutputStream()
          val err = `java.io`.ByteArrayOutputStream()
          val tOut = kotlin.concurrent.thread(start = true) { proc.inputStream.copyTo(out) }
          val tErr = kotlin.concurrent.thread(start = true) { proc.errorStream.copyTo(err) }
          val exit = proc.waitFor()
          tOut.join(); tErr.join()
          return Triple(exit, out.toString(Charsets.UTF_8).trim(), err.toString(Charsets.UTF_8).trim())
      }

      // ===================== GRADLE CONFIG AFTER FUNCTIONS =====================
      """;
  /*
   * Try1 build.gradle.kts import java.net.URL import java.util.zip.ZipInputStream
   * import java.io.ByteArrayOutputStream
   *
   * plugins { id("java") id("eclipse")
   * id("com.vanniktech.dependency.graph.generator") version "0.8.0"
   * id("project-report") id("com.intershop.gradle.jaxb") version "7.0.2" }
   *
   * val jaxbXjcPlugins by configurations.creating
   *
   * configurations { jaxbXjcPlugins }
   *
   * dependencies { // Add all your required XJC plugin jars here // covers
   * -Xequals, -XhashCode, -XtoString, -Xcopyable, -Xinheritance
   * jaxbXjcPlugins("org.jvnet.jaxb:jaxb-plugins:4.0.0") // covers
   * -Xannotate/annox jaxbXjcPlugins("org.jvnet.jaxb:jaxb-plugin-annotate:4.0.0")
   * } jaxb { javaGen { register("main") { schemas =
   * fileTree("src/main/resources") { include("
   **//*
                                                 * .xsd") } outputDir =
                                                 * layout.buildDirectory.dir("generated-sources/jaxb").get().asFile //args =
                                                 * listOf("-locale", "en", "-extension", "-XtoString", "-Xequals", "-XhashCode",
                                                 * "-Xcopyable", "-Xinheritance", "-Xannotate") //args = listOf("-version")
                                                 * //args = listOf("-extension") packageName = "com.foo.compare.generated" args
                                                 * = listOf("-extension", "-Xannotate", "-Xinheritance", "-Xcopyable",
                                                 * "-XtoString", "-Xequals", "-XhashCode") //options { // xjcClasspath =
                                                 * jaxbXjcPlugins //} } } } afterEvaluate { tasks.matching { it.name ==
                                                 * "jaxbJavaGenMain" }.configureEach { doFirst { // Add the plugin jars to the
                                                 * Ant classpath for the XJC task ant.withGroovyBuilder {
                                                 * "project"("antProject") { "taskdef"( "name" to "xjc", "classname" to
                                                 * "com.sun.tools.xjc.XJCTask", "classpath" to jaxbXjcPlugins.asPath ) } } } } }
                                                 * sourceSets["main"].java.srcDir(layout.buildDirectory.dir(
                                                 * "generated-sources/jaxb"))
                                                 */

  /*
   * Try2 jaxb { // Use a named config, e.g. "main" javaGen { create("main") {
   * //for a single file //schema = file("src/main/resources/your.xsd") schemaDir
   * = file("src/main/resources") // if you have .xjb files bindingDir =
   * file("src/main/resources") generatePackage = "com.foo.compare.generated"
   * outputDir = layout.buildDirectory.dir("generated-sources/jaxb").get().asFile
   * locale = "en" removeOldOutput = true args = listOf("-XtoString", "-Xequals",
   * "-XhashCode", "-Xcopyable", "-Xinheritance", "-Xannotate") // plugin
   * configurations if needed: // plugins = ... } } }
   */
  /*
   * Try3 tasks.register<Jar>("testJar") { archiveClassifier.set("tests")
   * from(sourceSets.test.get().output) } configurations { create("testArtifacts")
   * } artifacts { add("testArtifacts", tasks.named("testJar")) }
   *
   *
   * tasks.register<Exec>("jbangXjc") { group = "codegen" description =
   * "Run JAXB xjc via jbang (JAXB 4.x)"
   *
   * // On Windows with cmder/git-cmd, jbang should be on PATH commandLine =
   * listOf( "jbang.cmd", "--java", "17", "--deps",
   * "org.glassfish.jaxb:jaxb-xjc:4.0.5,org.glassfish.jaxb:jaxb-runtime:4.0.5",
   * "org.glassfish.jaxb:jaxb-xjc:4.0.5", "-d", "target/generated-sources", "-p",
   * "com.example.generated", "src/main/resources/schema.xsd" ) }
   */

  static class PomLoader {
    public static Project loadRootPom(LoadPomOptions cli) throws IOException, InterruptedException, TimeoutException {
      Projects effectivePom = null;
      if (cli.useEffectivePom) {
        Path effPomPath = cli.projectDir.toPath().resolve("target/effective-pom.xml");
        if (!Files.exists(effPomPath) || cli.forceGenerateEffectivePom) {
          log.info("Generating effective-pom.xml");
          generateEffectivePom(cli.projectDir, effPomPath);
        }
        effectivePom = loadEffectivePom(cli.ignoreUnknown, effPomPath);
      }

      ProjectContext context = new ProjectContext(cli, null, effectivePom);

      Project rootPom = loadPom(null, null, context.cli.projectDir, context);
      return rootPom;
    }

    private static void generateEffectivePom(File projectDir, Path effPomPath) throws IOException, InterruptedException, TimeoutException {
      String mavenCmd = isWindows() ? "mvn.cmd" : "mvn";
      int exit;
      try {
        exit = new ProcessExecutor().directory(projectDir).command(mavenCmd, "help:effective-pom", "-Doutput=" + effPomPath)
            .redirectOutput(System.out).redirectError(System.err).exitValues(0) // Only allow zero exit value
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

    private static Projects loadEffectivePom(boolean ignoreUnknown, Path effPomPath) throws IOException {
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
        if (e.getMessage().contains("one known property: \"project\"")) {
          EffectivePom singlePom = xmlMapper.readValue(xml, EffectivePom.class);
          Projects projects = new Projects();
          projects.project.add(singlePom);
          return projects;
        }
        throw e;
      }
    }

    private static final Map<String, Project> pomCache = new HashMap<>();

    public static Project loadPom(Project root, Project parentDirPom, File projectDirOrPomFile, ProjectContext context) {
      boolean ignoreUnknown = context.cli.ignoreUnknown;
      Projects effectivePom = context.effectivePom;
      try {
        File pomFile = projectDirOrPomFile.isDirectory() ? projectDirOrPomFile.toPath().resolve("pom.xml").toFile() : projectDirOrPomFile;

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
        pom.context = root == null ? context.withRoot(pom) : context;
        pom.parentDirPom = parentDirPom;
        pomCache.put(key, pom);

        if (pom.parentGav != null) {
          log.info("Resolving parent POM for {} -> {}", pom.ga(), pom.parentGav.ga());
          var parent = findParentPomFileNoCheck(pom.effectivePomOrThis(), ignoreUnknown);
          if (parent == pom) {
            throw new RuntimeException("Parent POM is self-referential: " + pom.id());
          }
          if (pom.parentPom != null) {
            if (pom.groupId == null) {
              pom.groupId = pom.parentPom.groupId;
            }
          }
          pom.parentPom = parent;
          Gav parentGav = pom.parentGav;
          if (parentGav != null || parent != null) {
            if (parentGav == null || parent == null) {
              throw new RuntimeException("Parent POM mismatch: one is null while the other is not. Parent: " + parentGav + ", ParentPom: " + parent);
            }
            if (!parentGav.groupId.equals(parent.groupId) || !parentGav.artifactId.equals(parent.artifactId)) {
              // TODO remove this - needed for debug
              parent = findParentPomFileNoCheck(pom, ignoreUnknown);
              throw new RuntimeException("""
                  In pom %s mismatch:
                  parentGav %s
                  parent    %s"
                  """.formatted(pom.id(), parentGav.id(), parent.id()));
            }
            if (!parentGav.version.equals(parent.version)) {
              log.warn("Parent POM version mismatch: {} vs {}", parentGav.version, parent.version);
            }
            // if (parent.artifactId == null || parentPom.artifactId == null) {
            // return true; // artifactId can be null in some cases, e.g., parent POMs
            // without artifactId
            // }
            if (!parentGav.artifactId.equals(parent.artifactId)) {
              throw new RuntimeException("Parent POM artifactId mismatch: " + parentGav.artifactId + " vs " + parent.artifactId);
            }
          }
        }
        return pom;
      } catch (IOException e) {
        throw new RuntimeException("Failed to load POM from " + projectDirOrPomFile, e);
      }
    }

    private static Project findParentPomFileNoCheck(Project pom, boolean ignoreUnknown) {
      File projectDir = pom.pomFile.getParentFile();
      String relPath = (pom.parentGav.relativePath == null || pom.parentGav.relativePath.isBlank()) ? "../pom.xml" : pom.parentGav.relativePath;
      relPath = relPath.endsWith("/pom.xml") ? relPath : relPath + "/pom.xml";
      // File parentPomFile = findParentPomFile(pom, pomFile.getParentFile());
      // if (parentPomFile != null && parentPomFile.exists()) {
      // pom.parentPom = loadPom(root, null, parentPomFile, ignoreUnknown,
      // effectivePom);
      // if (pom.parentPom != null) {
      // if (pom.groupId == null) {
      // pom.groupId = pom.parentPom.groupId;
      // }
      // }
      // checkIsSame(pom);
      // }

      try {
        File parentProjectDir = new File(projectDir, relPath).getCanonicalFile();
        String candidateKey = parentProjectDir.getCanonicalPath();
        Project parentPom = pomCache.get(candidateKey);
        if (parentPom != null) {
          if (parentPom.effectivePomOrThis().id().equals(pom.parentGav.id())) {
            return parentPom;
          }
        }

        // parentPom = loadPom(pom.context.root, pom.parentDirPom, projectDir,
        // pom.context);
        parentPom = loadPom(pom.context.root, pom.parentDirPom, parentProjectDir, pom.context);
        if (parentPom != null) {
          if (parentPom.effectivePomOrThis().ga().equals(pom.parentGav.ga())) {
            if (!parentPom.effectivePomOrThis().id().equals(pom.parentGav.id())) {
              if (parentPom.effectivePomOrThis().version.contains("${")) {
                log.info("In {} > Parent POM {} has unresolved version, using effective POM version: {}", pom.ga(),
                    parentPom.effectivePomOrThis().id(), pom.parentGav.id());
              } else {
                log.warn("In {} > Different versions found between parent POM {} and {}", pom.ga(), parentPom.effectivePomOrThis().id(),
                    pom.parentGav.id());
              }
            }
            return parentPom;
          } else {
            log.info("In {} > Inheritable declared parent POM [{}] differs from supra-module directory parent [{}] ... searching in .m2 repo next.",
                pom.ga(), pom.parentGav.ga(), parentPom.effectivePomOrThis().ga());
          }
        }
        // If the parent POM is not found in the expected relative path, we fallback to
        // Maven repo lookup
        String groupPath = pom.parentGav.groupId.replace('.', '/');
        String artifactId = pom.parentGav.artifactId;
        String version = pom.parentGav.version;
        File m2 = new File(System.getProperty("user.home"), ".m2/repository");
        File repoPom = new File(m2, String.format("%s/%s/%s/%s-%s.pom", groupPath, artifactId, version, artifactId, version));
        Project parentPomFromRepo = loadPom(pom.context.root, null, repoPom, pom.context);
        if (parentPomFromRepo != null)
          return parentPomFromRepo;
        throw new RuntimeException("Parent POM not found: tried local [" + candidateKey + "] and Maven repo [" + repoPom + "]");
      } catch (IOException e) {
        throw new RuntimeException("Failed to resolve parent POM path for " + pom.parentGav.artifactId, e);
      }
    }

    private static Project parsePom(File pomFile, boolean ignoreUnknown) {
      try {
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, !ignoreUnknown);
        Project res = xmlMapper.readValue(pomFile, Project.class);
        res.pomFile = pomFile;
        if (res.groupId == null && res.parentGav != null && res.parentGav.groupId != null) {
          res.groupId = res.parentGav.groupId;
        }
        if (res.version == null && res.parentGav != null && res.parentGav.version != null) {
          res.version = res.parentGav.version;
        }
        return res;
      } catch (IOException e) {
        throw new RuntimeException("Failed to parse POM file: " + pomFile.getAbsolutePath(), e);
      }
    }
  }
}
