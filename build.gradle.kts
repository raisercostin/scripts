val ver_javax_activation_activation_provided = "1.1"
val ver_org_apache_hadoop_hadoop_common_provided = "3.3.4"
val ver_commons_io_commons_io_compile = "2.16.0"
val ver_org_apache_commons_commons_lang3_compile = "3.14.0"
val ver_com_google_guava_guava_compile = "33.2.1-jre"
val ver_jakarta_xml_bind_jakarta_xml_bind_api_compile = "4.0.1"
val ver_org_jvnet_jaxb_jaxb_plugins_runtime_compile = "4.0.0"
val ver_org_slf4j_slf4j_api_compile = "1.7.36"
val ver_org_hamcrest_hamcrest_test = "2.2"
val ver_org_hamcrest_hamcrest_library_test = "2.2"
val ver_junit_junit_test = "4.13.2"
val ver_org_mockito_mockito_all_test = "1.10.8"
val ver_com_oracle_ojdbc8_test = "12.2.0.1"


plugins {
    id("java")
    id("eclipse")
    id("com.vanniktech.dependency.graph.generator") version "0.8.0"
    id("project-report")
    id("com.intershop.gradle.jaxb") version "7.0.2"
    id("dev.jbang") version "0.3.0"
}
java {
    sourceCompatibility = JavaVersion.toVersion("17")
    targetCompatibility = JavaVersion.toVersion("17")
}

group = "foo"
version = "1.2.3"
layout.buildDirectory.set(file("$projectDir/target/gradle"))

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
    compileOnly("javax.activation:activation:$ver_javax_activation_activation_provided")
    compileOnly("org.apache.hadoop:hadoop-common:$ver_org_apache_hadoop_hadoop_common_provided") {
        exclude(group = "org.mortbay.jetty", module = "jetty")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "log4j", module = "log4j")
        exclude(group = "org.codehaus.jackson", module = "jackson-mapper-asl")
    }
    compileOnly("com.fasterxml.jackson.module:jackson-module-scala_2.12:$ver_com_fasterxml_jackson_module_jackson_module_scala_2_12_provided")
    implementation("commons-io:commons-io:$ver_commons_io_commons_io_compile")
    implementation("org.apache.commons:commons-lang3:$ver_org_apache_commons_commons_lang3_compile")
    implementation("com.google.guava:guava:$ver_com_google_guava_guava_compile")
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:$ver_jakarta_xml_bind_jakarta_xml_bind_api_compile")
    implementation("org.jvnet.jaxb:jaxb-plugins-runtime:$ver_org_jvnet_jaxb_jaxb_plugins_runtime_compile")
    implementation("org.slf4j:slf4j-api:$ver_org_slf4j_slf4j_api_compile")
    implementation("xbrlcore:xbrlcore-new-patched:$ver_xbrlcore_xbrlcore_new_patched_compile")
    testImplementation("org.hamcrest:hamcrest:$ver_org_hamcrest_hamcrest_test")
    testImplementation("org.hamcrest:hamcrest-library:$ver_org_hamcrest_hamcrest_library_test")
    testImplementation("junit:junit:$ver_junit_junit_test") {
        exclude(group = "org.hamcrest", module = "hamcrest-core")
    }
    testImplementation("org.mockito:mockito-all:$ver_org_mockito_mockito_all_test")
    testImplementation("com.oracle:ojdbc8:$ver_com_oracle_ojdbc8_test")
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

val jaxbXjcPlugins by configurations.creating

configurations {
    jaxbXjcPlugins
}

dependencies {
    // Add all your required XJC plugin jars here
    // covers -Xequals, -XhashCode, -XtoString, -Xcopyable, -Xinheritance
    jaxbXjcPlugins("org.jvnet.jaxb:jaxb-plugins:4.0.0")
    // covers -Xannotate/annox
    jaxbXjcPlugins("org.jvnet.jaxb:jaxb-plugin-annotate:4.0.0")
}
jaxb {
    javaGen {
        register("main") {
            schemas = fileTree("src/main/resources") { include("**/*.xsd") }
            outputDir = layout.buildDirectory.dir("generated-sources/jaxb").get().asFile
            //args = listOf("-locale", "en", "-extension", "-XtoString", "-Xequals", "-XhashCode", "-Xcopyable", "-Xinheritance", "-Xannotate")
            //args = listOf("-version")
            //args = listOf("-extension")
            packageName = "bar"
            args = listOf("-extension", "-Xannotate", "-Xinheritance", "-Xcopyable", "-XtoString", "-Xequals", "-XhashCode")
            //options {
            //    xjcClasspath = jaxbXjcPlugins
            //}
        }
    }
}
afterEvaluate {
    tasks.matching { it.name == "jaxbJavaGenMain" }.configureEach {
        doFirst {
            // Add the plugin jars to the Ant classpath for the XJC task
            ant.withGroovyBuilder {
                "project"("antProject") {
                    "taskdef"(
                        "name" to "xjc",
                        "classname" to "com.sun.tools.xjc.XJCTask",
                        "classpath" to jaxbXjcPlugins.asPath
                    )
                }
            }
        }
    }
}
sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated-sources/jaxb"))

val jbangDep = "dev.jbang:jbang:0.119.0" // Update to latest if needed

configurations {
  create("jbangRunner")
}

dependencies {
  add("jbangRunner", jbangDep)
}

tasks.register<JavaExec>("runJbangXjc") {
  group = "codegen"
  description = "Run xjc via JBang from Gradle"
  mainClass.set("dev.jbang.Main")
  classpath = configurations["jbangRunner"]
  args = listOf(
    "--java", "17",
    "--deps", "org.glassfish.jaxb:jaxb-xjc:4.0.5,org.glassfish.jaxb:jaxb-runtime:4.0.5,org.jvnet.jaxb:jaxb-plugins:4.0.0,org.jvnet.jaxb:jaxb-plugin-annotate:4.0.0",
    "org.glassfish.jaxb:jaxb-xjc:4.0.5",
    "-extension", "-Xannotate", "-Xinheritance", "-Xcopyable", "-XtoString", "-Xequals", "-XhashCode",
    "-d", "target/generated-sources-jaxb",
    "-p", "com.foo.compare.generated",
    "src/main/resources/XBRLConfigurations.xsd"
  )
  // Set workingDir/output, etc, as needed
}
jbang {
  scripts {
    register("xjc") {
      // This can be any script, class, or Maven coordinate
      script = "org.glassfish.jaxb:jaxb-xjc:4.0.5"
      arguments = listOf(
        "-extension", "-Xannotate", "-Xinheritance", "-Xcopyable",
        "-XtoString", "-Xequals", "-XhashCode",
        "-d", "target/generated-sources-jaxb",
        "-p", "com.foo.compare.generated",
        "src/main/resources/XBRLConfigurations.xsd"
      )
      java = "17"
      dependencies = listOf(
        "org.glassfish.jaxb:jaxb-xjc:4.0.5",
        "org.glassfish.jaxb:jaxb-runtime:4.0.5",
        "org.jvnet.jaxb:jaxb-plugins:4.0.0",
        "org.jvnet.jaxb:jaxb-plugin-annotate:4.0.0"
      )
    }
  }
}
/**
jaxb {
    // Use a named config, e.g. "main"
    javaGen {
        create("main") {
            //for a single file
            //schema = file("src/main/resources/your.xsd")
            schemaDir = file("src/main/resources")
            // if you have .xjb files
            bindingDir = file("src/main/resources")
            generatePackage = "com.foo.compare.generated"
            outputDir = layout.buildDirectory.dir("generated-sources/jaxb").get().asFile
            locale = "en"
            removeOldOutput = true
            args = listOf("-XtoString", "-Xequals", "-XhashCode", "-Xcopyable", "-Xinheritance", "-Xannotate")
            // plugin configurations if needed:
            // plugins = ...
        }
    }
}
*/
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


tasks.register<Exec>("jbangXjc") {
  group = "codegen"
  description = "Run JAXB xjc via jbang (JAXB 4.x)"

  // On Windows with cmder/git-cmd, jbang should be on PATH
  commandLine = listOf(
    "jbang.cmd",
    "--java", "17",
    "--deps", "org.glassfish.jaxb:jaxb-xjc:4.0.5,org.glassfish.jaxb:jaxb-runtime:4.0.5",
    "org.glassfish.jaxb:jaxb-xjc:4.0.5",
    "-d", "target/generated-sources",
    "-p", "com.example.generated",
    "src/main/resources/schema.xsd"
  )
}
