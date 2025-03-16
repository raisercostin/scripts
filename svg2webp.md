
## Usage

### Convert without install

```
deno --allow-all https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/svg2webp.ts file.svg --output file.webp --format webp --width 2000
```


### Install & Use

Install
```
deno install --global -f --allow-net --allow-env --name svg2webp https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/svg2webp.ts
```

Convert svg to webp

```
svg2webp file.svg --output file.webp --format webp --width 2000
```

