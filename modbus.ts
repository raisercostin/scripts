#!/usr/bin/env deno run --allow-net --allow-read --allow-write

/**
 * merge_params_full.ts
 *
 * Merge Fronius Modbus params from JSON/TSV sources in the exact order
 * provided on the command line, and produce a full Param TSV matching
 * the Java ModbusParam fields.
 *
 * Usage:
 *   deno run --allow-net --allow-read --allow-write merge_params_full.ts \
 *     --from-simple-tsv simple.tsv \
 *     --from-json https://…/registers.json \
 *     --from-full-tsv full.tsv \
 *     --slave 1 \
 *     [--output merged.tsv]
 */

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";

interface RawParam {
  offset: number;
  description: string;
  unit: string;
  rawDataType: string;
  name?: string;
}

interface Param {
  param: string;
  group: string;
  level: string;
  name: string;
  description: string;
  values: string;
  defaultValue: string;
  minValue: string;
  maxValue: string;
  remarks: string;
  unit: string;
  step: string;
  precision: string;
  scale: string;
  offset: string;
  value: string;
  type: string;
  address: number;
  dataType: string;
  modbusValue: string;
}

type SourceSpec = { type: "json" | "simple-tsv" | "full-tsv"; src: string };

async function readJson(source: string): Promise<RawParam[]> {
  const txt = source.startsWith("http")
    ? await (await fetch(source)).text()
    : await Deno.readTextFile(source);
  const obj = JSON.parse(txt) as Record<string, {
    description: string;
    unit: string;
    data_type: string;
    access: string;
  }>;
  return Object.entries(obj)
    .filter(([, info]) => info.access.toLowerCase().includes("read"))
    .map(([addr, info]) => ({
      offset: Number(addr),
      description: info.description,
      unit: info.unit,
      rawDataType: info.data_type,
    }));
}

async function readTsv(path: string): Promise<RawParam[]> {
  const text = await Deno.readTextFile(path);
  const [header, ...lines] = text.trim().split("\n");
  const cols = header.toLowerCase().split("\t");
  const idx = {
    offset: cols.indexOf("modbus offset") >= 0 ? cols.indexOf("modbus offset") : cols.indexOf("offset"),
    description: cols.indexOf("description"),
    unit: cols.indexOf("units") >= 0 ? cols.indexOf("units") : cols.indexOf("unit"),
    rawDataType: cols.indexOf("data type"),
    name: cols.indexOf("name")
  };
  return lines.map(line => {
    const parts = line.split("\t");
    return {
      offset: Number(parts[idx.offset]),
      description: parts[idx.description],
      unit: parts[idx.unit],
      rawDataType: parts[idx.rawDataType],
      name: idx.name >= 0 ? parts[idx.name] : undefined
    };
  });
}

function merge(lists: RawParam[][]): RawParam[] {
  const m = new Map<number, RawParam>();
  for (const list of lists) {
    for (const p of list) {
      m.set(p.offset, p);
    }
  }
  return Array.from(m.values()).sort((a, b) => a.offset - b.offset);
}

function makeName(r: RawParam): string {
  if (r.name && r.name.trim()) return r.name;
  return r.description
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_|_$/g, "");
}

function mapDataType(raw: string): string {
  switch (raw.toLowerCase()) {
    case "single": return "float32";
    case "word": return "uint16";
    case "smallint": return "int16";
    case "dword": return "uint32";
    default: return raw;
  }
}

async function run(sources: SourceSpec[], slave: number, output?: string) {
  // 1) read all sources in order
  const raws: RawParam[][] = [];
  for (const { type, src } of sources) {
    raws.push(type === "json" ? await readJson(src) : await readTsv(src));
  }

  // 2) merge by offset
  const mergedRaw = merge(raws);

  // 3) map to full Param
  const params: Param[] = mergedRaw.map(r => ({
    param: `P${r.offset}`,
    group: "",
    level: "",
    name: makeName(r),
    description: r.description,
    values: "",
    defaultValue: "",
    minValue: "",
    maxValue: "",
    remarks: "",
    unit: r.unit,
    step: "",
    precision: "",
    scale: "",
    offset: "",
    value: "",
    type: "holding",
    address: r.offset,
    dataType: mapDataType(r.rawDataType),
    modbusValue: ""
  }));

  // 4) output TSV
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

  if (output) {
    await Deno.writeTextFile(output, tsv);
    console.log(`Saved ${params.length} params to ${output}`);
  } else {
    console.log(tsv);
  }
}

await new Command()
  .name("merge_params_full")
  .description("Merge Fronius Modbus params and produce full Param TSV")
  .option("--from-json <src:string>",    "JSON URL or file",     { collect: true })
  .option("--from-simple-tsv <file:string>", "simple TSV file", { collect: true })
  .option("--from-full-tsv <file:string>",   "full TSV file",   { collect: true })
  .option("--slave <id:number>",         "Modbus slave ID",      { default: 1 })
  .option("--output <file:string>",      "Output TSV file; omit to print")
  .action(options => {
    // build ordered sources from raw Deno.args
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
    if (!sources.length) {
      console.error("Error: no input sources provided");
      Deno.exit(1);
    }
    // invoke business logic
    return run(sources, options.slave, options.output);
  })
  .parse(Deno.args);
