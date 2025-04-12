import { readLines } from "https://deno.land/std/io/mod.ts";
import { red, green, blue, yellow, bold } from "https://deno.land/std/fmt/colors.ts";

/* ----------------- Logging Level Setup ----------------- */
export enum LogLevel {
  Trace = 0,
  Debug = 1,
  Info = 2,
  Warn = 3,
  Error = 4,
  None = 5,
}
export namespace LogLevel {
  export function getLogLevel(level: string): LogLevel {
    switch (level.toLowerCase()) {
      case "trace":
        return LogLevel.Trace;
      case "debug":
        return LogLevel.Debug;
      case "info":
        return LogLevel.Info;
      case "warn":
        return LogLevel.Warn;
      case "error":
        return LogLevel.Error;
      case "none":
        return LogLevel.None;
      default:
        return LogLevel.Info;
    }
  }
}

/* ----------------- Registry ----------------- */
export type InternalCommand = (
  line: string,
  env: Record<string, string>,
  pipedInput?: string
) => Promise<string>;

export class Registry {
  private commands: Record<string, InternalCommand> = {};
  register(name: string, commandFn: InternalCommand): void {
    this.commands[name] = commandFn;
  }
  get(name: string): InternalCommand | undefined {
    return this.commands[name];
  }
  has(name: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.commands, name);
  }
}

/* ----------------- Shell Class ----------------- */
export class Shell {
  public logLevel: LogLevel;
  public env: Record<string, string>;
  public commandRegistry: Registry;
  // We also store the last piped output here.
  public lastPipeOutput = "";

  constructor(logLevel: LogLevel = LogLevel.Info, env: Record<string, string> = {}) {
    this.logLevel = logLevel;
    this.env = env;
    this.commandRegistry = new Registry();
    this.registerDefaults();
  }

  private registerDefaults() {
    // Register the start command.
    this.commandRegistry.register("start", async (line, env) => {
      const rawUrl = line.slice("start".length).trim();
      const resolvedUrl = await this.interpolate(rawUrl);
      this.doStart(resolvedUrl);
      return "";
    });
    // Register export command.
    this.commandRegistry.register("export", async (line, env) => {
      const assignment = line.slice("export".length).trim();
      const eqIdx = assignment.indexOf("=");
      if (eqIdx === -1) {
        console.error("Invalid export syntax:", line);
        return "";
      }
      const key = assignment.slice(0, eqIdx).trim();
      const valueExpr = assignment.slice(eqIdx + 1).trim();
      // If there is a pipe, run directly; otherwise, interpolate.
      if (valueExpr.includes("|")) {
        env[key] = await this.runShellCommand(valueExpr, "");
      } else {
        env[key] = await this.interpolate(valueExpr);
      }
      if (this.logLevel === LogLevel.Debug) {
        console.debug("DEBUG export", key, "=", env[key]);
      }
      return "";
    });
    // Register assume command.
    this.commandRegistry.register("assume", async (line, env, pipedInput) => {
      //TODO improve perf by reading the number of lines and just return the input.
      const parts = line.split(/\s+/);
      if (parts.length < 2) {
        throw new Error("assume requires a parameter, e.g. 'assume 1'");
      }
      const expectedCount = Number(parts[1]);
      if (isNaN(expectedCount)) {
        throw new Error("assume parameter must be a number");
      }
      if (!pipedInput) {
        throw new Error("assume: no piped input available");
      }
      const linesOut = pipedInput.split("\n").filter(l => l.trim().length > 0);
      if (linesOut.length !== expectedCount) {
        throw new Error(`assume: expected ${expectedCount} line(s), got ${linesOut.length}:\n${pipedInput}`);
      }
      return linesOut.join("\n");
    });
    // Register regexp command.
    this.commandRegistry.register("regexp", async (line, env, pipedInput = "") => {
      const parts = line.split(/\s+/);
      if (parts.length < 2) {
        throw new Error("regexp: no pattern provided");
      }
      const pattern = parts.slice(1).join(" ");
      const regex = new RegExp(pattern);
      const match = regex.exec(pipedInput);
      if (this.logLevel === LogLevel.Trace) {
        console.debug("DEBUG regexp", pattern, "on pipedInput:", pipedInput, "=>", match);
      }
      if (match && match[1]) {
        return match[1];
      }
      return "";
    });
  }

  private doStart(url: string) {
    // Dialects for start.
    const dialects = {
      win: { start: "cmd /c start" },
      darwin: { start: "open" },
      linux: { start: "xdg-open" },
      mobile: { start: (params: { url: string }) => console.log("Open manually this url", params.url) },
    };
    const osType =
      Deno.build.os === "windows" ? "win" :
      Deno.build.os === "darwin" ? "darwin" :
      Deno.build.os === "linux" ? "linux" : "mobile";
    const currentDialect = dialects[osType];
    if (typeof currentDialect.start === "string") {
      const cmd = currentDialect.start + " " + url;
      const p = Deno.run({ cmd: cmd.split(" "), stdout: "null", stderr: "null" });
      p.status();
      p.close();
    } else {
      currentDialect.start({ url });
    }
  }

  public async runShellCommand(
    command: string,
    prefix: string
  ): Promise<string> {
    const env = this.env;
    command = command.replace(/\n/g, " ").trim();
    if (command.includes("kubectl exec") && command.includes("-it")) {
      command = command.replace("-it", "-i");
    }
    const segments = command.split("|").map(seg => seg.trim());
    let input: Uint8Array | null = null;
    let segmentOutput = "";
    for (let i = 0; i < segments.length; i++) {
      const segment = segments[i];
      const args = segment.split(/\s+/);
      const firstWord = args[0];
      if (this.logLevel < LogLevel.Info && i < segments.length - 1) {
        console.log(prefix + "[SEGMENT " + (i + 1) + "/" + segments.length + "]: " + segment);
      }
      let segmentResult = "";
      if (this.commandRegistry.has(firstWord)) {
        const pipedInput = input ? new TextDecoder().decode(input).trim() : "";
        segmentResult = await this.commandRegistry.get(firstWord)!(segment, this.env, pipedInput);
      } else {
        let proc;
        try {
          proc = Deno.run({
            cmd: args,
            stdin: input ? "piped" : "null",
            stdout: "piped",
            stderr: "piped",
          });
        } catch (e) {
          throw new Error(`Failed to spawn command: ${segment}\n${e.message}`);
        }
        if (input) {
          await proc.stdin.write(input);
          proc.stdin.close();
        }
        const stdoutLines: string[] = [];
        const stderrLines: string[] = [];
        const stdoutPromise = (async () => {
          for await (const line of readLines(proc.stdout)) {
            stdoutLines.push(line);
            if (this.logLevel === LogLevel.Trace) {
              console.log(prefix + line);
            }
          }
        })();
        const stderrPromise = (async () => {
          for await (const line of readLines(proc.stderr)) {
            stderrLines.push(line);
            if (this.logLevel <= LogLevel.Debug) {
              console.error(prefix + line);
            }
          }
        })();
        await Promise.all([stdoutPromise, stderrPromise]);
        const { code } = await proc.status();
        if (code !== 0 && !(args[0] === "grep" && code === 1)) {
          proc.close();
          throw new Error(`Command failed: ${segment}`);
        }
        proc.close();
        segmentResult = stdoutLines.join("\n");
        //console.log("Running command:", segment, "with args:", args, "and input:", proc,code,);
      }
      if (this.logLevel < LogLevel.Info && (i + 1) < segments.length) {
        console.log(prefix + "[SEGMENT " + (i + 1) + "/" + segments.length + "]: " + segment + " =>\n" + segmentResult);
      }
      input = new TextEncoder().encode(segmentResult);
      segmentOutput = segmentResult;
    }
    this.lastPipeOutput = segmentOutput.trim();
    return segmentOutput.trim();
  }

  public async interpolate(text: string): Promise<string> {
    const env = this.env
    const cmdSubPattern = /\$\(([\s\S]*?)\)/g;
    let match: RegExpExecArray | null;
    while ((match = cmdSubPattern.exec(text)) !== null) {
      const wholeMatch = match[0];
      const innerCmd = match[1].trim();
      const interpolatedInner = await this.interpolate(innerCmd);
      let result: string;
      if (interpolatedInner.includes("\n")) {
        result = await this.shellScript(interpolatedInner, 1, this.logLevel);
      } else {
        result = await this.runShellCommand(interpolatedInner, "");
      }
      text = text.replace(wholeMatch, result.trim());
      cmdSubPattern.lastIndex = 0;
    }
    const varPattern = /\$(?:\{([A-Za-z_]\w*)\}|([A-Za-z_]\w*))/g;
    for (const match of Array.from(text.matchAll(varPattern))) {
      const varName = match[1] || match[2];
      let value = env[varName] || "";
      if (value instanceof Promise) {
        value = await value;
      }
      text = text.replace(match[0], value);
    }
    if (this.logLevel === LogLevel.Debug) {
      console.debug("DEBUG interpolate:", text);
    }
    return text;
  }

  public async shellScript(
    script: string,
    indentLevel: number = 0,
    verbosity: LogLevel = this.logLevel
  ): Promise<string> {
    if (this.logLevel <= LogLevel.Trace) {
      console.info("DEBUG shellScript:["+script+"]");
    }
    const rawLines = script.split("\n");
    const lines: string[] = [];
    const balanceCount = (s: string): number =>
      (s.match(/\$\(/g)?.length || 0) - (s.match(/\)/g)?.length || 0);
  
    // Combine lines for export commands with unbalanced $(" and preserve comments.
    for (let i = 0; i < rawLines.length; i++) {
      let line = rawLines[i].trim();
      if (line.length === 0) continue;
      if (line.startsWith("#")) {
        lines.push(line);
        continue;
      }
      if (line.startsWith("export ") && line.includes("$(")) {
        let exportLine = line;
        let count = balanceCount(exportLine);
        while (count > 0 && i < rawLines.length - 1) {
          i++;
          exportLine += "\n" + rawLines[i].trim();
          count = balanceCount(exportLine);
        }
        lines.push(exportLine);
      } else {
        lines.push(line);
      }
    }
  
    let lastOutput = "";
    const indent = " ".repeat(indentLevel * 2);
    for (let i = 0; i < lines.length; i++) {
      const originalLine = lines[i];
      if (originalLine.startsWith("#")) {
        if (verbosity <= LogLevel.Info) console.log(indent + bold(yellow(originalLine)));
        continue;
      }
      if (verbosity <= LogLevel.Debug) {
        console.log(indent + bold(yellow("Script: ")) + originalLine);
      }
      const interpolatedLine = await this.interpolate(originalLine);
      const lineNum = i + 1;
      const firstWord = interpolatedLine.split(/\s+/)[0];
      const basePrefix = `${firstWord}:${lineNum}`.padEnd(10, " ");
      const cmdPrefix = indent + bold(blue(basePrefix + "> "));
      const outPrefix = indent + bold(green(basePrefix + "< "));
      if (verbosity <= LogLevel.Info) {
        console.log(indent + cmdPrefix + interpolatedLine);
      }
      try {
        if (this.commandRegistry.has(firstWord)) {
          lastOutput = await this.commandRegistry.get(firstWord)!(interpolatedLine, this.env, lastOutput);
        } else {
          lastOutput = await this.runShellCommand(interpolatedLine, outPrefix);
        }
        if (verbosity <= LogLevel.Info) {
          const finalLines = lastOutput.split("\n").map(line => outPrefix + line);
          console.log(finalLines.join("\n"));
        }
      } catch (err) {
        console.error(indent + bold(red(basePrefix + "err> ")) + red(err.message));
        throw err;
      }
    }
    return lastOutput;
  }

  public shell = async (
    strings: TemplateStringsArray,
    ...values: any[]
  ): Promise<string> => {
    if(this.logLevel <= LogLevel.Trace) {
      console.log("Shell called with:", strings, values);	
    }
    let script = "";
    for (let i = 0; i < strings.length; i++) {
      script += strings[i] + (i < values.length ? String(values[i]) : "");
    }
    return await this.shellScript(script);
  };
}
