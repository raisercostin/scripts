#!/usr/bin/env jbang
//JAVA 21

import java.io.*;
import java.net.URI;
import java.awt.Desktop;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/* === Logging Level Setup === */
enum LogLevelCode { TRACE, DEBUG, INFO, WARN, ERROR, NONE }

class LogLevel {
    private static final Map<LogLevelCode, LogLevel> levels = new EnumMap<>(LogLevelCode.class);

    public static final LogLevel TRACE = new LogLevel(LogLevelCode.TRACE, 0);
    public static final LogLevel DEBUG = new LogLevel(LogLevelCode.DEBUG, 1);
    public static final LogLevel INFO  = new LogLevel(LogLevelCode.INFO,  2);
    public static final LogLevel WARN  = new LogLevel(LogLevelCode.WARN,  3);
    public static final LogLevel ERROR = new LogLevel(LogLevelCode.ERROR, 4);
    public static final LogLevel NONE  = new LogLevel(LogLevelCode.NONE,  5);

    public final LogLevelCode code;
    public final int value;

    private LogLevel(LogLevelCode code, int value) {
        this.code = code;
        this.value = value;
        levels.put(code, this);
    }

    public static LogLevel from(LogLevelCode c) { return levels.get(c); }

    public boolean enabledFor(LogLevelCode c) { return this.value <= levels.get(c).value; }
    public boolean isTrace() { return enabledFor(LogLevelCode.TRACE); }
    public boolean isDebug() { return enabledFor(LogLevelCode.DEBUG); }
    public boolean isInfo()  { return enabledFor(LogLevelCode.INFO); }
    public boolean isWarn()  { return enabledFor(LogLevelCode.WARN); }
    public boolean isError() { return enabledFor(LogLevelCode.ERROR); }
}

/* ANSI color helpers */
class Clr {
    static String red(String s)    { return "\u001B[31m"+s+"\u001B[0m"; }
    static String green(String s)  { return "\u001B[32m"+s+"\u001B[0m"; }
    static String yellow(String s) { return "\u001B[33m"+s+"\u001B[0m"; }
    static String blue(String s)   { return "\u001B[34m"+s+"\u001B[0m"; }
    static String bold(String s)   { return "\u001B[1m"+s+"\u001B[0m"; }
}

@FunctionalInterface
interface InternalCommand {
    String run(String line, Map<String,String> env, String pipedInput) throws Exception;
}

class Registry {
    private final Map<String,InternalCommand> commands = new HashMap<>();
    void register(String name, InternalCommand fn) { commands.put(name, fn); }
    boolean has(String name) { return commands.containsKey(name); }
    InternalCommand get(String name) { return commands.get(name); }
}

class Shell {
    LogLevel logLevel;
    Map<String,String> env;
    Registry registry = new Registry();
    String lastPipeOutput = "";
    boolean ignoreError = false;

    Shell(LogLevelLevelOrCode lvl, Map<String,String> env) {
        this.logLevel = (lvl instanceof LogLevel) ? (LogLevel)lvl : LogLevel.from((LogLevelCode)lvl);
        this.env = env;
        registerDefaults();
    }

    // --- defaults
    private void registerDefaults() {
        registry.register("start", (line, env, pipe) -> {
            String url = line.substring("start".length()).trim();
            Desktop.getDesktop().browse(new URI(url));
            return "";
        });

        registry.register("export", (line, env, pipe) -> {
            String asg = line.substring("export".length()).trim();
            int eq = asg.indexOf('=');
            if (eq<0) { System.err.println("Invalid export: "+line); return ""; }
            String key = asg.substring(0,eq).trim();
            String valExpr = asg.substring(eq+1).trim();
            String val = valExpr.contains("|")
                ? runShellCommand(valExpr, "")
                : interpolate(valExpr);
            env.put(key, val);
            if (logLevel.isDebug()) System.err.println("DEBUG export "+key+"="+val);
            return "";
        });

        registry.register("assume", (line, env, piped) -> {
            String[] parts = line.split("\\s+");
            if(parts.length<2) throw new IllegalArgumentException("assume needs count");
            int expected = Integer.parseInt(parts[1]);
            if (piped==null) throw new IllegalStateException("no piped input");
            List<String> lines = Arrays.stream(piped.split("\n"))
                                       .filter(l->!l.isBlank()).collect(Collectors.toList());
            if (lines.size()!=expected)
                throw new IllegalStateException("assume: expected "+expected+" got "+lines.size());
            return String.join("\n", lines);
        });

        registry.register("regexp", (line, env, piped) -> {
            String pat = line.substring("regexp".length()).trim();
            Matcher m = Pattern.compile(pat).matcher(piped);
            return m.find() && m.groupCount()>=1 ? m.group(1) : "";
        });
    }

    // --- run external commands with simple pipe support
    String runShellCommand(String cmd, String prefix) throws Exception {
        List<String> segments = Arrays.stream(cmd.split("\\|"))
                                      .map(String::trim).collect(Collectors.toList());
        String piped = null;
        for (int i=0;i<segments.size();i++) {
            String seg = segments.get(i);
            String[] args = seg.split("\\s+");
            String out;
            if (registry.has(args[0])) {
                out = registry.get(args[0]).run(seg, env, piped==null?"":piped);
            } else {
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.environment().putAll(env);
                if (piped!=null) {
                    Process p = pb.start();
                    try(var w = p.getOutputStream()) {
                        w.write(piped.getBytes());
                    }
                    out = new String(p.getInputStream().readAllBytes()).trim();
                    p.waitFor();
                } else {
                    Process p = pb.start();
                    out = new String(p.getInputStream().readAllBytes()).trim();
                    p.waitFor();
                }
            }
            piped = out;
        }
        lastPipeOutput = (piped==null?"":piped).trim();
        return lastPipeOutput;
    }

    // --- variable & command interpolation
    String interpolate(String text) throws Exception {
        Matcher cmdM = Pattern.compile("\\$\\(([^)]*)\\)").matcher(text);
        while(cmdM.find()) {
            String inner = cmdM.group(1).trim();
            String val = runShellCommand(inner, "");
            text = text.replace(cmdM.group(0), val);
            cmdM = Pattern.compile("\\$\\(([^)]*)\\)").matcher(text);
        }
        Matcher varM = Pattern.compile("\\$(?:\\{(\\w+)\\}|(\\w+))").matcher(text);
        while(varM.find()) {
            String name = varM.group(1)==null?varM.group(2):varM.group(1);
            String val = env.getOrDefault(name, "");
            text = text.replace(varM.group(0), val);
            varM = Pattern.compile("\\$(?:\\{(\\w+)\\}|(\\w+))").matcher(text);
        }
        return text;
    }

    // --- main script executor
    String shellScript(String script, int indent) throws Exception {
        String[] raw = script.split("\n");
        List<String> lines = new ArrayList<>();
        for(var r: raw) {
            r=r.trim();
            if(r.isEmpty()||r.startsWith("#")) { lines.add(r); continue; }
            lines.add(r);
        }
        String last = "";
        String ind = " ".repeat(indent*2);
        for(int i=0;i<lines.size();i++) {
            String ln = lines.get(i);
            if(ln.startsWith("#")) {
                if(logLevel.isInfo()) System.out.println(ind+Clr.bold(Clr.yellow(ln)));
                continue;
            }
            String interp = interpolate(ln);
            if(logLevel.isInfo()) System.out.println(ind+Clr.bold(Clr.blue("cmd> ")) + interp);
            try {
                last = registry.has(interp.split("\\s+")[0])
                     ? registry.get(interp.split("\\s+")[0]).run(interp, env, last)
                     : runShellCommand(interp, "");
                if(logLevel.isInfo()) {
                    for(var outLine : last.split("\n"))
                        System.out.println(ind+Clr.green("out> ")+outLine);
                }
            } catch(Exception e) {
                System.err.println(ind+Clr.bold(Clr.red("err> ")) + e.getMessage());
                throw e;
            }
        }
        return last;
    }
}

public class jesh {
    public static void main(String[] args) throws Exception {
        LogLevel lvl = LogLevel.INFO;
        Map<String,String> env = new HashMap<>(System.getenv());
        Shell shell = new Shell(lvl, env);

        String script;
        if (args.length>0) {
            script = String.join(" ", args);
        } else {
            script = new BufferedReader(new InputStreamReader(System.in))
                .lines().collect(Collectors.joining("\n"));
        }
        shell.shellScript(script, 0);
    }
}
