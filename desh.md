# Desh — A Deno Shell

## NAME

**Desh** — A TypeScript-based shell utility for scripting system commands using Deno.

## SYNOPSIS

Create scripts like:
```ts
#!/usr/bin/env -S deno run --allow-run --allow-read --allow-write
import { Shell } from "https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/desh.ts"
const { shell, env } = new Shell("info") //trace,debug,info,warn,error
const pod = await shell`kubectl get pods | grep my-service | head -n 2 | awk 'NR==2{ exit 1 } { print $1 }'`
await shell`kubectl exec -it ${pod} -- ls /tmp`
```

## DESCRIPTION

**Desh** enables you to write portable, readable, and debuggable scripts in TypeScript that behave like Bash but with structured control, interpolation, and full TypeScript support.

Each command is executed using a `shell\`\`` tagged template. It supports:

- 2025-04-12
  - Inline and multiline scripts
  - Shell-style variables and substitutions
  - Piped commands (`|`) with debugging support
  - Output capture and logging control

## PIPELINE SUPPORT

You can pipe output between commands:

```ts
await shell`kubectl get pods | grep my-service | awk '{print $1}'`
```

Intermediate outputs are only logged at `trace` level. Final output is returned and can be stored:

```ts
const pid = await shell`ps -ef | grep java | awk '{print $2}'`;
```

## DEBUGGING

Set `LogLevel` when creating the shell:

```ts
const { shell } = new Shell("trace"); // "debug", "info", etc.
```

Log output includes:

- Executed command with line number
- Command output (stdout/stderr) with prefix
- Errors with details
- Optional raw script line in `debug`

## EXAMPLES

### Commit multiple files in order

```ts
const files = (await shell`ls test*.ts | sort -V`).split("\n");

for (const file of files) {
  await shell`
    cp ${file} test-to-commit.ts
    git add test-to-commit.ts
    git commit -m "Add ${file}"
  `;
}
```

## AUTHOR

Written by [Costin Grigore](https://github.com/raisercostin)

## SEE ALSO

- [Deno](https://deno.land/)
- [Cliffy CLI Parser](https://cliffy.io/)
- Your local shell 😄
- Alternatives/Inspiration
  - zx - https://github.com/google/zx - https://google.github.io/zx/
    - Unfortunately this is dependent of having a bash subsystem existing so it doesn't work directly in windows cmd
