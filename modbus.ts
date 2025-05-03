#!/usr/bin/env deno run --allow-net --allow-read --allow-write

/**
 * Merge Modbus params from JSON/TSV sources output a full Param TSV.
 */

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { yellow, cyan, green } from "https://deno.land/std@0.192.0/fmt/colors.ts";

//
// ——— JSON import schema types ———
//

/** Allowed Modbus function codes (JSON schema enum) */
export type FunctionCode = 1 | 2 | 3 | 4 | 5 | 6 | 15 | 16;                    // 3=Read Holding, 4=Read Input, etc.

/** Allowed data types in imported JSON */
export type JsonDataType =
  | "int16" | "uint16" | "int32" | "uint32"
  | "int64" | "uint64" | "float" | "double"
  | "string" | "boolean";                                                      // as per JSON schema

/** Access modes for registers */
export type AccessMode = "read" | "write" | "read/write";                      // as per JSON schema

/** Single register definition from JSON */
export interface RegisterEntry {
  name: string;                   // JSON “name”
  description: string;            // JSON “description”
  function_codes: FunctionCode[]; // JSON “function_codes”
  size?: number;                  // JSON “size”
  data_type: JsonDataType;        // JSON “data_type”
  unit: string;                   // JSON “unit”
  access: AccessMode;             // JSON “access”
  scaling_factor?: number;        // JSON “scaling_factor”
  scaling_offset?: number;        // JSON “scaling_offset”
}

/** Full set of register definitions keyed by offset */
export type RegisterDefinitions = Record<string, RegisterEntry>;

//
// ——— Union types for Param fields ———
//


export type Level = "U" | "I" | "S";                                          // User / Installer / Service

/** Modbus register type */
export type ModbusRegisterType = "coil" | "discrete" | "holding" | "input" | "unknown";    // based on function codes

/** Home Assistant data type */
export type DataType = "float32" | "uint16" | "int16" | "uint32";              // for YAML output

//
// ——— Internal raw & final types ———
//

/** Raw input row from JSON or TSV */
interface RawParam {
  offset: number;             // Modbus offset
  description: string;        // human-readable description
  unit: string;               // measurement unit
  rawDataType: string;        // JSON/TSV data_type
  functionCodes?: FunctionCode[]; // from JSON, if available
  name?: string;              // optional base name
  scaling_factor?: number;    // optional
  scaling_offset?: number;    // optional
}

/** Complete Param record matching Java ModbusParam */
export interface Param {
  param: string;              // e.g. "P40069"
  group: string;              // parameter group, unused here
  level: Level;               // access level, default "U"
  name: string;               // snake_cased name
  description: string;        // human description
  values: string;             // enumerated values
  defaultValue: string;       // default setting
  minValue: string;           // minimum allowed
  maxValue: string;           // maximum allowed
  remarks: string;            // extra notes
  unit: string;               // e.g. "A"
  step: string;               // increment step
  precision: string;          // decimal precision
  scale: string;              // scale factor
  offset: string;             // scale offset
  value: string;              // current scaled value
  type: ModbusRegisterType;   // determined from functionCodes
  address: number;            // Modbus register address
  dataType: DataType;         // HA data type
  modbusValue: string;        // raw register value
}

/** CLI source specification */
type SourceSpec = { type: "json" | "simple-tsv" | "full-tsv"; src: string };

//
// ——— Business logic ———
//

async function mergeParams(
  sources: SourceSpec[],
  slave: number,
  outputFile?: string,
  dryRun = false,
  logLevel = "info"
) {
  if (logLevel === "debug") console.debug(cyan("📥 Reading sources in order…"));
  const raws: RawParam[][] = [];
  for (const { type, src } of sources) {
    if (logLevel === "debug") console.debug(`  • ${type} → ${src}`);
    raws.push(type === "json" ? await readJson(src) : await readTsv(src));
  }

  const mergedRaw = mergeRawParams(raws);
  if (logLevel === "debug") console.debug(cyan(`🔗 Merged to ${mergedRaw.length} unique params`));

  const params: Param[] = mergedRaw.map(r => {
    // determine register type
    const codes = r.functionCodes ?? [];
    const regType: ModbusRegisterType =
      codes.includes(1) ? "coil" :
      codes.includes(2) ? "discrete" :
      codes.includes(3) ? "holding" :
      codes.includes(4) ? "input" :
      /* default/implies read holding */ "unknown";

    return {
      param:         `P${r.offset}`,
      group:         "",
      level:         "U",
      name:          makeName(r),
      description:   r.description,
      values:        "",
      defaultValue:  "",
      minValue:      "",
      maxValue:      "",
      remarks:       "",
      unit:          r.unit,
      step:          "",
      precision:     "",
      scale:         r.scaling_factor?.toString() || "",
      offset:        r.scaling_offset?.toString() || "",
      value:         "",
      type:          regType,
      address:       r.offset,
      dataType:      mapDataType(r.rawDataType),
      modbusValue:   ""
    };
  });

  const header = [
    "param","group","level","name","description","values","defaultValue",
    "minValue","maxValue","remarks","unit","step","precision","scale",
    "offset","value","type","address","dataType","modbusValue"
  ].join("\t");
  const lines = params.map(p =>
    [
      p.param,p.group,p.level,p.name,p.description,p.values,p.defaultValue,
      p.minValue,p.maxValue,p.remarks,p.unit,p.step,p.precision,p.scale,
      p.offset,p.value,p.type,p.address,p.dataType,p.modbusValue
    ].join("\t")
  );
  const tsv = [header, ...lines].join("\n");

  if (dryRun) {
    console.log(yellow("⚠️  Dry run — printing TSV without writing."));
    console.log(tsv);
  } else if (outputFile) {
    await Deno.writeTextFile(outputFile, tsv);
    console.log(green(`✅ Saved ${params.length} params to ${outputFile}`));
  } else {
    console.log(tsv);
  }
}

async function readJson(source: string): Promise<RawParam[]> {
  const txt = source.startsWith("http")
    ? await (await fetch(source)).text()
    : await Deno.readTextFile(source);
  const obj = JSON.parse(txt) as RegisterDefinitions;
  return Object.entries(obj)
    .filter(([, e]) => e.access === "read" || e.access === "read/write")
    .map(([addr, e]) => ({
      offset:         Number(addr),
      description:    e.description,
      unit:           e.unit,
      rawDataType:    e.data_type,
      functionCodes:  e.function_codes,
      name:           e.name,
      scaling_factor: e.scaling_factor,
      scaling_offset: e.scaling_offset
    }));
}

async function readTsv(path: string): Promise<RawParam[]> {
  const text = await Deno.readTextFile(path);
  const [header, ...lines] = text.trim().split("\n");
  const cols = header.toLowerCase().split("\t");
  const idx = {
    offset:      cols.includes("modbus offset") ? cols.indexOf("modbus offset") : cols.indexOf("offset"),
    description: cols.indexOf("description"),
    unit:        cols.includes("units") ? cols.indexOf("units") : cols.indexOf("unit"),
    rawDataType: cols.indexOf("data type"),
    name:        cols.indexOf("name")
  };
  return lines.map(line => {
    const parts = line.split("\t");
    return {
      offset:      Number(parts[idx.offset]),
      description: parts[idx.description],
      unit:        parts[idx.unit],
      rawDataType: parts[idx.rawDataType],
      name:        idx.name >= 0 ? parts[idx.name] : undefined
    };
  });
}

function mergeRawParams(lists: RawParam[][]): RawParam[] {
  const map = new Map<number, RawParam>();
  for (const list of lists) for (const p of list) map.set(p.offset, p);
  return Array.from(map.values()).sort((a, b) => a.offset - b.offset);
}

function makeName(r: RawParam): string {
  if (r.name && r.name.trim()) return r.name;
  return r.description
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_|_$/g, "");
}

function mapDataType(raw: string): DataType {
  switch (raw.toLowerCase()) {
    case "single":   return "float32";
    case "word":     return "uint16";
    case "smallint": return "int16";
    case "dword":    return "uint32";
    default:         return raw as DataType;
  }
}

//
// ——— CLI parser with Cliffy ———
//

await new Command()
  .name("merge_params_full")
  .version("1.0.0")
  .description("Merge Fronius Modbus params and output full Param TSV")
  .option("-n, --dry-run",           "print TSV without writing")
  .option("--log-level <level>",     "log level: debug|info|warn|error|quiet", { default: "info" })
  .option("--from-json <src:string>",        "JSON URL or file",    { collect: true })
  .option("--from-simple-tsv <file:string>", "simple TSV file",     { collect: true })
  .option("--from-full-tsv <file:string>",   "full TSV file",       { collect: true })
  .option("--slave <id:number>",     "Modbus slave ID",      { default: 1 })
  .option("--output <file:string>",  "Output TSV file; omit to print")
  .action(function (options) {
    if (
      !options.fromJson?.length &&
      !options.fromSimpleTsv?.length &&
      !options.fromFullTsv?.length
    ) return this.showHelp();
    const args = Deno.args;
    const sources: SourceSpec[] = [];
    for (let i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--from-json":
          sources.push({ type: "json",       src: args[++i] });
          break;
        case "--from-simple-tsv":
          sources.push({ type: "simple-tsv", src: args[++i] });
          break;
        case "--from-full-tsv":
          sources.push({ type: "full-tsv",   src: args[++i] });
          break;
      }
    }
    return mergeParams(
      sources,
      options.slave,
      options.output,
      options.dryRun,
      options.logLevel
    );
  })
  .parse(Deno.args);
