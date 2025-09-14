# xmvn

A pragmatic tool for converting large Maven multi-module projects to Gradle (Kotlin DSL). Designed for reproducibility and deterministic builds for large JVM codebases.

## Features

* Converts Maven modules and dependencies to build.gradle.kts
* Preserves dependency versions exactly (uses resolutionStrategy.force)
* Converts standard plugins (compiler, checkstyle, antlr, jaxb, jar, etc)
* Detects annotation processors (lombok, immutables, etc)
* Handles plugin configs and dependency exclusions
* Fails fast on unmapped plugins or configs
* Windows/cmd compatible by default

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

## Install

`jbang app install --force https://github.com/raisercostin/scripts/blob/main/xmvn.java`

To run without install
`jbang https://github.com/raisercostin/scripts/blob/main/xmvn.java <params>`

## Usage

```
xmvn

Usage: xmvn [-hV] [--debug-repositories] [--force-generate-effective-pom]
                  [--ignore-unknown] [--ignore-unknown-java-version]
                  [--ignore-unknown-versions] [--inline-versions]
                  [--maven-compatible] [--use-api-dependencies]
                  [--use-effective-pom] [--use-implementation-dependencies]
                  [--use-pom-inheritance]
                  [--force-provided-for-tests=<forceProvidedForTests>]
                  <projectDir>

      <projectDir>           Directory containing pom.xml or root pom.xml
      --debug-repositories   Debug info on repositories
      --force-generate-effective-pom
                             Force regeneration of effective-pom.xml even if it
                               exists
      --force-provided-for-tests=<forceProvidedForTests>
                             Force compileOnly+testImplementation for the maven
                               scope=provided libraries [:group:artifact1:,:
                               group2:artifact2:]
                               Default: :org.apache.maven:maven-compat:
  -h, --help                 Show this help message and exit.
      --ignore-unknown       Ignore unknown XML fields
      --ignore-unknown-java-version
                             Ignore unknown Java version in pom.xml (useful in
                               debug)
      --ignore-unknown-versions
                             Ignore unknown dependencies versions (useful in
                               debug)
      --inline-versions      Inline dependency versions instead of using val
                               variables
      --maven-compatible     Generate settings.gradle.kts compatible with Maven:
                              - generate inside target/gradle
      --use-api-dependencies Use api dependencies instead of implementation.
                               Dependencies appearing in the api configurations
                               will be transitively exposed to consumers of the
                               library, and as such will appear on the compile
                               classpath of consumers. This is the default
                               since maven offers only this.
                               Default: true
      --use-effective-pom    Use mvn help:effective-pom
      --use-implementation-dependencies
                             Use implementation dependencies instead of api.
                               Use this if you want to compile only against
                               explicit dependencies.
      --use-pom-inheritance  Use recursive pom.xml parent inheritance
  -V, --version              Print version information and exit.
```

### Simple use

`xmvn .`


### Advanced usage

The maven test dependencies are not transitively passed when dependency is on test-jars. So these libraries (specific to your build) you will expose as compileOnly too.

`xmvn --force-provided-for-tests=:org.apache.maven:maven-compat:,:org.infinispan:infinispan-core:,:com.oracle:ojdbc8:,:org.gwtproject:gwt-user:,:org.apache.spark:spark-sql-api_2.12:,:org.apache.spark:spark-catalyst_2.12:,:org.apache.spark:spark-sql_2.12:,`

## TODO

- self-check that generates same artifacts as mvn - same content and size but faster
- cache runs of gradle build. do not ovewrite if is identical
- add a front build that behinds generates gradle build files and runs gradle build
- add a build to eclipse standard projects (not dependent on gradle or maven or other natures) but properly configures dependencies
  between submodules and generates eclipse project files
- More plugin mappings (site, custom, reporting)

## License

Apache 2.0
