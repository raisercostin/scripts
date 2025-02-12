#!/usr/bin/env -S deno run --allow-net --allow-env
//https://developers.cloudflare.com/api/
//https://github.com/cloudflare/cloudflare-typescript/tree/main
//https://developers.cloudflare.com/dns/manage-dns-records/how-to/batch-record-changes/

import { Cloudflare } from "https://esm.sh/cloudflare";
import { BatchPatch, BatchPatchParam, BatchPutParam, RecordBatchParams, RecordBatchResponse, RecordParam, RecordResponse, TXTRecordParam } from "https://esm.sh/cloudflare@4.0.0/resources/dns/records.d.ts";
import { DNSRecord } from "https://esm.sh/cloudflare@4.0.0/resources/email-routing/dns.d.ts";
import process from "node:process";
import { Command, EnumType } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";

const cf = new Cloudflare({
  //apiEmail: "raisercostin@gmail.com",
  apiToken: process.env['CLOUDFLARE_API_KEY'],
});

// function async list(): any {
//   // Automatically fetches more pages as needed.
//   for await (const recordResponse of client.dns.records.list({ zone_id: '023e105f4ecef8ad9ca31a8372d0c353' })) {
//     console.log(recordResponse);
//   }
// }
// const recordResponse = await client.dns.records.create({ zone_id: '023e105f4ecef8ad9ca31a8372d0c353' });

// console.log(recordResponse);

// list()
//const zones = await cf.zones.list();
//console.log(zones);
//console.log(zones.result[0].id);

// const response = await fetch("https://api.cloudflare.com/client/v4/zones", {
//   headers: {
//     "Authorization": "Bearer "+process.env['CLOUDFLARE_API_KEY'],
//     "Content-Type": "application/json",
//   },
// });
//const data:Zone = await response.json();
//console.log(data);

// // Automatically fetches more pages as needed.
// const all = await cf.dns.records.list({ zone_id: 'e84cf0e05950a569d68531b99a74bc86'})
// console.table(all.result);
// // for await (const recordResponse of all) {
// //   console.table(recordResponse);
// // }


const API_BASE = "https://api.cloudflare.com/client/v4";
const CLOUD_AUTH_TOKEN = process.env['CLOUDFLARE_API_KEY'];

//lit all zones
async function listZones() {
  const response = await fetch(`${API_BASE}/zones`, {
    method: "GET",
    headers: {
      "Authorization": `Bearer ${CLOUD_AUTH_TOKEN}`,
      "Content-Type": "application/json",
    },
  });

  const data = await response.json();
  //console.table(data.result);
  //console.log(Object.keys(data.result[0]));
  //console.log(data.result[0])
  //console.table(data.result,["id","name"]);
  console.log(data.result)
}

async function getZoneId(domain: string): Promise<string> {
  const response = await fetch(`${API_BASE}/zones?name=${domain}`, {
    method: "GET",
    headers: {
      "Authorization": `Bearer ${CLOUD_AUTH_TOKEN}`,
      "Content-Type": "application/json",
    },
  });
  return response.json().then(data => data.result[0].id);
}

async function getDnsRecords(zoneId: string): Promise<RecordResponse[]> {
  const response = await fetch(`${API_BASE}/zones/${zoneId}/dns_records`, {
    method: "GET",
    headers: {
      "Authorization": `Bearer ${CLOUD_AUTH_TOKEN}`,
      "Content-Type": "application/json",
    },
  });
  return response.json().then(x => x.result as unknown as RecordResponse[]);
}

async function addDnsRecords(params: RecordBatchParams): Promise<RecordBatchResponse> {
  const all: string = JSON.stringify(params);
  const zoneId = params.zone_id;
  (params as any).zone_id = null;
  console.log(all)
  return (await fetch(`${API_BASE}/zones/${zoneId}/dns_records/batch`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${CLOUD_AUTH_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: all
  })).json() as unknown as RecordBatchResponse;
}

async function main() {
  const zoneId = await getZoneId(DOMAIN_NAME);
  if (!zoneId) return;

  console.log("Zone ID:", zoneId);

  const dnsRecords = await getDnsRecords(zoneId);
  console.log("DNS Records:", dnsRecords);
  console.table(dnsRecords, ["id", "name", "type", "content"]);
}

//main()
//listZones()
//console.log(await getZoneId("cultural-mobility.com"))
//getDnsRecords("e84cf0e05950a569d68531b99a74bc86")

//e84cf0e05950a569d68531b99a74bc86
async function createOrUpdateMigaduConfig(domain: string, hostedEmailVerify: string): Promise<RecordBatchParams> {
  const zoneId = await getZoneId(domain);
  const old: RecordResponse[] = await getDnsRecords(zoneId)
  const used = new Set<string>()
  console.table(old, ["id", "name", "type", "content"]);
  const newRecords = await createMigaduConfig(domain, hostedEmailVerify)
  const updated = newRecords.posts?.map(newRecord2 => {
    const newRecord = newRecord2 as BatchPutParam
    if (!newRecord?.name?.endsWith(domain)) {
      newRecord.name = newRecord.name ? newRecord.name + "." + domain : domain
    }
    const oldRecord = old.find(r => r.name === newRecord.name && r.type === newRecord.type && !used.has(r.id))
    if (oldRecord?.id) {
      used.add(oldRecord.id)
      newRecord.id = oldRecord.id
    } else {
      console.log("couldn't find newRecord: ", newRecord)
    }
    if (oldRecord === newRecord)
      return null
    return newRecord
  }).filter(x => x != null) as BatchPutParam[]
  return { zone_id: zoneId, puts: updated }
}
async function createMigaduConfig(domain: string, hostedEmailVerify: string): Promise<RecordBatchParams> {
  const DOMAIN_NAME = domain;
  const automatic = 1
  const migaduRecords: BatchPutParam[] =
    [
      // Verification TXT Record
      // We must uniquely verify your domain ownership to prevent hijacking. Please add and keep the following TXT record at all times.
      {
        type: "TXT", name: DOMAIN_NAME, content: `"hosted-email-verify=${hostedEmailVerify}"`, ttl: automatic,
        proxied: false,
        comment: "Migadu - Verification TXT Record - Domain verification record"
      },

      //   Mail Exchange (MX) Records
      // Mail exchanger records (MX) route email destined for your domain.
      // Please remove any pre-existing MX records first and then add the MX records as below. Unless you have a strong reason not to do so, please add them all keeping the given prioritization - aspmx1 being of lower MX priority than aspmx2.
      {
        type: "MX", name: DOMAIN_NAME, content: "aspmx1.migadu.com", ttl: automatic, priority: 10,
        proxied: false,
        comment: "Migadu - Mail Exchange (MX) Record - Primary MX host"
      },
      {
        type: "MX", name: DOMAIN_NAME, content: "aspmx2.migadu.com", ttl: automatic, priority: 20,
        proxied: false,
        comment: "Migadu - Mail Exchange (MX) Record - Secondary MX host"
      },

      //   DKIM+ARC Key Records
      // DomainKeys Identified Mail (DKIM) is an email authentication method designed to prove integrity of email addresses and message through a public cryptography mechanism.
      // Authenticated Received Chain (ARC) is very similar, but it signs third party messages passing through, such as forwarded mails. We utilize the same keys for DKIM and ARC.
      // Only one key pair is used at any given time, while the other two are used for automated key rotations on our end.
      // IMPORTANT: Your DNS may not need the trailing dot after the CNAME destination value. Please try with a trailing dot first anyway.
      // IMPORTANT: Your DNS may not allow an underscore in the CNAME destination (...cultural-mobility.com._domainkey...). While that is a DNS specs violation in your DNS provider's editor, you can omit it (...cultural-mobility.com.domainkey...).
      // Please add all records as given below.
      {
        type: "CNAME", name: `key1._domainkey.${DOMAIN_NAME}`, content: `key1.${DOMAIN_NAME}._domainkey.migadu.com.`, ttl: automatic,
        proxied: false,
        comment: "Migadu - DKIM+ARC Key Record - Primary key"
      },
      {
        type: "CNAME", name: `key2._domainkey.${DOMAIN_NAME}`, content: `key2.${DOMAIN_NAME}._domainkey.migadu.com.`, ttl: automatic,
        proxied: false,
        comment: "Migadu - DKIM+ARC Key Record - Secondary key"
      },
      {
        type: "CNAME", name: `key3._domainkey.${DOMAIN_NAME}`, content: `key3.${DOMAIN_NAME}._domainkey.migadu.com.`, ttl: automatic,
        proxied: false,
        comment: "Migadu - DKIM+ARC Key Record - Tertiary key"
      },

      //   SPF Record
      // Sender Policy Framework (SPF) is an email authentication method designed to prevent email forging. SPF record (of type TXT), defined in the DNS, limits acceptable senders for your domain to listed IP addresses. However, SPF by itself does not take any action. In combination with a valid DMARC policy, SPF is a powerful mechanism.
      // IMPORTANT: Please do not use the DNS record type SPF which has been obsoleted by RFC 7208 but is still offered by some DNS editors. Make sure you pick the TXT type.
      // IMPORTANT: You cannot have multiple SPF policies. If you already have an SPF policy in place which you want to keep, add just the include:spf.migadu.com part to your existing policy, preferably right after v=spf1.
      // Please add the following TXT record. Your DNS may require that you add quotes around the TXT value containing spaces.
      {
        type: "TXT", name: DOMAIN_NAME, content: `"v=spf1 include:spf.migadu.com -all"`, ttl: automatic,
        proxied: false,
        comment: "Migadu - SPF Record - Defines allowed senders for domain"
      },

      //   DMARC Records
      // This is an optional, but recommended configuration.
      // Domain-based Message Authentication, Reporting and Conformance (DMARC) is an email authentication protocol which extends DKIM and SPF. It instructs the message receiver what action to take if neither of those authentication methods passes – such as to reject the message or quarantine it (classify as junk).
      // We strongly recommend adding initial DMARC policy to quarantine non aligned messages, which can then be tightened as reject later on.
      // IMPORTANT: If you are using additional email services on your domain such as newsletter senders, you should configure DKIM for those services and include their sending IP addresses into your SPF policy. Not doing so but using a reject DMARC policy, messages sent from services other than Migadu will be rejected by recipients.
      // DMARC may also conflict with mailing lists. We recommend not using the reject policy right away if you participate in mailing lists.
      // Please add the following TXT record. Your DNS may require that you add quotes around the TXT value containing spaces.
      {
        type: "TXT", name: "_dmarc", content: `"v=DMARC1; p=quarantine;"`, ttl: automatic,
        proxied: false,
        comment: "Migadu - DMARC Record - Policy to quarantine unaligned messages"
      },

      // Subdomain Addressing
      // This is an optional configuration.
      // Subdomain addressing is an alternative to plus-addressing (e.g. john+anything@cultural-mobility.com). In subdomain addressing, all messages for local part subdomains are sent to their respective mailboxes. For example anything@john.cultural-mobility.com is automatically expanded to john@cultural-mobility.com.
      // To use subdomain addressing on your mailboxes, add the wildcard MX records given below. Your DNS may or may not support DNS wildcard records.
      // Please note that subdomain addressing is not a standard behavior in email systems. As such it may not be easily portable between providers.
      {
        type: "MX", name: "*", content: "aspmx1.migadu.com", ttl: 3600, priority: 10,
        proxied: false,
        comment: "Migadu - Subdomain Addressing MX Record - Primary MX host for subdomains"
      },
      {
        type: "MX", name: "*", content: "aspmx2.migadu.com", ttl: 3600, priority: 20,
        proxied: false,
        comment: "Migadu - Subdomain Addressing MX Record - Secondary MX host for subdomains"
      },

      //   Autoconfig / Autodiscovery Records
      // This is an optional configuration.
      // Some email clients make configuring of mailbox connections a bit easier by relying on automatic detection of parameters. This usually does not work well except for the simplest cases. There are some records you can set that may give email clients a hint and starting hand to your users.
      // If Mozilla Thunderbird is your preferred email client, please create the record below in your DNS.
      // IMPORTANT: Your DNS may not need the trailing dot after the CNAME destination value. Please try with a dot first anyway.
      {
        type: "CNAME", name: "autoconfig", content: "autoconfig.migadu.com.", ttl: automatic,
        proxied: false,
        comment: "Migadu - Autoconfig - Thunderbird autoconfig mechanism"
      },

      // Latest versions of Microsoft Outlook use a different mechanism for detecting server settings.
      {
        type: "SRV", name: "_autodiscover._tcp", data: { port: 443, target: "autodiscover.migadu.com", priority: 0, weight: 1 }, ttl: automatic,
        proxied: false,
        comment: "Migadu - Autodiscover - Outlook autodiscovery mechanism"
      },

      // You may also want to add the generic service discovery records, as given below. Email clients are increasingly relying on them.
      {
        type: "SRV", name: "_submissions._tcp", data: { port: 465, target: "smtp.migadu.com", priority: 0, weight: 1 }, ttl: automatic,
        proxied: false,
        comment: "Migadu - SMTP Outgoing - SMTPS (SMTP with SSL) for sending email"
      },
      {
        type: "SRV", name: "_imaps._tcp", data: { port: 993, target: "imap.migadu.com", priority: 0, weight: 1 }, ttl: automatic,
        proxied: false,
        comment: "Migadu - IMAP Incoming - Secure IMAP for receiving email"
      },
      {
        type: "SRV", name: "_pop3s._tcp", data: { port: 995, target: "pop.migadu.com", priority: 0, weight: 1 }, ttl: automatic,
        proxied: false,
        comment: "Migadu - POP3 Incoming - Secure POP3 for receiving email"
      }
      // IMPORTANT: You may have noticed we used port 465 (SMTPS) for submission instead of 587, the StartTLS enabled SMTP port. We do support both ports, but StartTLS is getting obsoleted by RFC 8314 soon.
    ]

  const zoneId = await getZoneId(domain)
  return { zone_id: zoneId, posts: migaduRecords }
}
//console.table(await getDnsRecords(await getZoneId("civiz.org")), ["id", "name", "type", "content"]);
//console.log(await createMigaduConfig("agileism.org","o6wf1gfj"))
// console.log(await addDnsRecords(await createOrUpdateMigaduConfig("civiz.org", "o6wf1gfj")))
// console.table(await getDnsRecords(await getZoneId("civiz.org")), ["id", "name", "type", "content"]);


type Invalid<T> = ['Needs to be all of', T];
const arrayOfAll =
  <T>() =>
    <U extends T[]>(
      ...array: U & ([T] extends [U[number]] ? unknown : Invalid<T>[])
    ) =>
      array;

const columns: string[] = arrayOfAll<keyof RecordResponse>()(
  "id", "name", "type", "content", "created_on", "meta", "modified_on", "proxiable", "comment_modified_on", "tags_modified_on", "comment", "proxied", "settings", "tags", "ttl"
);
columns.push("*");

await new Command()
  .name("cloudflare")
  .version("0.1")
  .description("Manage cloudflare dns records")
  .globalType("format", new EnumType(["table", "json"]))
  .globalType("column", new EnumType(columns))
  .command("list <domain:string>", "List all zones")
  .env("CLOUDFLARE_API_KEY=<cloudflareApiKey:string>", "Cloudflare api key must be configured.")
  .option("-c, --column [column:column]", "Comma separated list of columns", { collect: true })
  .option("-cs, --columns [columns:string]", "Comma separated list of columns")
  .option("-f, --format [format:format]", "Output format", { default: "table" })
  .action(async (options, ...args) =>
    options.format === "json" ?
      console.log(JSON.stringify(await getDnsRecords(await getZoneId(args[0])), null, 2)) :
      console.table(await getDnsRecords(await getZoneId(args[0])),
        (options?.column?.length ?? 0) > 0 ? options.column :
          options?.columns ? options.columns.toString().split(",") :
            ["id", "name", "type", "content"]))
  .parse(Deno.args);
