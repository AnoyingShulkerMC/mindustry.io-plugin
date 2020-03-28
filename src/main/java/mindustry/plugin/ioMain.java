package mindustry.plugin;

import java.awt.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import arc.math.Mathf;
import arc.util.*;
import arc.util.Timer;
import mindustry.content.*;
import mindustry.entities.Effects;
import mindustry.entities.type.BaseUnit;
import mindustry.graphics.Pal;
import mindustry.net.Administration;
import mindustry.plugin.datas.PersistentPlayerData;
import mindustry.plugin.datas.PlayerData;
import mindustry.plugin.discord.Loader;
import mindustry.plugin.utils.ContentHandler;
import mindustry.plugin.utils.MapRules;
import mindustry.plugin.utils.VoteSession;
import mindustry.world.Tile;
import org.json.JSONObject;
import org.json.JSONTokener;

import arc.Events;
import mindustry.Vars;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.gen.Call;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;

import static mindustry.Vars.*;
import static mindustry.plugin.utils.Funcs.*;
import static mindustry.plugin.discord.Loader.*;

public class ioMain extends Plugin {
    //register event handlers and create variables in the constructor
    public ioMain() {
        Loader.load();
        content.load();
        // display on screen messages
        float duration = 10f;
        int start = 450;
        int increment = 30;

        Timer.schedule(() -> {
            int currentInc = 0;
            for(String msg : onScreenMessages){
                Call.onInfoPopup(msg, duration, 20, 50, 20, start + currentInc, 0);
                currentInc = currentInc + increment;
            }
        }, 0, 10);

        Events.on(EventType.ServerLoadEvent.class, event -> {
            contentHandler = new ContentHandler();
            Log.info("content loaded");
        });

        // update every tick
        Events.on(EventType.Trigger.update, () -> {
            CompletableFuture.runAsync(() -> {
                playerGroup.all().forEach(player1 -> {
                    if (player1.doRainbow) {
                        // update rainbows
                        String playerNameUnmodified = player1.origName;
                        int hue = player1.hue;
                        if (hue < 360) {
                            hue = hue + 1;
                        } else {
                            hue = 0;
                        }

                        String hex = "#" + Integer.toHexString(Color.getHSBColor(hue / 360f, 1f, 1f).getRGB()).substring(2);
                        String[] c = playerNameUnmodified.split(" ", 2);
                        if (c.length > 1) player1.name = c[0] + " [" + hex + "]" + escapeColorCodes(c[1]);
                        else player1.name = "[" + hex + "]" + escapeColorCodes(c[0]);
                        player1.hue = hue;
                    }
                    if (player1.doTrail) {
                        String hex = Integer.toHexString(Color.getHSBColor(player1.hue / 360f, 1f, 1f).getRGB()).substring(2);

                        arc.graphics.Color c = arc.graphics.Color.valueOf(hex);
                        Call.onEffectReliable(player1.trailFx, player1.x, player1.y, (180 + player1.rotation) % 360, c);
                    }
                    if (player1.bt != null && player1.isShooting()) {
                        Call.createBullet(player1.bt, player1.getTeam(), player1.x, player1.y, player1.rotation, player1.sclVelocity, player1.sclLifetime);
                    }
                });
            });
        });

        Events.on(EventType.TapEvent.class, tapEvent -> {
            if(tapEvent.tile != null) {
                Player player = tapEvent.player;
                Tile t = tapEvent.tile;
                player.tapTile = t;
                if (player.inspector) {
                    Call.onEffectReliable(player.con, Fx.placeBlock, t.worldx(), t.worldy(), 0.75f, Pal.accent);
                    player.sendMessage("[orange]--[] [accent]tile [](" + t.x + ", " + t.y + ")[accent] block:[] " + ((t.block() == null || t.block() == Blocks.air) ? "[#545454]none" : t.block().name) + " [orange]--[]");
                    Tile.tileInfo info = t.info;
                    if (info.placedBy != null) {
                        String pBy = (player.isAdmin ? info.placedByUUID : info.placedBy);
                        player.sendMessage("[accent]last placed by:[] " + escapeColorCodes(pBy));
                    }
                    if (info.destroyedBy != null) {
                        String dBy = (player.isAdmin ? info.destroyedByUUID : info.destroyedBy);
                        player.sendMessage("[accent]last [scarlet]deconstructed[] by:[] " + escapeColorCodes(dBy));
                    }
                    if (t.block() == Blocks.air && info.wasHere != null){
                        player.sendMessage("[accent]block that was here:[] " + info.wasHere);
                    }
                    if (info.configuredBy != null) {
                        String cBy = (player.isAdmin ? info.configuredByUUID : info.configuredBy);
                        player.sendMessage("[accent]last configured by:[] " + escapeColorCodes(cBy));
                    }
                }
            }
        });

        // player joined
        Events.on(EventType.PlayerJoin.class, event -> {
            CompletableFuture.runAsync(() -> {
                Player player = event.player;
                player.origName = player.name;
                if (bannedNames.contains(player.name)) {
                    player.con.kick("[scarlet]Download the game from legitimate sources to join.\n[accent]https://anuke.itch.io/mindustry");
                    return;
                }

                PlayerData pd = getData(player.uuid);

                if (!playerDataGroup.containsKey(player.uuid)) {
                    PersistentPlayerData data = new PersistentPlayerData();
                    playerDataGroup.put(player.uuid, data);
                }

                if (pd != null) {
                    try {
                        if (pd.discordLink == null) {
                            pd.reprocess();
                            setData(player.uuid, pd);
                        }
                    } catch (Exception ignored) {
                        pd.reprocess();
                        setData(player.uuid, pd);
                    }
                    if (pd.banned || pd.bannedUntil > Instant.now().getEpochSecond()) {
                        player.con.kick("[scarlet]You are banned.[accent] Reason:\n" + pd.banReason);
                    }
                    int rank = pd.rank;
                    if(rank < minRank){
                        player.con.kick("[accent]This server is rank locked.[]\nYour rank: [accent]" + rankNames.get(rank).name + "[]\nRequired rank: [accent]" + rankNames.get(minRank).name);
                    }
                    switch (rank) { // apply new tag
                        case 0:
                            if (pd.discordLink.length() > 0) {
                                player.tag = "[#7289da]\uE848[] ";
                            } else {
                                player.tag = "";
                            }
                            break;
                        case 1:
                            Call.sendMessage("[sky]active player " + player.name + "[] joined the server!");
                            player.tag = rankNames.get(1).tag + " ";
                            break;
                        case 2:
                            Call.sendMessage("[#fcba03]regular player " + player.name + "[] joined the server!");
                            player.tag = rankNames.get(2).tag + " ";
                            break;
                        case 3:
                            Call.sendMessage("[scarlet]donator " + player.name + "[] joined the server!");
                            player.tag = rankNames.get(3).tag + " ";
                            break;
                        case 4:
                            Call.sendMessage("[orange]<[][white]io moderator[][orange]>[] " + player.name + "[] joined the server!");
                            player.tag = rankNames.get(4).tag + " ";
                            break;
                        case 5:
                            Call.sendMessage("[orange]<[][white]io admin[][orange]>[] " + player.name + "[] joined the server!");
                            player.tag = rankNames.get(5).tag + " ";
                            break;
                    }
                } else { // not in database
                    setData(player.uuid, new PlayerData(0));
                }

                if (welcomeMessage.length() > 0) {
                    Call.onInfoMessage(player.con, formatMessage(player, welcomeMessage));
                }

                if (verification) {
                    if (pd != null && !pd.verified) {
                        player.canInteract = false;
                        Log.info("Unverified player joined: " + player.name);
                        String url = "http://api.vpnblocker.net/v2/json/" + player.con.address + "/" + apiKey;
                        String pjson = ClientBuilder.newClient().target(url).request().accept(MediaType.APPLICATION_JSON).get(String.class);

                        JSONObject json = new JSONObject(new JSONTokener(pjson));
                        if (json.has("host-ip")) {
                            if (json.getBoolean("host-ip")) { // verification failed
                                Log.info("IP verification failed for: " + player.name);
                                Call.onInfoMessage(player.con, verificationMessage);
                                player.canInteract = false;
                            } else {
                                Log.info("IP verification success for: " + player.name);
                                pd.verified = true;
                                player.canInteract = true;
                                setData(player.uuid, pd);
                            }
                        } else { // site doesn't work for some reason  ?
                            pd.verified = true;
                            player.canInteract = true;
                            setData(player.uuid, pd);
                        }
                    }
                }
            });
        });

        // player built building
        Events.on(EventType.BlockBuildEndEvent.class, event -> {
            if (event.player == null) return;
            if (event.breaking) return;
            PlayerData pd = getData(event.player.uuid);
            PersistentPlayerData td = (playerDataGroup.getOrDefault(event.player.uuid, null));
            if (pd == null || td == null) return;
            if (event.tile.entity != null) {
                if (!activeRequirements.bannedBlocks.contains(event.tile.block())) {
                    td.bbIncrementor++;
                }
            }
        });

        Events.on(EventType.BuildSelectEvent.class, event -> {
            if(event.builder instanceof Player){
                if(event.tile != null){
                    Player player = (Player) event.builder;
                    if(!event.breaking){
                        event.tile.info.placedBy = player.name;
                        event.tile.info.placedByUUID = player.uuid;
                        event.tile.info.wasHere = (event.tile.block() != null ? event.tile.block().name : "[#545454]none");
                    } else{
                        event.tile.info.destroyedBy = player.name;
                        event.tile.info.destroyedByUUID = player.uuid;
                    }
                }
            }
        });

        Events.on(EventType.TapConfigEvent.class, event -> {
            if(event.tile != null & event.player != null){
                Tile.tileInfo info = event.tile.info;
                Player player = event.player;
                info.configuredBy = player.name;
                info.configuredByUUID = player.uuid;
            }
        });

        Events.on(EventType.GameOverEvent.class, () -> {
            CompletableFuture.runAsync(() -> {
                for (Player p : playerGroup.all()) {
                    PlayerData pd = getData(p.uuid);
                    if (pd != null) {
                        pd.gamesPlayed++;
                        setData(p.uuid, pd);
                        Call.onInfoToast(p.con, "[accent]+1 games played", 10);
                    }
                }
            });
        });

        Events.on(EventType.WorldLoadEvent.class, event -> {
            Timer.schedule(MapRules::run, 5); // idk
        });

        Events.on(EventType.ServerLoadEvent.class, event -> {
            // action filter
            Vars.netServer.admins.addActionFilter(action -> {
                Player player = action.player;
                if (player == null) return true;

                if (player.isAdmin) return true;
                if (!player.canInteract) return false;

                return action.type != Administration.ActionType.rotate;
            });
        });
    }

    //register commands that run on the server
    @Override
    public void registerServerCommands(CommandHandler handler){

    }

    //cooldown between votes
    int voteCooldown = 120 * 1;

    //register commands that player can invoke in-game
    @Override
    public void registerClientCommands(CommandHandler handler){
        if (api != null) {

            handler.<Player>register("inspector", "Toggle on tile inspector. (Grief detection)", (args, player) -> {
                player.inspector = !player.inspector;
                player.sendMessage("[accent]Tile inspector toggled.");
            });

            handler.<Player>register("rainbow", "[regular+] Give your username a rainbow animation", (args, player) -> {
                PlayerData pd = getData(player.uuid);
                if (pd != null && pd.rank >= 2) {
                    player.sendMessage("[sky]Rainbow effect toggled.");
                    player.doRainbow = !player.doRainbow;
                } else {
                    player.sendMessage(noPermissionMessage);
                }
            });

            handler.<Player>register("trail", "[regular+] Give your username an in-world trail animation.", (args, player) -> {
                PlayerData pd = getData(player.uuid);
                if (pd != null && pd.rank >= 2) {
                    player.sendMessage("[sky]Trail effect toggled.");
                    player.doTrail = !player.doTrail;
                } else {
                    player.sendMessage(noPermissionMessage);
                }
            });

            handler.<Player>register("trailfx", "<effect>", "[donator+] Customize your trail effect. (full fx list on #donator [pinned messages])", (args, player) -> {
                PlayerData pd = getData(player.uuid);
                String targetFx = args[0];
                if (pd != null && pd.rank >= 3) {
                    if(targetFx.equals("dynamicExplosion") || targetFx.equals("dropItem") || targetFx.toLowerCase().contains("block") || targetFx.equals("shieldBreak") || targetFx.equals("incendTrail") || targetFx.equals("missileTrail")){
                        player.sendMessage("[scarlet]This effect is disabled.");
                        return;
                    }
                    try {
                        Field field = Fx.class.getDeclaredField(targetFx);
                        Effects.Effect effect = (Effects.Effect)field.get(null);
                        if (effect != null) {
                            player.trailFx = effect;
                        } else{
                            player.sendMessage("[scarlet]No such effect found. View full list of effects on #donator channel in pinned messages.");
                        }
                        player.sendMessage("[sky]Trail effect changed to " + targetFx + ".");
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {
                        player.sendMessage("[scarlet]No such effect found. View full list of effects on #donator channel in pinned messages.");
                    }
                } else {
                    player.sendMessage(noPermissionMessage);
                }
            });

            handler.<Player>register("draugpet", "[active+] Spawn a draug mining drone for your team (disabled on pvp)", (args, player) -> {
                if(!state.rules.pvp || player.isAdmin) {
                    PlayerData pd = getData(player.uuid);
                    if (pd != null && pd.rank >= 1) {
                        PersistentPlayerData tdata = playerDataGroup.get(player.uuid);
                        if (tdata == null) return;
                        if (tdata.draugPets.size < pd.rank || player.isAdmin) {
                            BaseUnit baseUnit = UnitTypes.draug.create(player.getTeam());
                            baseUnit.set(player.getX(), player.getY());
                            baseUnit.add();
                            tdata.draugPets.add(baseUnit);
                            Call.sendMessage(player.name + "[#b177fc] spawned in a draug pet! " + tdata.draugPets.size + "/" + pd.rank + " spawned.");
                        } else {
                            player.sendMessage("[#b177fc]You already have " + pd.rank + " draug pets active!");
                        }
                    } else {
                        player.sendMessage(noPermissionMessage);
                    }
                } else {
                    player.sendMessage("[scarlet]This command is disabled on pvp.");
                }
            });

            handler.<Player>register("spawn", "[active+]Skip the core spawning stage and spawn instantly.", (args, player) -> {
                if(!state.rules.pvp || player.isAdmin) {
                    PlayerData pd = getData(player.uuid);
                    if (pd != null && pd.rank >= 1) {
                        player.onRespawn(player.getClosestCore().tile);
                        player.sendMessage("[accent]Spawned!");
                    } else {
                        player.sendMessage(noPermissionMessage);
                    }
                } else {
                    player.sendMessage("[scarlet]This command is disabled on pvp.");
                }
            });

            handler.<Player>register("stats", "<player>", "Display stats of the specified player.", (args, player) -> {
                if(args[0].length() > 0) {
                    Player p = findPlayer(args[0]);
                    if(p != null){
                        PlayerData pd = getData(p.uuid);
                        if (pd != null) {
                            Call.onInfoMessage(player.con, formatMessage(p, statMessage));
                        }
                    } else {
                        player.sendMessage("[scarlet]Error: Player not found or offline");
                    }
                } else {
                    Call.onInfoMessage(player.con, formatMessage(player, statMessage));
                }
            });

            handler.<Player>register("info", "Display your stats.", (args, player) -> { // self info
                PlayerData pd = getData(player.uuid);
                if (pd != null) {
                    Call.onInfoMessage(player.con, formatMessage(player, statMessage));
                }
            });

            handler.<Player>register("event", "Join an ongoing event (if there is one)", (args, player) -> { // self info
                if(eventIp.length() > 0){
                    Call.onConnect(player.con, eventIp, eventPort);
                } else{
                    player.sendMessage("[accent]There is no ongoing event at this time.");
                }
            });

            handler.<Player>register("maps","[page]", "Display all maps in the playlist.", (args, player) -> { // self info
                if(args.length > 0 && !Strings.canParseInt(args[0])){
                    player.sendMessage("[scarlet]'page' must be a number.");
                    return;
                }
                int commandsPerPage = 6;
                int page = args.length > 0 ? Strings.parseInt(args[0]) : 1;
                int pages = Mathf.ceil((float)Vars.maps.customMaps().size / commandsPerPage);

                page --;

                if(page >= pages || page < 0){
                    player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                    return;
                }

                StringBuilder result = new StringBuilder();
                result.append(Strings.format("[orange]-- Maps Page[lightgray] {0}[gray]/[lightgray]{1}[orange] --\n\n", (page+1), pages));

                for(int i = commandsPerPage * page; i < Math.min(commandsPerPage * (page + 1), Vars.maps.customMaps().size); i++){
                    mindustry.maps.Map map = Vars.maps.customMaps().get(i);
                    result.append("[white] - [accent]").append(escapeColorCodes(map.name())).append("\n");
                }
                player.sendMessage(result.toString());
            });

            Timekeeper vtime = new Timekeeper(voteCooldown);

            VoteSession[] currentlyKicking = {null};

            handler.<Player>register("nominate","<map...>", "[regular+] Vote to change to a specific map.", (args, player) -> {
                if(!state.rules.pvp || player.isAdmin) {
                    PlayerData pd = getData(player.uuid);
                    if (pd != null && pd.rank >= 2) {
                        mindustry.maps.Map found = getMapBySelector(args[0]);

                        if(found != null){
                            if(!vtime.get()){
                                player.sendMessage("[scarlet]You must wait " + voteCooldown/60 + " minutes between nominations.");
                                return;
                            }

                            VoteSession session = new VoteSession(currentlyKicking, found);

                            session.vote(player, 1);
                            vtime.reset();
                            currentlyKicking[0] = session;
                        }else{
                            player.sendMessage("[scarlet]No map[orange]'" + args[0] + "'[scarlet] found.");
                        }
                    } else {
                        player.sendMessage(noPermissionMessage);
                    }
                } else {
                    player.sendMessage("[scarlet]This command is disabled on pvp.");
                }
            });

            handler.<Player>register("rtv", "Vote to change the map.", (args, player) -> { // self info
                if(currentlyKicking[0] == null){
                    player.sendMessage("[scarlet]No map is being voted on.");
                }else{
                    //hosts can vote all they want
                    if(player.uuid != null && (currentlyKicking[0].voted.contains(player.uuid) || currentlyKicking[0].voted.contains(netServer.admins.getInfo(player.uuid).lastIP))){
                        player.sendMessage("[scarlet]You've already voted. Sit down.");
                        return;
                    }

                    currentlyKicking[0].vote(player, 1);
                }
            });

            handler.<Player>register("label", "<duration> <text...>", "[admin only] Create an in-world label at the current position.", (args, player) -> {
                if(args[0].length() <= 0 || args[1].length() <= 0) player.sendMessage("[scarlet]Invalid arguments provided.");
                if (player.isAdmin) {
                    float x = player.getX();
                    float y = player.getY();

                    Tile targetTile = world.tileWorld(x, y);
                    Call.onLabel(args[1], Float.parseFloat(args[0]), targetTile.worldx(), targetTile.worldy());
                } else {
                    player.sendMessage(noPermissionMessage);
                }
            });

            handler.<Player>register("gm", "<multiplier>", "[admin only] Set your max health multiplier.", (args, player) -> {
                if (player.isAdmin) {
                    player.healthMultiplier = Float.parseFloat(args[0]);
                    player.sendMessage("[accent]Health multiplier set to " + Float.parseFloat(args[0]));
                    player.heal();
                } else {
                    player.sendMessage(noPermissionMessage);
                }
            });

            handler.<Player>register("removelink", "Remove your active discord link.", (args, player) -> {
                CompletableFuture.runAsync(() -> {
                    String uuid = player.uuid;
                    PlayerData pd = getData(uuid);
                    if (pd == null) return;
                    if (pd.discordLink.length() > 0) {
                        pd.discordLink = "";
                        player.sendMessage("[#7289da]\uE848[#99aab5] Discord link with [accent]" + formatMessage(player, "%discord%") + "[] removed successfully.");
                        setData(uuid, pd);
                    } else {
                        player.sendMessage("[#7289da]\uE848[#99aab5] You don't have an active discord link to remove. Setup a discord link using the `link` command in http://discord.mindustry.io");
                    }
                });
            });
        }

    }

}