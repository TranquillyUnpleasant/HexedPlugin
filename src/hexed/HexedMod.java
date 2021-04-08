package hexed;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.*;
import hexed.HexData.HexCaptureEvent;
import hexed.HexData.HexMoveEvent;
import hexed.HexData.HexTeam;
import hexed.HexData.ProgressIncreaseEvent;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.core.NetServer.TeamAssigner;
import mindustry.game.*;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.Schematic.Stile;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.net.Administration;
import mindustry.type.ItemStack;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import static arc.util.Log.info;
import static mindustry.Vars.*;

public class HexedMod extends Plugin {
    //in seconds
    public static final float spawnDelay = 60 * 4;
    //item requirement to captured a hex
    public static final int itemRequirement = 210;

    public static final int messageTime = 1;
    //minimum players to wait for
    private static int minPlayers = 10;
    //in ticks: 3 minutes
    private final static int leaderboardTime = 60 * 60 * 2;

    private final static int updateTime = 60 * 2;

    private final static int timerBoard = 0;
    private final static int timerUpdate = 1;

    private final String domain = "mindustry.io.community";
    private final Rules rules = new Rules();
    private final Interval interval = new Interval(5);

    private HexData data;
    private boolean registered = false;

    private boolean started = false;

    private Schematic start;

    @Override
    public void init() {
        rules.attackMode = true;
        rules.tags.put("hexed", "true");
        rules.loadout = ItemStack.list(Items.copper, 300, Items.lead, 500, Items.graphite, 150, Items.metaglass, 150, Items.silicon, 150, Items.plastanium, 50);
        rules.buildCostMultiplier = 1f;
        rules.buildSpeedMultiplier = 3f / 4f;
        rules.blockHealthMultiplier = 1.4f;
        rules.unitBuildSpeedMultiplier = 0.6f;
        rules.enemyCoreBuildRadius = (Hex.diameter - 1) * tilesize / 2f;
        rules.unitDamageMultiplier = 1f;
        rules.canGameOver = false;
        rules.ambientLight = new Color(80, 0, 0, 30);
        rules.schematicsAllowed = false;
        rules.bannedBlocks.addAll(
                Blocks.hail,
                Blocks.ripple,
                Blocks.foreshadow,
                Blocks.microProcessor,
                Blocks.logicProcessor,
                Blocks.hyperProcessor,
                Blocks.exponentialReconstructor,
                Blocks.tetrativeReconstructor
        );

        start = Schematics.readBase64("bXNjaAF4nE2RDW6DMAyFnR9IAqxTD8JZdoaURVUlChW0q3r7vRdvUpHgw7H97NjSy6cRv+RrkbCU5ynv5UOO03q7lW185nke57ydi/RvRxKndfkpr3UTl7dJwl7O17Lcpd9XRI+3vJRZhq3c8gXWeoEr7FO+38smw7RuZVwe01weuxzfMv4qtZD6RmB8LPOa+RdONfUlIl94xYg1gJP6eBELtIqgiOpLDBbp1RqYR9s4wImzQM0zmmeYR18vjpEHMabKGV+9pgGQ1wKeYlYaqlhVsapiqUJfEheAjrUtqlexqsnWXUMphRcXIRWk9lVDPJOSsKLtYHkxPT5dgxjD80GjDuyjYfAAQIjo2H+jvpa+HvBMhavFa3he3aEOFMD1AoDW2EjHxgIH4YFaKyDBGR0tUa8eoWOJoIjUjLx6C3QcWdRxRr1X+t8MhuEYx4pJt5hULKlYohh9iUtJVKFVt5hUrOORrXeyRFDUJjqu/RdXMj2b");

        Events.run(Trigger.update, () -> {
            if (active()) {
                data.updateStats();

                for (Player player : Groups.player) {
                    if (player.team() != Team.derelict && player.team().cores().isEmpty() && started) {
                        player.clearUnit();
                        killTiles(player.team());
                        Call.sendMessage("[yellow](!)[] [accent]" + player.name + "[lightgray] has been eliminated![yellow] (!)");
                        Call.infoMessage(player.con, "Your cores have been destroyed. You are defeated.");
                        player.team(Team.derelict);
                    }
                    if (player.team() == Team.derelict) {
                        player.clearUnit();
                    }
                    if (started && data.getControlled(player).size == data.hexes().size) {
                        Groups.player.forEach(p -> {
                            Call.infoMessage(p.con, "[accent]--EVENT OVER--\n\n[lightgray]"
                                    + (p == player ? "[accent]You[] were" : "[yellow]" + player.name + "[lightgray] was") +
                                    " victorious, with [accent]" + data.getControlled(player).size + "[lightgray] hexes conquered.\n\n[stat]Thank you to everyone who attended the event!\n\n[orange]Hope to see you in the next(?) one!");
                            Timer.schedule(() -> Call.connect(p.con, domain, port), 120);
                        });
                        started = false;
                    }
                }

                if (interval.get(timerBoard, leaderboardTime)) {
                    Call.infoToast(getLeaderboard(), 15f);
                }

                if (interval.get(timerUpdate, updateTime)) {
                    data.updateControl();
                }
            }
        });

        Events.on(BlockDestroyEvent.class, event -> {
            //reset last spawn times so this hex becomes vacant for a while.
            if (event.tile.block() instanceof CoreBlock) {
                Hex hex = data.getHex(event.tile.pos());

                if (hex != null) {
                    //update state
                    hex.spawnTime.reset();
                    hex.updateController();
                }
            }
        });

        Events.on(PlayerLeave.class, event -> {
            if (active() && event.player.team() != Team.derelict) {
                killTiles(event.player.team());
            }
        });

        Events.on(PlayerJoin.class, event -> {
            if(started){
                Call.infoMessage(event.player.con, "The event has already started.\nAssigning into spectator mode.");
                event.player.unit().kill();
                event.player.team(Team.derelict);
            }
            if(!active() || event.player.team() == Team.derelict) return;

            Seq<Hex> copy = data.hexes().copy();
            copy.shuffle();
            Hex hex = copy.find(h -> h.controller == null && h.spawnTime.get());

            if(hex != null){
                loadout(event.player, hex.x, hex.y);
                Core.app.post(() -> data.data(event.player).chosen = false);
                hex.findController();
            }else{
                Call.infoMessage(event.player.con, "There are currently no empty hex spaces available.\nAssigning into spectator mode.");
                event.player.unit().kill();
                event.player.team(Team.derelict);
            }

            data.data(event.player).lastMessage.reset();
        });

        Events.on(ProgressIncreaseEvent.class, event -> updateText(event.player));

        Events.on(HexCaptureEvent.class, event -> {
            Hex hex = event.hex;
            Tile tile = world.tile(hex.x, hex.y);
            if (tile != null) {
                if (tile.block() != Blocks.air) tile.removeNet();
                tile.setNet(Blocks.coreShard, event.player.team(), 0);
            }
            updateText(event.player);
        });

        Events.on(HexMoveEvent.class, event -> updateText(event.player));

        Events.on(EventType.ServerLoadEvent.class, event -> {
            netServer.admins.addActionFilter(action -> {
                if (!started) Call.infoToast(action.player.con, "[scarlet]The event has not yet begun.", 10f);
                return started && action.type != Administration.ActionType.command;
            });
        });

        TeamAssigner prev = netServer.assigner;
        netServer.assigner = (player, players) -> {
            Seq<Player> arr = Seq.with(players);

            if (active()) {
                //pick first inactive team
                for (Team team : Team.all) {
                    if (team.id > 5 && !team.active() && !arr.contains(p -> p.team() == team) && !data.data(team).dying && !data.data(team).chosen) {
                        data.data(team).chosen = true;
                        return team;
                    }
                }
                Call.infoMessage(player.con, "There are currently no empty hex spaces available.\nAssigning into spectator mode.");
                return Team.derelict;
            } else {
                return prev.assign(player, players);
            }
        };
    }

    void updateText(Player player) {
        HexTeam team = data.data(player);

        StringBuilder message = new StringBuilder("[white]Hex #" + team.location.id + "\n");

        if (!team.lastMessage.get()) return;

        if (team.location.controller == null) {
            if (team.progressPercent > 0) {
                message.append("[lightgray]Capture progress: [accent]").append((int) (team.progressPercent)).append("%");
            } else {
                message.append("[lightgray][[Empty]");
            }
        } else if (team.location.controller == player.team()) {
            message.append("[yellow][[Captured]");
        } else if (team.location != null && team.location.controller != null && data.getPlayer(team.location.controller) != null) {
            message.append("[#").append(team.location.controller.color).append("]Captured by ").append(data.getPlayer(team.location.controller).name);
        } else {
            message.append("<Unknown>");
        }
        Call.setHudText(player.con, message.toString());
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("hexed", "Begin hosting with the Hexed gamemode.", args -> {
            if (!state.is(State.menu)) {
                Log.err("Stop the server first.");
                return;
            }

            data = new HexData();

            logic.reset();
            Log.info("Generating map...");
            HexedGenerator generator = new HexedGenerator();
            world.loadGenerator(Hex.size, Hex.size, generator);
            data.initHexes(generator.getHex());
            info("Map generated.");
            state.rules = rules.copy();
            logic.play();
            netServer.openServer();
        });

        handler.register("begin", "[force?]", "Begin the event", args -> {
            boolean force = args.length < 1 || args[0].equals("force");
            if (Groups.player.size() < 10 && !force) {
                Log.info("Wait for more people to join, @ / @", Groups.player.size(), minPlayers);
                return;
            }
            Call.infoMessage("[accent]--EVENT STARTED--[]\n\n[scarlet]Last one to survive wins![]\n\n[white]Happy hexing...\nand may the odds be ever in your favor!");
            started = true;
        });

        handler.register("finish", "Announce the end of the event.", args -> {
            if (!started) {
                Log.info("The event has not started yet.");
                return;
            }
            Player winner = Groups.player.find(p -> {
                Log.info("@ : @ / @ hexes controlled.", p.name, data.getControlled(p).size, data.hexes().filter(h -> h.controller != null).size);
                return data.getControlled(p).size == data.hexes().size;
            });
            if (winner == null) {
                Log.info("The event is not over yet, there is more than one player remaining.");
                return;
            }
            Groups.player.forEach(p -> {
                Call.infoMessage(p.con, "[accent]--EVENT OVER--\n\n[lightgray]"
                        + (p == winner ? "[accent]You[] were" : "[yellow]" + winner.name + "[lightgray] was") +
                        " victorious, with [accent]" + data.getControlled(winner).size + "[lightgray] hexes conquered.\n\n[stat]Thank you to everyone who attended the event!\n\n[orange]Hope to see you in the next(?) one!");
            });
            Timer.schedule(() -> System.exit(0), 120);
        });

        handler.register("setmin", "<amount>", "Set the minimum amount of players before the event can start.", args -> {
            try {
                int number = Integer.parseInt(args[0]);
                if (number > 0) {
                    minPlayers = number;
                    Log.info("Set minimum players to @", number);
                }
                Log.info("Amount must be positive.");
            } catch (NumberFormatException ignored) {
                Log.info("Amount must be a number.");
            }
        });

        handler.register("r", "Restart the server.", args -> System.exit(2));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        if (registered) return;
        registered = true;

        handler.<Player>register("spectate", "Enter spectator mode. This destroys your base.", (args, player) -> {
            if (player.team() == Team.derelict) {
                player.sendMessage("[scarlet]You're already spectating.");
            } else {
                killTiles(player.team());
                player.unit().kill();
                player.team(Team.derelict);
            }
        });

        handler.<Player>register("captured", "Dispay the number of hexes you have captured.", (args, player) -> {
            if (player.team() == Team.derelict) {
                player.sendMessage("[scarlet]You're spectating.");
            } else {
                player.sendMessage("[lightgray]You've captured[accent] " + data.getControlled(player).size + "[] hexes.");
            }
        });

        handler.<Player>register("leaderboard", "Display the leaderboard", (args, player) -> {
            player.sendMessage(getLeaderboard());
        });

        handler.<Player>register("hexstatus", "Get hex status at your position.", (args, player) -> {
            Hex hex = data.data(player).location;
            if (hex != null) {
                hex.updateController();
                StringBuilder builder = new StringBuilder();
                builder.append("| [lightgray]Hex #").append(hex.id).append("[]\n");
                builder.append("| [lightgray]Owner:[] ").append(hex.controller != null && data.getPlayer(hex.controller) != null ? data.getPlayer(hex.controller).name : "<none>").append("\n");
                for (TeamData data : state.teams.getActive()) {
                    if (hex.getProgressPercent(data.team) > 0) {
                        builder.append("|> [accent]").append(this.data.getPlayer(data.team).name).append("[lightgray]: ").append((int) hex.getProgressPercent(data.team)).append("% captured\n");
                    }
                }
                player.sendMessage(builder.toString());
            } else {
                player.sendMessage("[scarlet]No hex found.");
            }
        });
    }

    String getLeaderboard() {
        StringBuilder builder = new StringBuilder();
        builder.append("[accent]Leaderboard\n[scarlet]").append("[lightgray] mins. remaining\n\n");
        int count = 0;
        for (Player player : data.getLeaderboard()) {
            builder.append("[yellow]").append(++count).append(".[white] ")
                    .append(player.name).append("[orange] (").append(data.getControlled(player).size).append(" hexes)\n[white]");

            if (count > 4) break;
        }
        return builder.toString();
    }

    void killTiles(Team team) {
        data.data(team).dying = true;
        Time.runTask(8f, () -> data.data(team).dying = false);
        for (int x = 0; x < world.width(); x++) {
            for (int y = 0; y < world.height(); y++) {
                Tile tile = world.tile(x, y);
                if (tile.build != null && tile.team() == team) {
                    Time.run(Mathf.random(60f * 6), tile.build::kill);
                }
            }
        }
    }

    void loadout(Player player, int x, int y) {
        Stile coreTile = start.tiles.find(s -> s.block instanceof CoreBlock);
        if (coreTile == null) throw new IllegalArgumentException("Schematic has no core tile. Exiting.");
        int ox = x - coreTile.x, oy = y - coreTile.y;
        start.tiles.each(st -> {
            Tile tile = world.tile(st.x + ox, st.y + oy);
            if (tile == null) return;

            if (tile.block() != Blocks.air) {
                tile.removeNet();
            }

            tile.setNet(st.block, player.team(), st.rotation);

            if (st.config != null) {
                tile.build.configureAny(st.config);
            }
            if (tile.block() instanceof CoreBlock) {
                for (ItemStack stack : state.rules.loadout) {
                    Call.setItem(tile.build, stack.item, stack.amount);
                }
            }
        });
    }

    public boolean active() {
        return state.rules.tags.getBool("hexed") && !state.is(State.menu);
    }


}
