package io.github.hello09x.fakeplayer.core.manager;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public class WildFakeplayerManager implements PluginMessageListener, Listener {

    private final static Logger log = Main.getInstance().getLogger();
    private final static boolean IS_BUNGEECORD = Bukkit
            .getServer()
            .spigot()
            .getSpigotConfig()
            .getBoolean("settings.bungeecord", false);
    private final static String CHANNEL = "BungeeCord";
    private final static String SUB_CHANNEL = "PlayerList";

    /**
     * 定义探测到连续 n 次不在线时进行清理
     * <br>
     * 仅在 {@link #IS_BUNGEECORD} 为 {@code true} 时生效
     */
    private final static int CLEANUP_THRESHOLD = 2;
    private final static int CLEANUP_PERIOD = 6000;

    private final FakeplayerManager manager;
    private final FakeplayerConfig config;

    /**
     * K: "uuid:&lt;创建者 UUID&gt;" 或 "name:&lt;创建者名称&gt;", V: 连续不在线的次数
     */
    private final Map<String, AtomicInteger> offline = new HashMap<>();

    @Inject
    public WildFakeplayerManager(FakeplayerManager manager, FakeplayerConfig config) {
        this.manager = manager;
        this.config = config;
        Schedulers.globalTimer(Main.getInstance(), 0, CLEANUP_PERIOD, this::cleanup);
    }

    /**
     * 创建者退出后立即清理其假人, 而不必等待定时轮询。
     * <p>按 {@code follow-quiting-force} 开关启用, 延迟 {@code follow-quiting-force-delay} 秒执行;
     * 若创建者在该延迟内重新上线 (快速重连或切换服务器后返回), 则保留其假人。</p>
     */
    @EventHandler
    public void handleFollowQuitingForce(@NotNull PlayerQuitEvent event) {
        if (!config.isFollowQuiting() || !config.isFollowQuitingForce()) {
            return;
        }

        var creator = event.getPlayer();
        var creatorUuid = creator.getUniqueId();
        var delayTicks = Math.max(1, config.getFollowQuitingForceDelay()) * 20L;
        for (var target : this.manager.getAll(creator)) {
            // 在假人所属区域线程上移除 (Folia 兼容)
            Schedulers.entityLater(Main.getInstance(), target, delayTicks, () -> {
                // 创建者已经回到服务器, 说明只是快速重连, 不要误删
                if (Bukkit.getPlayer(creatorUuid) != null) {
                    return;
                }
                manager.remove(target.getName(), "Creator offline");
            });
        }
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] message
    ) {
        if (!channel.equals(CHANNEL)) {
            return;
        }

        @SuppressWarnings("UnstableApiUsage")
        var in = ByteStreams.newDataInput(message);
        if (!in.readUTF().equals(SUB_CHANNEL)) {
            return;
        }

        if (!in.readUTF().equals("ALL")) {
            return;
        }

        var players = new HashSet<String>();
        players.addAll(Arrays.asList(in.readUTF().split(", ")));
        players.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        this.cleanup0(players);
    }

    /**
     * 生成分组用的创建者键: 已知 UUID 时用 UUID, 否则用名称
     */
    private @NotNull String creatorKey(@NotNull Player target) {
        var uuid = manager.getCreatorUuid(target);
        return uuid != null
                ? "uuid:" + uuid
                : "name:" + manager.getCreatorName(target);
    }

    /**
     * 清除所有不在 {@code online} 列表中的玩家的假人
     * <p>创建者的 UUID 已知时优先用 UUID 判断是否在线 (改名后不会误删);
     * 从 BungeeCord 收到的列表只有名称, 因此名称匹配作为备用。</p>
     *
     * @param online 在线玩家名称
     */
    public void cleanup0(@NotNull Set<String> online) {
        var onlineUuids = Bukkit.getOnlinePlayers()
                                .stream()
                                .map(Player::getUniqueId)
                                .collect(Collectors.toSet());

        var group = manager.getAll()
                           .stream()
                           .collect(Collectors.groupingBy(this::creatorKey));

        for (var entry : group.entrySet()) {
            var key = entry.getKey();
            var targets = entry.getValue();
            if (targets.isEmpty()) {
                continue;
            }

            var creatorName = manager.getCreatorName(targets.get(0));
            if (creatorName == null || creatorName.equals("CONSOLE")) {
                continue;
            }

            var creatorUuid = manager.getCreatorUuid(targets.get(0));
            var isOnline = (creatorUuid != null && onlineUuids.contains(creatorUuid))
                    || online.contains(creatorName);
            if (isOnline) {
                continue;
            }

            if (offline.computeIfAbsent(key, x -> new AtomicInteger()).incrementAndGet() < CLEANUP_THRESHOLD) {
                continue;
            }

            for (var target : targets) {
                manager.remove(target.getName(), "Creator offline");
            }
            log.info("%s is offline more than %d ticks, removing %d fake players".formatted(
                    creatorName,
                    CLEANUP_PERIOD * CLEANUP_THRESHOLD,
                    targets.size())
            );
        }

        for (var name : online) {
            offline.remove("name:" + name);
        }
        for (var uuid : onlineUuids) {
            offline.remove("uuid:" + uuid);
        }
    }

    /**
     * 清理召唤者下线的假人
     */
    public void cleanup() {
        if (!config.isFollowQuiting()) {
            return;
        }

        // 非 bungeeCord 服务器立即清理
        if (!IS_BUNGEECORD) {
            this.cleanup0(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toSet()));
            return;
        }

        // BungeeCord 服务器请求获取所有服务器在线玩家后
        // 在接收到在线列表后再进行清理
        var recipient = Bukkit
                .getServer()
                .getOnlinePlayers()
                .stream()
                .filter(manager::isNotFake)
                .findAny()
                .orElse(null);

        if (recipient == null) {
            return;
        }

        @SuppressWarnings("UnstableApiUsage")
        var out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_CHANNEL);
        out.writeUTF("ALL");
        recipient.sendPluginMessage(
                Main.getInstance(),
                CHANNEL,
                out.toByteArray()
        );
    }

}
