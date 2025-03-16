#!/usr/bin/env -S deno run --allow-net --allow-read --allow-write

// https://medium.com/@vadym.ruchka/creating-a-telegram-bot-for-deno-deploy-bbcb3fd60ef0

import { Command, EnumType } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { Client } from "jsr:@mtkruto/mtkruto";

await new Command()
  .name("telegram-online-users")
  .version("1.0.0")
  .description("Fetches online users from a Telegram group using MTProto.")
  .option("-i, --id <id:number>", "Your Telegram API ID", { required: true })
  .option("--hash <hash:string>", "Your Telegram API Hash", { required: true })
  //.option("-p, --phone <phone:string>", "Your Telegram Phone Number", { required: true })
  .option("-g, --group <group:string>", "Telegram Group/Channel Username", { required: true })
  .action(async (options) => {
    const { id, hash, phone, group } = options;

    const client = new Client({
      apiId: id,
      apiHash: hash
    });

    console.log("Connecting to Telegram...");
    await client.connect();

    // Authenticate with a one-time password
    await client.auth({
      phoneCode: async () => {
        console.log("Enter the code sent to your Telegram:");
        return prompt()!;
      },
    });

    console.log("Connected!");

    try {
      const chat = await client.call("channels.getParticipants", {
        channel: group,
        filter: "recent",
        offset: 0,
        limit: 100,
      });

      const onlineUsers = chat.users.filter((user: any) => user.status?.class_name === "UserStatusOnline");

      console.log(`Online Users in ${group}:`);
      onlineUsers.forEach((user: any) => {
        console.log(`- ${user.username || `${user.first_name} ${user.last_name}`}`);
      });

      await client.disconnect();
    } catch (error) {
      console.error("Error fetching users:", error);
    }
  })
  .parse(Deno.args);
