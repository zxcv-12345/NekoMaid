package cn.apisium.nekomaid;

import cn.apisium.nekomaid.builtin.BuiltinPlugins;
import cn.apisium.netty.engineio.EngineIoHandler;
import cn.apisium.uniporter.Uniporter;
import cn.apisium.uniporter.router.api.Route;
import cn.apisium.uniporter.router.api.UniporterHttpHandler;
import com.google.common.collect.ArrayListMultimap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.socket.engineio.server.EngineIoServer;
import io.socket.socketio.server.SocketIoAdapter;
import io.socket.socketio.server.SocketIoNamespace;
import io.socket.socketio.server.SocketIoServer;
import io.socket.socketio.server.SocketIoSocket;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.annotation.command.Command;
import org.bukkit.plugin.java.annotation.command.Commands;
import org.bukkit.plugin.java.annotation.dependency.Dependency;
import org.bukkit.plugin.java.annotation.dependency.SoftDependency;
import org.bukkit.plugin.java.annotation.permission.Permission;
import org.bukkit.plugin.java.annotation.permission.Permissions;
import org.bukkit.plugin.java.annotation.plugin.*;
import org.bukkit.plugin.java.annotation.plugin.author.Author;
import org.bukkit.util.CachedServerIcon;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

@SuppressWarnings({"UnusedReturnValue", "unused"})
@Plugin(name = "NekoMaid", version = "1.0")
@Description("A plugin can use Web to manage your server.")
@Author("Shirasawa")
@Website("https://neko-craft.com")
@ApiVersion(ApiVersion.Target.v1_13)
@Commands(@Command(name = "nekomaid", permission = "neko.maid.use", desc = "Can use NekoMaid.", aliases = "nm"))
@Permissions(@Permission(name = "neko.maid.use"))
@Dependency("Uniporter")
@SoftDependency("PlugMan")
@SoftDependency("Vault")
@SoftDependency("OpenInv")
@SoftDependency("PlaceholderAPI")
public final class NekoMaid extends JavaPlugin implements Listener {
    public static NekoMaid INSTANCE;
    { INSTANCE = this; }

    private final ArrayListMultimap<org.bukkit.plugin.Plugin, Consumer<Client>> connectListeners = ArrayListMultimap.create();
    private final HashMap<String, HashMap<String, AbstractMap.SimpleEntry<Consumer<Client>,
            Consumer<Client>>>> pluginPages = new HashMap<>();

    private BuiltinPlugins plugins;
    private EngineIoServer engineIoServer;
    private final ConcurrentHashMap<SocketIoSocket, String[]> pages = new ConcurrentHashMap<>();
    private final HashMap<SocketIoSocket, HashMap<String, Client>> clients = new HashMap<>();
    private final JSONObject pluginScripts = new JSONObject();
    public SocketIoNamespace io;
    protected Map<String, Set<SocketIoSocket>> mRoomSockets;

    final public JSONObject GLOBAL_DATA = new JSONObject();

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    @Override
    public void onEnable() {
        saveDefaultConfig();
        GLOBAL_DATA
                .put("plugins", pluginScripts)
                .put("version", getServer().getVersion())
                .put("onlineMode", getServer().getOnlineMode())
                .put("hasWhitelist", getServer().hasWhitelist());
        CachedServerIcon icon = getServer().getServerIcon();
        if (icon != null && !icon.isEmpty()) GLOBAL_DATA.put("icon", icon.getData());
        if (getConfig().getString("token", null) == null) {
            getConfig().set("token", UUID.randomUUID().toString());
            saveConfig();
        }
        engineIoServer = new EngineIoServer();
        io = new SocketIoServer(engineIoServer).namespace("/");
        try {
            Field field = SocketIoAdapter.class.getDeclaredField("mRoomSockets");
            field.setAccessible(true);
            mRoomSockets = (Map<String, Set<SocketIoSocket>>) field.get(io.getAdapter());
        } catch (Exception e) {
            e.printStackTrace();
            setEnabled(false);
            return;
        }
        io.on("connection", arr -> {
            SocketIoSocket client = (SocketIoSocket) arr[0];
            Object obj = client.getConnectData();
            if (!(obj instanceof JSONObject) || client.getInitialHeaders() == null ||
                    !client.getInitialHeaders().containsKey("User-Agent") ||
                    client.getInitialHeaders().get("User-Agent").isEmpty()) {
                client.send("!");
                client.disconnect(false);
                return;
            }
            String token = ((JSONObject) obj).getString("token"), ua = client.getInitialHeaders().get("User-Agent").get(0);
            if (token == null || token.isEmpty() || token.length() > 100 || ua == null || ua.isEmpty() ||
                    !getConfig().getString("token", "").equals(Utils.decrypt(token, ua))) {
                client.send("!");
                client.disconnect(false);
                return;
            }
            client.send("globalData", GLOBAL_DATA);
            client.once("disconnect", args -> {
                pages.remove(client);
                clients.remove(client);
            }).on("switchPage", args -> {
                try {
                    String[] oldPageObj = pages.get(client);
                    Client wrappedClient = null;
                    if (oldPageObj != null) {
                        client.leaveRoom(oldPageObj[0] + ":page:" + oldPageObj[1]);
                        HashMap<String, AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>>> oldPages =
                                pluginPages.get(oldPageObj[0]);
                        if (oldPages != null) {
                            AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>> oldPage = oldPages.get(oldPageObj[1]);
                            if (oldPage != null && oldPage.getValue() != null) oldPage.getValue()
                                    .accept(wrappedClient = getClient(oldPageObj[0], client));
                        }
                    }
                    String namespace = (String) args[0], page = (String) args[1];
                    pages.put(client, new String[]{ namespace, page });
                    client.joinRoom(namespace + ":page:" + page);
                    HashMap<String, AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>>> pages = pluginPages.get(namespace);
                    if (pages == null) return;
                    AbstractMap.SimpleEntry<Consumer<Client>, Consumer<Client>> pageAction = pages.get(page);
                    if (pageAction != null && pageAction.getKey() != null) pageAction.getKey()
                            .accept(wrappedClient == null ? getClient(namespace, client) : wrappedClient);
                } catch (Exception e) {e.printStackTrace();}
            }).on("error", System.out::println);
            connectListeners.forEach((k, v) -> v.accept(getClient(k, client)));
        }).on("error", System.out::println);
        Uniporter.registerHandler("NekoMaid", new MainHandler(), true);
//        Uniporter.registerHandler("NekoMaidDownload", new MainHandler(), true);

        plugins = new BuiltinPlugins(this);

        getServer().getPluginManager().registerEvent(PluginDisableEvent.class, this, EventPriority.NORMAL, (a, e) -> {
            org.bukkit.plugin.Plugin p = ((PluginDisableEvent) e).getPlugin();
            String name = p.getName();
            pluginPages.remove(name);
            clients.forEach((k, v) -> v.remove(name));
            connectListeners.removeAll(p);
        }, this);
        getCommand("nekomaid").setExecutor(this);
    }

    public int getClientsCount() { return clients.size(); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, org.bukkit.command.@NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        new RuntimeException("@33").printStackTrace();
        return true;
    }

    @Override
    public void onDisable() {
        Uniporter.removeHandler("NekoMaid");
        pages.clear();
        connectListeners.clear();
        if (engineIoServer != null) engineIoServer.shutdown();
        if (plugins != null) plugins.disable();
    }

    public int getClientsCountInPage(org.bukkit.plugin.Plugin plugin, @NotNull String page) {
        if (mRoomSockets == null) return 0;
        Set<SocketIoSocket> set = mRoomSockets.get(plugin.getName() + ":page:" + page);
        return set == null ? 0 : set.size();
    }

    public int getClientsCountInRoom(org.bukkit.plugin.Plugin plugin, @NotNull String room) {
        if (mRoomSockets == null) return 0;
        Set<SocketIoSocket> set = mRoomSockets.get(plugin.getName() + ":" + room);
        return set == null ? 0 : set.size();
    }

    @Contract("_, _, _ -> this")
    public NekoMaid broadcast(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String name, @NotNull Object... data) {
        if (!clients.isEmpty()) io.broadcast((String) null, plugin.getName() + ":" + name, data);
        return this;
    }

    @Contract("_, _, _, _ -> this")
    public NekoMaid broadcast(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String room,
                              @NotNull String name, @NotNull Object... data) {
        if (getClientsCountInRoom(plugin, room) != 0) {
            Utils.serialize(data);
            String prefix = plugin.getName() + ":";
            io.broadcast(prefix + room, prefix + name, data);
        }
        return this;
    }

    @Contract("_, _, _, _ -> this")
    public NekoMaid broadcastInPage(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String page,
                              @NotNull String name, @NotNull Object... data) {
        return broadcast(plugin, "page:" + page, name, data);
    }

    private Client getClient(org.bukkit.plugin.Plugin plugin, SocketIoSocket client) {
        return getClient(plugin.getName(), client);
    }

    private Client getClient(String plugin, SocketIoSocket client) {
        return clients.computeIfAbsent(client, (a) -> new HashMap<>())
                .computeIfAbsent(plugin, (a) -> new Client(plugin, client));
    }

    @Contract("_, _ -> this")
    public NekoMaid onConnected(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Consumer<Client> fn) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(fn);
        connectListeners.put(plugin, fn);
        return this;
    }

    @Contract("_, _ -> this")
    public NekoMaid on(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Consumer<Client> fn) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(fn);
        connectListeners.put(plugin, fn);
        return this;
    }

    @Contract("_, _, _ -> this")
    @NotNull
    public NekoMaid onSwitchPage(@NotNull org.bukkit.plugin.Plugin plugin,
                             @NotNull String page, @Nullable Consumer<Client> onEnter) {
        return onSwitchPage(plugin, page, onEnter, null);
    }

    @Contract("_, _, _, _ -> this")
    @NotNull
    public NekoMaid onSwitchPage(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String page,
                             @Nullable Consumer<Client> onEnter, @Nullable Consumer<Client> onLeave) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(page);
        if (onEnter != null || onLeave != null)
            pluginPages.computeIfAbsent(plugin.getName(), k -> new HashMap<>())
                    .computeIfAbsent(page, k -> new AbstractMap.SimpleEntry<>(onEnter, onLeave));
        return this;
    }

    @Contract("_, _ -> this")
    @NotNull
    public NekoMaid addScript(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull String script) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(script);
        pluginScripts.put(plugin.getName(), script);
        return this;
    }

    private final class MainHandler implements UniporterHttpHandler {
        @Override
        public void handle(String path, Route route, ChannelHandlerContext context, FullHttpRequest request) {
            if (route.isGzip()) context.pipeline().addLast(new HttpContentCompressor())
                    .addLast(new WebSocketServerCompressionHandler());
            context.channel().pipeline().addLast(new EngineIoHandler(engineIoServer, null,
                    "ws://maid.neko-craft.com", 1024 * 1024 * 5) {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    super.exceptionCaught(ctx, cause);
                    cause.printStackTrace();
                }
            });
        }

        @Override
        public boolean needReFire() { return true; }
    }
}