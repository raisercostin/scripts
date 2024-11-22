#!/usr/bin/env -S deno run
// svg_to_image.ts
// Created by raisercostin 2024-11-22
// Import Puppeteer from NPM
import puppeteer from 'npm:puppeteer';
import { parse } from 'https://deno.land/std@0.203.0/flags/mod.ts';

// Function to display help message
function showHelp() {
  console.log(`
Usage: deno run --allow-read --allow-write --allow-net --allow-env svg_to_image.ts [options] <input.svg>

Options:
  -h, --help        Show this help message
  -w, --width       Width of the output image in pixels (default: 800)
  -t, --height      Height of the output image in pixels (default: 600)
  -f, --format      Output image format: png or webp (default: png)
  -o, --output      Output file path (default: output.png or output.webp)
`);
  Deno.exit(0);
}

// Parse command-line arguments
const args = parse(Deno.args, {
  alias: {
    h: 'help',
    w: 'width',
    t: 'height',
    f: 'format',
    o: 'output',
  },
  default: {
    width: '800',
    height: '600',
    format: 'png',
  },
  boolean: ['help'],
  string: ['width', 'height', 'format', 'output'],
});

// Show help if requested or no input file provided
if (args.help || args._.length === 0) {
  showHelp();
}

// Extract arguments
const svgFilePath = args._[0] as string;
const width = parseInt(args.width);
const height = parseInt(args.height);
const format = args.format.toLowerCase() === 'webp' ? 'webp' : 'png';
const outputFilePath = args.output || `output.${format}`;

// Validate input SVG file existence
try {
  const fileInfo = await Deno.stat(svgFilePath);
  if (!fileInfo.isFile) {
    console.error(`Error: "${svgFilePath}" is not a valid file.`);
    Deno.exit(1);
  }
} catch (error) {
  console.error(`Error: SVG file "${svgFilePath}" does not exist.`);
  Deno.exit(1);
}

// Read the SVG content
const svgContent = await Deno.readTextFile(svgFilePath);

// Create an HTML template embedding the SVG
const htmlContent = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    body, html {
      margin: 0;
      padding: 0;
      width: ${width}px;
      height: ${height}px;
      overflow: hidden;
      display: flex;
      justify-content: center;
      align-items: center;
      background: transparent;
    }
    svg {
      width: 100%;
      height: 100%;
    }
  </style>
</head>
<body>
  ${svgContent}
</body>
</html>
`;

// Function to perform the SVG to Image conversion
async function convertSvgToImage() {
  console.log('Launching headless Chromium...');
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  });

  try {
    const page = await browser.newPage();

    // Set viewport dimensions
    await page.setViewport({ width, height });

    console.log('Loading SVG content...');
    // Load the HTML content containing the SVG
    await page.setContent(htmlContent, { waitUntil: 'networkidle0' });

    // Ensure the SVG element is rendered
    await page.waitForSelector('svg');

    console.log(`Capturing screenshot as "${outputFilePath}" (${format.toUpperCase()})...`);
    // Capture screenshot
    await page.screenshot({
      path: outputFilePath,
      type: format as 'png' | 'webp',
      omitBackground: true, // Makes background transparent if supported
    });

    console.log(`Success: Converted "${svgFilePath}" to "${outputFilePath}".`);
  } catch (error) {
    console.error('Error during conversion:', error);
  } finally {
    await browser.close();
    console.log('Chromium browser closed.');
  }
}

// Execute the conversion
await convertSvgToImage();
