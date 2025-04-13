# Deno Utility Scripts

## Cloudflare DNS config: list/backup/restore/reconfig

- also configure email for migado.com

```shell
## install
deno install --global -f --allow-net --allow-env --name cloudflare https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/cloudflare.ts

## help
cloudflare --help
cloudflare list --help
## List domains managed by cloudflare
cloudflare domains
## list dns records
cloudflare list civiz.org
cloudflare list civiz.org --format json
```

### Full sample

```shell
## install
deno install --global -f --allow-net --allow-env --name cloudflare https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/cloudflare.ts

cloudflare --help
λ deno --allow-env --allow-net cloudflare.ts --help

  Usage:   cloudflare
  Version: 0.1

  Description:

    Manage cloudflare dns records

  Options:

    -h, --help                 - Show this help.
    -V, --version              - Show the version number for this program.
    -c, --column    [column]   - Comma separated list of columns            (Values: "id", "name", "type", "content", "created_on",
                                                                            "meta", "modified_on", "proxiable",
                                                                            "comment_modified_on", "tags_modified_on", "comment",
                                                                            "proxied", "settings", "tags", "ttl", "*")
    -cs, --columns  [columns]  - Comma separated list of columns
    -f, --format    [format]   - Output format                              (Default: "table", Values: "table", "json")

  Commands:

    domains            - List all domains
    list     <domain>  - List all DNS records

  Environment variables:

    CLOUDFLARE_API_KEY  <cloudflareApiKey>  - Cloudflare api key must be configured.

## help
cloudflare --help
cloudflare list --help

## List domains managed by cloudflare
cloudflare domains
┌───────┬─────────────────────────┬──────────┬────────┬────────┬──────────────────┬──────────────────────────────┐
│ (idx) │ name                    │ status   │ paused │ type   │ development_mode │ original_registrar           │
├───────┼─────────────────────────┼──────────┼────────┼────────┼──────────────────┼──────────────────────────────┤
│     0 │ "civiz.org"             │ "active" │ false  │ "full" │ 0                │ "enom, llc (id: 48)"         │
│     1 │ "foo.com"               │ "active" │ false  │ "full" │ 0                │ "namecheap, inc. (id: 1068)" │
│     2 │ "bar.org"               │ "active" │ false  │ "full" │ 0                │ null                         │
│     3 │ "bar.org              " │ "active" │ false  │ "full" │ 0                │ "ionos se (id: 83)"          │
└───────┴─────────────────────────┴──────────┴────────┴────────┴──────────────────┴──────────────────────────────┘

## list dns records
cloudflare list civiz.org
┌───────┬────────────────────────────────────┬────────────────────────────────┬─────────┬────────────────────────────────────────┐
│ (idx) │ id                                 │ name                           │ type    │ content                                │
├───────┼────────────────────────────────────┼────────────────────────────────┼─────────┼────────────────────────────────────────┤
│     0 │ "bc467a3b323c27b383404febb6865b09" │ "autoconfig.civiz.org"         │ "CNAME" │ "autoconfig.migadu.com"                │
│     1 │ "c0cfd6c2f629b036a633393d5d6c99cd" │ "key1._domainkey.civiz.org"    │ "CNAME" │ "key1.civiz.org._domainkey.migadu.com" │
│     2 │ "24a8488c8bcdc3cba6073ea1b2dd04e2" │ "key2._domainkey.civiz.org"    │ "CNAME" │ "key2.civiz.org._domainkey.migadu.com" │
│     3 │ "ffea0589b96e7cb5f5468b041877bd05" │ "key3._domainkey.civiz.org"    │ "CNAME" │ "key3.civiz.org._domainkey.migadu.com" │
│     4 │ "0c0734542a2373a33cf3231b4d2b23d9" │ "*.civiz.org"                  │ "MX"    │ "aspmx1.migadu.com"                    │
│     5 │ "52255ff1ec44ec9bec60a93f8a071506" │ "*.civiz.org"                  │ "MX"    │ "aspmx2.migadu.com"                    │
│     6 │ "4c7e503824d446b5bf24c87dde3c296c" │ "civiz.org"                    │ "MX"    │ "aspmx1.migadu.com"                    │
│     7 │ "bd74e03031e4420be340b48fd5a43426" │ "civiz.org"                    │ "MX"    │ "aspmx2.migadu.com"                    │
│     8 │ "6f95c74ff517952c520563dd1dd32bd3" │ "_autodiscover._tcp.civiz.org" │ "SRV"   │ "1 443 autodiscover.migadu.com"        │
│     9 │ "e0645e66af73587fa3586a2b12bc32cb" │ "_imaps._tcp.civiz.org"        │ "SRV"   │ "1 993 imap.migadu.com"                │
│    10 │ "1d41ce5ce2d21222f9e6fed0ea4e2a8b" │ "_pop3s._tcp.civiz.org"        │ "SRV"   │ "1 995 pop.migadu.com"                 │
│    11 │ "7e97fb2bb8056dcdc659bb8fe0330430" │ "_submissions._tcp.civiz.org"  │ "SRV"   │ "1 465 smtp.migadu.com"                │
│    12 │ "96935fe90254a8a671495a7a0e462e12" │ "civiz.org"                    │ "TXT"   │ '"hosted-email-verify=o6wf1gfj"'       │
│    13 │ "9aa7aa7b9646015bbca82be5d7837eed" │ "civiz.org"                    │ "TXT"   │ '"v=spf1 include:spf.migadu.com -all"' │
│    14 │ "60a975285bd2d98fce708e795ae16a17" │ "_dmarc.civiz.org"             │ "TXT"   │ '"v=DMARC1; p=quarantine;"'            │
└───────┴────────────────────────────────────┴────────────────────────────────┴─────────┴────────────────────────────────────────┘

cloudflare list civiz.org --format json
[
  {
    "id": "bc467a3b323c27b383404febb6865b09",
    "name": "autoconfig.civiz.org",
    "type": "CNAME",
    "content": "autoconfig.migadu.com",
    "proxiable": true,
    "proxied": false,
    "ttl": 1,
    "settings": {
      "flatten_cname": false
    },
    "meta": {},
    "comment": "Migadu - Autoconfig - Thunderbird autoconfig mechanism",
    "tags": [],
    "created_on": "2025-02-11T11:44:27.355702Z",
    "modified_on": "2025-02-11T11:44:27.355702Z",
    "comment_modified_on": "2025-02-11T11:44:27.355702Z"
  },
]
```
