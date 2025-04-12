#!/usr/bin/env -S deno run --allow-net --allow-read --allow-write

// https://medium.com/@vadym.ruchka/creating-a-telegram-bot-for-deno-deploy-bbcb3fd60ef0

import { X509Certificate } from "node:crypto";
import { Command, EnumType } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { Client, StorageLocalStorage } from "jsr:@mtkruto/mtkruto";

// CLI Command Setup
await new Command()
  .name("telegram-online-users")
  .version("1.0.0")
  .description("Fetches online users from a Telegram group using MTProto.")
  .option("-i, --id <id:number>", "Your Telegram API ID", { required: true })
  .option("--hash <hash:string>", "Your Telegram API Hash", { required: true })
  .option("-p, --phone <phone:string>", "Your Telegram Phone Number", { required: true })
  .option("-g, --group <group:string>", "Telegram Group/Channel Username", { required: false })
  .action(async (options) => {
    const { id, hash, phone, group } = options;

    const client = new Client({
      storage: new StorageLocalStorage("telegram_session"),
      apiId: id,
      apiHash: hash,
    });

    console.log("Connecting to Telegram...");
    await client.start({
      phone: () => phone,
      code: () => {
        console.log("Enter the code sent to your Telegram:");
        return prompt()!;
      },
      password: () => {
        console.log("Enter your Telegram password (if applicable):");
        return prompt()!;
      },
    });

    console.log("USER_BOT ONLINE!");
    //const chats = await getAllChats(client, group);
    // if (group)
    //   console.log("Your Groups & Channels:", await client.getChat(group));

    const chats = await client.getChats();
    console.log(chats.filter(x => x.chat.title?.includes(group)).map(x => x.chat));

    const statuses: { _: "contactStatus"; user_id: bigint; status: any }[] =
      await client.invoke({ _: "contacts.getStatuses" });

    // statuses.forEach(x=>console.log(x))

    //console.log("statuses", statuses);//.full_user.status; // Returns UserStatus (Online, Offline, etc.)
    const all = new Map(statuses.map((status) => [status.user_id, status.status]));
    // console.log("all:",all)

    const users = await client.getChatMembers(-1001685401232);
    const users2 = users.map(x => ({ ...x, status: all.get(BigInt(x.user.id)) }))
    //console.log(users2);
    console.table(users2.map(x => ({
      id: x.user.id,
      username: x.user.username,
      firstName: x.user.firstName,
      lastName: x.user.lastName,
      status: x.status?._, // Status type
      wasOnline: new Date(x.status?.was_online*1000) ?? "N/A", // Convert timestamp to date
      byMe: x.status?.by_me ?? false // Whether the status is reported by the bot itself
    })));

    // const result = await client.invoke({
    //   _: "users.getFullUser",
    //   id: { _: "inputUser", user_id: 554918477n },
    // });

    // console.log("user", result);//.full_user.status; // Returns UserStatus (Online, Offline, etc.)



    // // Listen for new messages
    // client.on("updateNewMessage", async (update: any) => {
    //   if (!update.message.peerId?.channelId) return;

    //   const chatId = update.message.peerId.channelId;
    //   console.log(`New message detected in chat: ${chatId}`);

    //   // Fetch participants
    //   try {
    //     const result = await client.call("channels.getParticipants", {
    //       channel: chatId,
    //       filter: { _: "channelParticipantsRecent" },
    //       offset: 0,
    //       limit: 200,
    //       hash: 0,
    //     });

    //     const onlineUsers = result.users.filter((user: any) => user.status?.className === "UserStatusOnline");

    //     console.log(`Online Users in ${group}:`);
    //     onlineUsers.forEach((user: any) => {
    //       console.log(`- ${user.username || `${user.firstName} ${user.lastName}`}`);
    //     });
    //   } catch (error) {
    //     console.error("Error fetching users:", error);
    //   }
    // });

    // await new Promise(() => { }); // Keep script running
    //client.stopPoll
  })
  .parse(Deno.args);

async function getAllChats(client: Client) {
  return await client.getChat("raisercostin");
}

async function getAllUsers(client: Client, group: number) {
  const result = await client.invoke("channels.getParticipants", {
    channel: group,
    filter: { _: "channelParticipantsSearch", q: "" },
    offset: 0,
    limit: 200,
    hash: 0,
  });

  return result.users.map((user: any) => ({
    id: user.id,
    username: user.username || `${user.firstName} ${user.lastName}`,
  }));
}
