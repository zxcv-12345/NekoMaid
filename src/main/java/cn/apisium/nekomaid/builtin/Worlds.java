package cn.apisium.nekomaid.builtin;

import cn.apisium.nekomaid.NekoMaid;
import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.FutureTask;

final class Worlds implements Listener {
    private boolean hasWorldGameRuleChangeEvent = false, canSetViewDistance;
    private final Plugin mv;
    private final NekoMaid main;
    private final static class World {
        public String[][] rules;
        public String name, id, difficulty, alias;
        public int entities, chunks, tiles, players, weather, viewDistance;
        public long time, seed;
        public boolean allowMonsters, allowAnimals, pvp, allowFlight, autoHeal, hunger;
    }
    @SuppressWarnings({"unchecked", "deprecation"})
    public Worlds(NekoMaid main) {
        this.main = main;
        mv = main.getServer().getPluginManager().getPlugin("Multiverse-Core");
        if (mv != null) main.GLOBAL_DATA.put("hasMultiverse", true);
        main.onConnected(main, client -> client.onWithAck("worlds:fetch", this::getWorlds)
                .onWithAck("worlds:weather", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null) return;
                    main.getServer().getScheduler().runTask(main, () -> {
                        if (world.isThundering()) {
                            world.setThundering(false);
                            world.setStorm(false);
                        } else if (world.hasStorm()) world.setThundering(true);
                        else world.setStorm(true);
                    });
                }).onWithAck("worlds:rule", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null) return;
                    String k = (String) args[1], v = (String) args[2];
                    main.getServer().getScheduler().runTask(main, () -> {
                        world.setGameRuleValue(k, v);
                        if (!hasWorldGameRuleChangeEvent) update();
                    });
                }).onWithAck("worlds:difficulty", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null) return;
                    String value = (String) args[1];
                    Difficulty diff = Difficulty.valueOf(value);
                    main.getServer().getScheduler().runTask(main, () -> {
                        world.setDifficulty(diff);
                        if (mv != null) try {
                            MVWorldManager wm = ((MultiverseCore) mv).getMVWorldManager();
                            wm.getMVWorld(world).setPropertyValue("difficulty", value);
                            ((MultiverseCore) mv).getMVWorldManager().saveWorldsConfig();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                        update();
                    });
                }).onWithAck("worlds:pvp", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null) return;
                    boolean value = (boolean) args[1];
                    main.getServer().getScheduler().runTask(main, () -> {
                        world.setPVP(value);
                        if (mv != null) try {
                            MVWorldManager wm = ((MultiverseCore) mv).getMVWorldManager();
                            wm.getMVWorld(world).setPropertyValue("pvp", String.valueOf(value));
                            ((MultiverseCore) mv).getMVWorldManager().saveWorldsConfig();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                        update();
                    });
                }).onWithAck("worlds:viewDistance", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null || !canSetViewDistance) return;
                    main.getServer().getScheduler().runTask(main, () -> {
                        world.setViewDistance((int) args[1]);
                        update();
                    });
                }).onWithAck("worlds:set", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null || mv == null) return;
                    MVWorldManager wm = ((MultiverseCore) mv).getMVWorldManager();
                    main.getServer().getScheduler().runTask(main, () -> {
                        try {
                            wm.getMVWorld(world).setPropertyValue((String) args[1], (String) args[2]);
                            update();
                            wm.saveWorldsConfig();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    });
                }).onWithAck("worlds:save", args -> {
                    org.bukkit.World world = main.getServer().getWorld(UUID.fromString((String) args[0]));
                    if (world == null) return;
                    main.getServer().getScheduler().runTask(main, world::save);
                }));
        main.getServer().getScheduler()
                .runTask(main, () -> main.getServer().getPluginManager().registerEvents(this, main));
        try {
            Class<? extends Event> clazz = (Class<? extends Event>)
                    Class.forName("io.papermc.paper.event.world.WorldGameRuleChangeEvent");
            main.getServer().getPluginManager().registerEvent(clazz, this,
                    EventPriority.MONITOR, (a, b) -> update(), main, true);
            hasWorldGameRuleChangeEvent = true;
        } catch (Throwable ignored) { }
        try {
            org.bukkit.World.class.getMethod("setViewDistance", int.class);
            canSetViewDistance = true;
            main.GLOBAL_DATA.put("canSetViewDistance", true);
        } catch (Throwable ignored) { }
    }

    private void update() { main.broadcastInPage(main, "worlds", "worlds:update"); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onWeatherChange(WeatherChangeEvent e) { update(); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onThunderChange(ThunderChangeEvent e) { update(); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTimeSkip(TimeSkipEvent e) { update(); }
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent e) { update(); }
    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) { update(); }
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) { update(); }

    @SuppressWarnings("deprecation")
    private Object[] getWorlds() {
        FutureTask<Object[]> task = new FutureTask<>(() -> main.getServer().getWorlds().stream().map(it -> {
            Chunk[] chunks = it.getLoadedChunks();
            World w = new World();
            w.id = it.getUID().toString();
            w.name = it.getName();
            w.entities = it.getEntities().size();
            w.players = it.getPlayers().size();
            w.chunks = chunks.length;
            w.weather = it.isThundering() ? 2 : it.hasStorm() ? 1 : 0;
            w.viewDistance = it.getViewDistance();
            w.allowMonsters = it.getAllowMonsters();
            w.allowAnimals = it.getAllowAnimals();
            w.pvp = it.getPVP();
            w.time = it.getTime();
            w.difficulty = it.getDifficulty().name();
            w.seed = it.getSeed();
            w.rules = Arrays.stream(it.getGameRules()).map(r -> new String[] { r, it.getGameRuleValue(r) })
                    .toArray(String[][]::new);
            for (Chunk ch : chunks) w.tiles += ch.getTileEntities().length;
            if (mv != null) {
                MultiverseWorld mw = ((MultiverseCore) mv).getMVWorldManager().getMVWorld(it);
                w.alias = mw.getAlias();
                w.allowFlight = mw.getAllowFlight();
                w.autoHeal = mw.getAutoHeal();
                w.hunger = mw.getHunger();
            }
            return w;
        }).toArray());
        main.getServer().getScheduler().runTask(main, task);
        try {
            return task.get();
        } catch (Throwable e) {
            e.printStackTrace();
            return new Object[0];
        }
    }
}
