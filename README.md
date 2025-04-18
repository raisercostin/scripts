# Scripts collection

- [adbsync.md](adbsync.md) - Sync your android apps in a local dir.
- [cloudflare.md](cloudflare.md) - Cloudflare DNS config: list/backup/restore/reconfig
- [desh.md](desh.md) - **Desh** — A TypeScript-based shell utility for scripting system commands using Deno.
- [svg2webp.md](svg2webp.md) - Svg to webp convertor
- [androidbackupextractor.java](androidbackupextractor.java) - android ab extractor to tar files
- [file server with rendering and editing](restfs.ts)
  `deno install --global -f --allow-env --allow-net --allow-run --allow-read --allow-write --name restfs` https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/restfs.ts`
  `restfs .`

Various one file utilities (in general) in:
- typescript - using deno, cliffy(for params) and [desh.ts](desh.ts) for shell with pipes in typescript
  - [adbsync.ts](adbsync.ts) - see deno, cliffy, desh in action
  - [cloudflare.ts](cloudflare.ts) - see deno, cliffy
- java - using jbang and picocli
  - [leetcodeTakeout.java](leetcodeTakeout.java)
  - [androidbackupextractor.java](androidbackupextractor.java)
