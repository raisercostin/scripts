import { join, extname, basename } from "https://deno.land/std@0.203.0/path/mod.ts";
import * as xlsx from "https://deno.land/x/sheetjs@v0.18.3/xlsx.mjs";
import * as cptable from "https://deno.land/x/sheetjs@v0.18.3/dist/cpexcel.full.mjs";
import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { parse as parseDate } from "https://deno.land/std@0.203.0/datetime/mod.ts";

xlsx.set_cptable(cptable);

interface ParsedOptions {
  csvFile: string;
  xlsxFile: string;
  delimiter: string;
  force: boolean;
  detectNumbers: boolean;
  dateFormats: string[];
}

async function fileExists(path: string): Promise<boolean> {
  try {
    await Deno.stat(path);
    return true;
  } catch {
    return false;
  }
}

function normalizeNumbers(rows: string[][]): string[][] {
  return rows.map((row) =>
    row.map((value) => {
      const trimmed = value.trim();
      if (/^-?\d{1,3}(,\d{3})*(\.\d+)?$/.test(trimmed)) {
        return trimmed.replace(/,/g, "").replace(/(\.\d+)$/, ".$1");
      } else if (/^-?\d+,\d+$/.test(trimmed)) {
        return trimmed.replace(",", ".");
      }
      return trimmed;
    })
  );
}

function tryParseDate(value: string, formats: string[]): number | null {
  for (const format of formats) {
    try {
      //console.log(`try [${value.trim()}] [${format}] ${typeof format}`)
      const parsed = parseDate(value.trim(), format + "");
      if (parsed) {
        //console.log("parsed",parsed)
        // Convert date to Excel's numeric format (days since 1900-01-01)
        //const excelDate = (parsed.getTime() - new Date(Date.UTC(1900, 0, 1)).getTime()) / (1000 * 60 * 60 * 24) + 2;
        //return excelDate; // Return Excel-compatible numeric date
        return parsed.toISOString().split("T")[0];
        // return parsed.toISOString(); // Standardize to ISO 8601 format
      }
    } catch (e) {
      // Ignore parse errors and try the next format
      //console.log("error",e)
    }
  }
  return null;
}

function normalizeDates(rows: string[][], dateFormats: string[]): (string | number)[][] {
  return rows.map((row) =>
    row.map((value) => {
      const parsedDate = tryParseDate(value, dateFormats);
      return parsedDate ? parsedDate : value;
    })
  );
}

async function csvToXlsx(options: ParsedOptions) {
  console.log("options:", options)
  const { csvFile, xlsxFile, delimiter, force, detectNumbers, dateFormats } = options;

  if (!(await fileExists(csvFile))) {
    throw new Error(`Input CSV file does not exist: ${csvFile}`);
  }

  if (await fileExists(xlsxFile)) {
    if (!force) {
      throw new Error(
        `Output file "${xlsxFile}" already exists. Use --force to overwrite.`
      );
    }
    await Deno.remove(xlsxFile);
  }

  const csvContent = await Deno.readTextFile(csvFile);
  let rows = csvContent.split("\n").map((line) => line.split(delimiter));

  if (detectNumbers) {
    rows = normalizeNumbers(rows);
  }

  if (dateFormats.length > 0) {
    rows = normalizeDates(rows, dateFormats);
  }

  const workbook = xlsx.utils.book_new();
  const worksheet = xlsx.utils.aoa_to_sheet(rows);

  xlsx.utils.book_append_sheet(workbook, worksheet, "Sheet1");
  xlsx.writeFile(workbook, xlsxFile);

  console.log(`Successfully converted ${csvFile} to ${xlsxFile}`);
}

await new Command()
  .name("convertcsv")
  .version("1.2.0")
  .description("Convert a CSV file to an XLSX file with optional numeric and date format detection.")
  .arguments("<csvFile:string>")
  .option(
    "-o, --output [xlsxFile:string]",
    "The output XLSX file. Defaults to the same name as the CSV file with .xlsx extension."
  )
  .option("-d, --delimiter [delimiter:string]", "The CSV delimiter. Defaults to ';'.", {
    default: ";",
  })
  .option("-f, --force [force:boolean]", "Force overwrite of the output file if it already exists.", {
    default: false,
  })
  .option(
    "-n, --detect-numbers [detectNumbers:boolean]",
    "Detect and convert numeric formats (e.g., '1,23' to '1.23').",
    {
      default: false,
    }
  )
  .option("--date-format <dateFormats:string[]>", "Specify acceptable date formats. Multiple formats can be provided, separated by commas.", {
    collect: true,
    default: [],
  })
  .action(async (options, csvFile) => {
    const xlsxFile =
      options.output || join(Deno.cwd(), `${basename(csvFile, extname(csvFile))}.xlsx`);

    const parsedOptions: ParsedOptions = {
      csvFile,
      xlsxFile,
      delimiter: options.delimiter,
      force: options.force,
      detectNumbers: options.detectNumbers,
      dateFormats: options.dateFormat || [],
    };

    await csvToXlsx(parsedOptions);
  })
  .parse(Deno.args);
