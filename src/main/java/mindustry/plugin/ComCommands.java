package mindustry.plugin;

import arc.files.Fi;
import arc.math.Mathf;
import arc.util.Timer;
import mindustry.maps.Map;

import mindustry.entities.type.Player;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Call;
import mindustry.plugin.datas.PlayerData;
import mindustry.plugin.discordcommands.RoleRestrictedCommand;
import mindustry.world.blocks.storage.CoreBlock.CoreEntity;
import mindustry.world.modules.ItemModule;
import mindustry.plugin.discordcommands.Command;
import mindustry.plugin.discordcommands.Context;
import mindustry.plugin.discordcommands.DiscordCommands;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static mindustry.Vars.*;
import static mindustry.plugin.Utils.*;
import static mindustry.plugin.ioMain.getTextChannel;

public class ComCommands {
    public void registerCommands(DiscordCommands handler) {
        handler.registerCommand(new Command("chat") {
            {
                help = "<message> Sends a message to in-game chat.";
            }
            public void run(Context ctx) {
                if(ctx.event.isPrivateMessage()) return;

                EmbedBuilder eb = new EmbedBuilder();
                ctx.message = escapeCharacters(ctx.message);
                if (ctx.message.length() < chatMessageMaxSize) {
                    Call.sendMessage("[sky]" + ctx.author.getName() + " @discord >[] " + ctx.message);
                    eb.setTitle("Command executed");
                    eb.setDescription("Your message was sent successfully..");
                    ctx.channel.sendMessage(eb);
                } else{
                    ctx.reply("Message too big.");
                }
            }
        });
        handler.registerCommand(new Command("downloadmap") {
            {
                help = "<mapname/mapid> Preview and download a server map in a .msav file format.";
            }
            public void run(Context ctx) {
                if (ctx.args.length < 2) {
                    ctx.reply("Not enough arguments, use `%map <mapname/mapid>`".replace("%", ioMain.prefix));
                    return;
                }

                Map found = getMapBySelector(ctx.message.trim());
                if (found == null) {
                    ctx.reply("Map not found!");
                    return;
                }

                Fi mapFile = found.file;

                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle(escapeCharacters(found.name()))
                        .setDescription(escapeCharacters(found.description()))
                        .setAuthor(escapeCharacters(found.author()));
                // TODO: .setImage(mapPreviewImage)
                ctx.channel.sendMessage(embed, mapFile.file());
            }
        });
        handler.registerCommand(new Command("players") {
            {
                help = "Check who is online and their ids.";
            }
            public void run(Context ctx) {
                StringBuilder msg = new StringBuilder("**Players online: " + playerGroup.size() + "**\n```\n");
                for (Player player : playerGroup.all()) {
                    msg.append("· ").append(escapeCharacters(player.name)).append(" : ").append(player.id).append("\n");
                }
                msg.append("```");
                ctx.channel.sendMessage(msg.toString());
            }
        });
        handler.registerCommand(new Command("info") {
            {
                help = "Get basic server information.";
            }
            public void run(Context ctx) {
                try {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle(ioMain.serverName)
                            .addField("Players", String.valueOf(playerGroup.size()))
                            .addField("Map", world.getMap().name())
                            .addField("Wave", String.valueOf(state.wave))
                            .addField("Next wave in", Math.round(state.wavetime / 60) + " seconds.");

                    ctx.channel.sendMessage(eb);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                    ctx.reply("An error has occurred.");
                }
            }
        });
        handler.registerCommand(new Command("resinfo") {
            {
                help = "Check the amount of resources in the core.";
            }
            public void run(Context ctx) {
                if (!state.rules.waves) {
                    ctx.reply("Only available in survival mode!");
                    return;
                }
                // the normal player team is "sharded"
                TeamData data = state.teams.get(Team.sharded);
                //-- Items are shared between cores
                CoreEntity core = data.cores.first();
                ItemModule items = core.items;
                EmbedBuilder eb = new EmbedBuilder()
                        .setTitle("Resources in the core:");
                items.forEach((item, amount) -> eb.addInlineField(item.name, String.valueOf(amount)));
                ctx.channel.sendMessage(eb);
            }
        });

        handler.registerCommand(new Command("help") {
            {
                help = "Display all available commands and their usage.";
            }
            public void run(Context ctx) {
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("Public commands:");
                EmbedBuilder embed2 = new EmbedBuilder()
                        .setTitle("Restricted commands:");
                for(Command command : handler.getAllCommands()) {
                    if(command instanceof RoleRestrictedCommand) {
                        embed2.addField("**" + command.name + "**", command.help);
                    } else {
                        embed.addField("**" + command.name + "**", command.help);
                    }
                }
                ctx.channel.sendMessage(embed2);
                ctx.channel.sendMessage(embed);
            }
        });

        handler.registerCommand(new Command("redeem"){
            {
                help = "<name|id> Promote your in-game rank. [NOTE: Abusing this power and giving it to other players will result in a ban.]";
            }

            public void run(Context ctx) {
                CompletableFuture.runAsync(() -> {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = "";
                    if (ctx.args.length > 1) {
                        target = ctx.args[1];
                    }
                    List<Role> authorRoles = ctx.author.asUser().get().getRoles(ctx.event.getServer().get()); // javacord gay
                    List<String> roles = new ArrayList<>();
                    for (Role r : authorRoles) {
                        if (r != null) {
                            roles.add(r.getIdAsString());
                        }
                    }
                    if (target.length() > 0) {
                        int rank = 0;
                        for (String role : roles) {
                            if (rankRoles.containsKey(role)) {
                                if (rankRoles.get(role) > rank) {
                                    rank = rankRoles.get(role);
                                }
                            }
                        }
                        Player player = findPlayer(target);
                        if (player != null && rank > 0) {
                            PlayerData pd = getData(player.uuid);
                            if (pd != null) {
                                pd.rank = rank;
                                setData(player.uuid, pd);
                            }
                            eb.setTitle("Command executed successfully");
                            eb.setDescription("Promoted " + escapeCharacters(player.name) + " to " + escapeColorCodes(rankNames.get(rank).name) + ".");
                            ctx.channel.sendMessage(eb);
                            player.con.kick("Your rank was modified, please rejoin.", 0);
                        } else {
                            eb.setTitle("Command terminated");
                            eb.setDescription("Player not online or not found.");
                            ctx.channel.sendMessage(eb);
                        }
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Invalid arguments provided or no roles to redeem.");
                        ctx.channel.sendMessage(eb);
                    }
                });
            }

        });

        TextChannel warningsChannel = null;
        if (ioMain.data.has("warnings_chat_channel_id")) {
            warningsChannel = getTextChannel(ioMain.data.getString("warnings_chat_channel_id"));
        }

        TextChannel finalWarningsChannel = warningsChannel;
        handler.registerCommand(new Command("link") {
            {
                help = "<player/id> Link your discord account with your in-game account (receive special benefits)";
            }
            public void run(Context ctx) {
                CompletableFuture.runAsync(() -> {
                    if (ctx.event.isPrivateMessage()) return;
                    EmbedBuilder eb = new EmbedBuilder();
                    ctx.message = escapeCharacters(ctx.message);
                    Player p = findPlayer(ctx.message);
                    if (p != null) {
                        String uuid = p.uuid;
                        PlayerData pd = getData(uuid);

                        if (pd != null) {
                            if (pd.discordLink.length() > 0) {
                                eb.setTitle("That player already has an active discord link!");
                                eb.setDescription("If that's you, and you wish to change your discord link, use the /removelink command in-game.");
                                eb.setColor(Pals.warning);
                                ctx.channel.sendMessage(eb);
                            } else {
                                p.passPhrase = String.valueOf(Mathf.random(1000, 9999));
                                eb.setTitle("<a:loading:686693525907177519> Attempting link with " + escapeCharacters(p.name));
                                eb.addField("Link PIN", p.passPhrase);
                                eb.setDescription("Now, use the **/link " + p.passPhrase + "** command in game.");
                                CompletableFuture<Message> msg = ctx.channel.sendMessage(eb);
                                setData(uuid, pd);
                                Timer.schedule(() -> {
                                    PlayerData pd2 = getData(uuid);
                                    if (pd2 != null) {
                                        EmbedBuilder eb2 = new EmbedBuilder();
                                        if (p.passPhrase.equals("OK")) {
                                            pd2.discordLink = ctx.author.getIdAsString();
                                            pd2.verified = true;
                                            setData(p.uuid, pd2);
                                            eb2.setTitle(":white_check_mark: Link with " + escapeCharacters(p.name) + " successful!");
                                            eb2.setDescription("Thank you for linking your account.");
                                            eb2.setColor(Pals.success);
                                            msg.thenAccept(m -> {
                                                m.edit(eb2);
                                            });
                                            if (finalWarningsChannel != null) {
                                                EmbedBuilder eb3 = new EmbedBuilder();
                                                eb3.setTitle(ctx.author.getDiscriminatedName() + " <-> " + escapeCharacters(p.name) + " link successful.");
                                                eb3.addField("UUID", p.uuid);
                                                finalWarningsChannel.sendMessage(eb3);
                                            }
                                        } else {
                                            eb2.setTitle(":x: Link with " + escapeCharacters(p.name) + " failed.");
                                            eb2.setDescription("Timed out. You have 15 seconds to use the /link command in game.");
                                            eb2.setColor(Pals.error);
                                            msg.thenAccept(m -> {
                                                m.edit(eb2);
                                            });
                                        }
                                        setData(uuid, pd2);
                                    }
                                    p.passPhrase = "";
                                }, 15);
                                p.sendMessage("[#7289da]\uE848[#99aab5] A discord link was prompted to your account by " + ctx.author.getDiscriminatedName());
                                p.sendMessage("[#7289da]\uE848[#99aab5] If this is you, use the /link command with the provided PIN on discord.");
                            }
                        }
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Player " + ctx.message + " not found in " + ioMain.serverName + ".\nAre you using the correct prefix?");
                        eb.setColor(Pals.error);
                        ctx.channel.sendMessage(eb);
                    }
                });
            }
        });
    }
}
