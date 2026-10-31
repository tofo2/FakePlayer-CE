package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.core.entity.Fakeplayer;
import io.github.hello09x.fakeplayer.core.util.OfflineCreator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
import java.util.stream.Stream;

@Singleton
public class FakeplayerList {

    private final Map<String, Fakeplayer> playersByName = new HashMap<>();
    private final Map<UUID, Fakeplayer> playersByUUID = new HashMap<>();
    private final Map<String, List<Fakeplayer>> playersByCreator = new HashMap<>();
    private final Map<UUID, List<Fakeplayer>> playersByCreatorUuid = new HashMap<>();

    private static @NotNull String nameKey(@NotNull String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * 获取创建者的 UUID
     *
     * @param creator 创建者
     * @return 真实玩家或已记录 UUID 的离线创建者返回其 UUID, 其他 (例如控制台) 返回 {@code null}
     */
    public static @Nullable UUID creatorUuid(@NotNull CommandSender creator) {
        if (creator instanceof Player player) {
            return player.getUniqueId();
        }
        if (creator instanceof OfflineCreator offline) {
            return offline.getUniqueId();
        }
        return null;
    }

    /**
     * 添加一个假人到假人清单
     *
     * @param player 假人
     */
    public boolean add(@NotNull Fakeplayer player) {
        var name = nameKey(player.getName());
        if (this.playersByName.containsKey(name) || this.playersByUUID.containsKey(player.getUUID())) {
            return false;
        }
        this.playersByName.put(name, player);
        this.playersByUUID.put(player.getUUID(), player);
        this.playersByCreator.computeIfAbsent(player.getCreator().getName(), key -> new LinkedList<>()).add(player);

        var creatorUuid = creatorUuid(player.getCreator());
        if (creatorUuid != null) {
            this.playersByCreatorUuid.computeIfAbsent(creatorUuid, key -> new LinkedList<>()).add(player);
        }
        return true;
    }

    /**
     * 通过假人的名称获取假人
     *
     * @param name 名称
     * @return 假人
     */
    public @Nullable Fakeplayer getByName(@NotNull String name) {
        return this.playersByName.get(nameKey(name));
    }

    /**
     * 通过 UUID 获取假人
     *
     * @param uuid UUID
     * @return 假人
     */
    public @Nullable Fakeplayer getByUUID(@NotNull UUID uuid) {
        return this.playersByUUID.get(uuid);
    }

    /**
     * 获取创建者创建的所有假人
     * <p>先按创建者名称匹配; 如果该名称当前有在线的真实玩家, 还会合并按其 UUID 匹配到的假人,
     * 这样玩家改名后仍能找到自己的假人。</p>
     *
     * @param creator 创建者名称
     * @return 假人
     */
    public @NotNull @Unmodifiable List<Fakeplayer> getByCreator(@NotNull String creator) {
        var result = new LinkedHashSet<Fakeplayer>();
        Optional.ofNullable(this.playersByCreator.get(creator)).ifPresent(result::addAll);

        var online = Bukkit.getPlayerExact(creator);
        if (online != null) {
            Optional.ofNullable(this.playersByCreatorUuid.get(online.getUniqueId())).ifPresent(result::addAll);
        }
        return List.copyOf(result);
    }

    /**
     * 移除一个假人
     *
     * @param player 假人
     */
    public boolean remove(@NotNull Fakeplayer player) {
        var name = nameKey(player.getName());
        if (this.playersByName.get(name) != player || this.playersByUUID.get(player.getUUID()) != player) {
            return false;
        }
        this.playersByName.remove(name);
        this.playersByUUID.remove(player.getUUID());
        Optional.ofNullable(this.playersByCreator.get(player.getCreator().getName())).ifPresent(players -> players.remove(player));

        var creatorUuid = creatorUuid(player.getCreator());
        if (creatorUuid != null) {
            Optional.ofNullable(this.playersByCreatorUuid.get(creatorUuid)).ifPresent(players -> players.remove(player));
        }
        return true;
    }

    /**
     * 通过 UUID 移除假人
     *
     * @param uuid UUID
     * @return 被移除的假人
     */
    public @Nullable Fakeplayer removeByUUID(@NotNull UUID uuid) {
        var player = getByUUID(uuid);
        if (player == null) {
            return null;
        }
        this.remove(player);
        return player;
    }

    /**
     * 获取创建的数量
     *
     * @param creator 玩家名称
     * @return 数量
     */
    public int countByCreator(@NotNull String creator) {
        return this.getByCreator(creator).size();
    }

    /**
     * 获取所有假人
     *
     * @return 假人
     */
    public @NotNull @Unmodifiable List<Fakeplayer> getAll() {
        return List.copyOf(this.playersByUUID.values());
    }

    public @NotNull Stream<Fakeplayer> stream() {
        return this.playersByUUID.values().stream();
    }

    public int getSize() {
        return this.playersByUUID.size();
    }

}
