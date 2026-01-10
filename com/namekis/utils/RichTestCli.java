package com.namekis.utils;

import java.io.PrintWriter;

import org.junit.platform.console.options.CommandResult;

//DEPS org.junit.jupiter:junit-jupiter:5.11.3
//DEPS org.assertj:assertj-core:3.26.3
//DEPS org.junit.jupiter:junit-jupiter-engine:5.11.3
//DEPS org.junit.platform:junit-platform-launcher:1.11.3
//DEPS org.junit.platform:junit-platform-console:1.11.3

//SOURCES RichCli.java

public class RichTestCli {
  public static void main(String[] allArgs) {
    com.namekis.utils.RichCli.main(allArgs, args -> {
      java.util.List<String> fullArgs = new java.util.ArrayList<>(java.util.List.of(args));
      if (args.length == 0) {
        fullArgs.add("execute");
        fullArgs.add("--scan-class-path");
      }
      fullArgs.add(System.getProperty("java.class.path"));
      fullArgs.add("--disable-banner");
      mymain(fullArgs.toArray(String[]::new));
    });
  }

  private static void mymain(String... args) {
    // org.junit.platform.console.ConsoleLauncher.main(args);
    PrintWriter out = new PrintWriter(System.out);
    PrintWriter err = new PrintWriter(System.err);
    CommandResult<?> result = org.junit.platform.console.ConsoleLauncher.run(out, err, args);
    int exitCode = result.getExitCode();
    if (exitCode != 0)
      System.exit(result.getExitCode());
  }

  /**
   * Resolve the calling test class from the stack and run only that test class (including nested tests).
   */
  public static void main2(String[] allArgs) {
    String testClassName = detectTestClassNameFromStacktrace();
    main2(allArgs, testClassName);
  }

  /**
   * Run JUnit with classpath scanning scoped to the given test class (including nested tests).
   */
  public static void main2(String[] allArgs, String testClassName) {
    com.namekis.utils.RichCli.main(allArgs, args -> {
      java.util.List<String> fullArgs = new java.util.ArrayList<>();
      if (args == null || args.length == 0) {
        fullArgs.add("execute");
        fullArgs.add("--scan-class-path");
        String includePattern = buildIncludePattern(testClassName);
        if (includePattern != null) {
          fullArgs.add("--include-classname");
          fullArgs.add(includePattern);
        }
      } else {
        for (String arg : args)
          fullArgs.add(arg);
      }

      // Critical: Add classpath so launcher finds tests and dependencies
      fullArgs.add("-cp");
      fullArgs.add(System.getProperty("java.class.path"));
      fullArgs.add("--disable-banner");
      mymain(fullArgs.toArray(new String[0]));
    });
  }

  private static String buildIncludePattern(String testClassName) {
    if (testClassName == null || testClassName.isBlank())
      return null;
    String escaped = java.util.regex.Pattern.quote(testClassName);
    return "^" + escaped + "(\\$.*)?$";
  }

  private static String detectTestClassNameFromStacktrace() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    for (StackTraceElement element : stack) {
      String className = element.getClassName();
      if (className.endsWith("_test") || className.endsWith("Test")) {
        return className;
      }
    }
    if (stack.length == 0)
      throw new IllegalStateException("Could not detect test class name from stacktrace");
    return stack[stack.length - 1].getClassName();
  }
}
