package io.github.hello09x.fakeplayer.core.manager;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.fakeplayer.api.spi.ActionSetting;
import io.github.hello09x.fakeplayer.api.spi.ActionType;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.util.Schedulers;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.constant.MetadataKeys;
import io.github.hello09x.fakeplayer.core.entity.FakeplayerTicker;
import io.github.hello09x.fakeplayer.core.util.OfflineCreator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.server.ServerLoadEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Singleton
public class FakeplayerRestoreManager implements Listener {

    private final static Logger log = Main.getInstance().getLogger();

    private final static String FILE_NAME = "restore-fakeplayers.txt";
    private final static String SEPARATOR = "|";
    private final static long SNAPSHOT_INTERVAL_TICKS = 20L * 30;
    private final static long MINIMUM_AGE_TICKS = 100L;

    private final FakeplayerManager manager;
    private final FakeplayerConfig config;
    private final ActionManager actionManager;
    private final FakeplayerList playerList;

    private volatile String lastSnapshot;

    @Inject
    public FakeplayerRestoreManager(
            FakeplayerManager manager,
            FakeplayerConfig config,
            ActionManager actionManager,
            FakeplayerList playerList
    ) {
        this.manager = manager;
        this.config = config;
        this.actionManager = actionManager;
        this.playerList = playerList;
    }
  
    public void start() {
        Schedulers.asyncTimer(
                Main.getInstance(),
                SNAPSHOT_INTERVAL_TICKS,
                SNAPSHOT_INTERVAL_TICKS,
                this::saveSnapshot
        );
    }

    public void saveSnapshot() {
        if (!config.isRestoreOnStart()) {
            return;
        }
        var lines = new ArrayList<String>();
        for (var player : manager.getAll()) {
            var creator = manager.getCreatorName(player);
            if (creator == null) {
                continue;
            }

            var spawnedAt = player.getMetadata(MetadataKeys.SPAWNED_AT).stream()
                    .findFirst()
                    .map(value -> value.asLong())
                    .orElse(0L);

            if (Bukkit.getCurrentTick() - spawnedAt < MINIMUM_AGE_TICKS) {
                log.info("Skipping snapshot for freshly spawned fake player %s".formatted(player.getName()));
                continue;
            }

            UUID creatorUuid = null;
            if (this.playerList != null) {
                var fakeplayer = this.playerList.getByUUID(player.getUniqueId());
                if (fakeplayer != null) {
                    creatorUuid = FakeplayerList.creatorUuid(fakeplayer.getCreator());
                }
            }

            var location = player.getLocation();
            lines.add(String.join(SEPARATOR,
                    player.getName(),
                    creator,
                    location.getWorld().getName(),
                    String.valueOf(location.getX()),
                    String.valueOf(location.getY()),
                    String.valueOf(location.getZ()),
                    String.valueOf(location.getYaw()),
                    String.valueOf(location.getPitch()),
                    this.encodeActions(actionManager.getSettings(player)),
                    creatorUuid == null ? "" : creatorUuid.toString()
            ));
        }

        // 内容没变就不落盘, 避免每 30 秒无谓写文件
        var content = String.join("\n", lines);
        if (content.equals(this.lastSnapshot)) {
            return;
        }
        this.lastSnapshot = content;
        this.write(lines);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(@NotNull ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        if (!config.isRestoreOnStart()) {
            return;
        }
        var entries = this.read();
        if (entries.isEmpty()) {
            return;
        }

        log.info("Restoring %d fake player(s) from the last session...".formatted(entries.size()));
        var lifespan = Optional.ofNullable(config.getLifespan()).map(Duration::toMillis).orElse(FakeplayerTicker.NON_REMOVE_AT);

        Schedulers.async(Main.getInstance(), () -> {
            var restored = 0;
            for (var entry : entries) {
                try {
                    var location = this.resolveLocation(entry);

                    var creatorName = entry.creator();
                    if (entry.creatorUuid() != null) {
                        var currentName = Bukkit.getOfflinePlayer(entry.creatorUuid()).getName();
                        if (currentName != null && !currentName.isBlank()) {
                            creatorName = currentName;
                        }
                    }

                    var player = manager.spawnAsync(new OfflineCreator(creatorName, entry.creatorUuid()), entry.name(), location, lifespan)
                                         .get(30, TimeUnit.SECONDS);
                    if (player != null) {
                        restored++;
                        this.restoreActions(player, entry.actions());
                        log.info("Restored fake player %s (creator: %s)".formatted(entry.name(), creatorName));
                    }
                } catch (Exception e) {
                    log.warning("Failed to restore fake player %s: %s".formatted(entry.name(), e.getMessage()));
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.info("Restore finished: %d/%d fake player(s) restored".formatted(restored, entries.size()));
        });
    }

    private @NotNull Location resolveLocation(@NotNull Entry entry) {
        var world = Bukkit.getWorld(entry.world());
        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }
        return new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
    }

    private void write(@NotNull List<String> lines) {
        try {
            var folder = Main.getInstance().getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                return;
            }
            var tmp = folder.toPath().resolve(FILE_NAME + ".tmp");
            var target = folder.toPath().resolve(FILE_NAME);
            Files.writeString(tmp, String.join("\n", lines), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warning("Failed to save fake player restore list: " + e);
        }
    }

    private @NotNull List<Entry> read() {
        var file = Main.getInstance().getDataFolder().toPath().resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            var entries = new ArrayList<Entry>();
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var entry = this.parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (IOException e) {
            log.warning("Failed to read fake player restore list: " + e);
            return List.of();
        }
    }

    private @Nullable Entry parse(@NotNull String line) {
        var parts = line.trim().split("\\" + SEPARATOR);
        if (parts.length < 8 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }

        List<ActionEntry> actions = List.of();
        UUID creatorUuid = null;

        if (parts.length > 8) {
            var field = parts[8].trim();
            // Alte Test-Dateien: Index 8 enthielt direkt die Ersteller-UUID
            if (field.length() == 36 && field.indexOf(',') < 0 && parseUuid(field) != null) {
                creatorUuid = parseUuid(field);
            } else {
                actions = this.decodeActions(field);
            }
        }
        if (parts.length > 9 && !parts[9].isBlank()) {
            creatorUuid = parseUuid(parts[9].trim());
        }

        try {
            return new Entry(
                    parts[0],
                    parts[1],
                    parts[2],
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]),
                    Float.parseFloat(parts[6]),
                    Float.parseFloat(parts[7]),
                    actions,
                    creatorUuid
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private @Nullable UUID parseUuid(@NotNull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warning("restore-fakeplayers.txt contains an illegal creator UUID: " + value);
            return null;
        }
    }

    /**
     * 恢复假人上次的动作; 必须在假人所属的实体线程执行
     */
    private void restoreActions(@NotNull Player player, @NotNull List<ActionEntry> actions) {
        if (actions.isEmpty()) {
            return;
        }
        Schedulers.entity(Main.getInstance(), player, () -> {
            if (!player.isValid()) {
                return;
            }
            for (var action : actions) {
                try {
                    actionManager.setAction(player, action.type(), action.setting());
                } catch (RuntimeException e) {
                    log.warning("Failed to restore action %s of %s: %s".formatted(action.type(), player.getName(), e.getMessage()));
                }
            }
        });
    }

    /**
     * 编码为 {@code TYPE,maximum,remains,interval,wait,message,tx,ty,tz}, 多个动作以 {@code ;} 分隔。
     * <p>只有 message 是自由文本, 用 Base64 避免与分隔符冲突。</p>
     */
    private @NotNull String encodeActions(@NotNull Map<ActionType, ActionSetting> settings) {
        if (settings.isEmpty()) {
            return "";
        }
        var encoded = new StringBuilder();
        for (var entry : settings.entrySet()) {
            var setting = entry.getValue();
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(entry.getKey().name()).append(',')
                   .append(setting.maximum).append(',')
                   .append(setting.remains).append(',')
                   .append(setting.interval).append(',')
                   .append(setting.wait).append(',')
                   .append(setting.message == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(setting.message.getBytes(StandardCharsets.UTF_8))).append(',')
                   .append(setting.targetX == null ? "" : setting.targetX).append(',')
                   .append(setting.targetY == null ? "" : setting.targetY).append(',')
                   .append(setting.targetZ == null ? "" : setting.targetZ);
        }
        return encoded.toString();
    }

    private @NotNull List<ActionEntry> decodeActions(@NotNull String encoded) {
        if (encoded.isBlank()) {
            return List.of();
        }
        var actions = new ArrayList<ActionEntry>();
        for (var part : encoded.split(";")) {
            var fields = part.split(",", -1);
            if (fields.length < 9) {
                continue;
            }
            try {
                var setting = new ActionSetting(
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]),
                        fields[5].isEmpty() ? null : new String(Base64.getUrlDecoder().decode(fields[5]), StandardCharsets.UTF_8)
                );
                setting.remains = Integer.parseInt(fields[2]);
                setting.targetX = fields[6].isEmpty() ? null : Double.parseDouble(fields[6]);
                setting.targetY = fields[7].isEmpty() ? null : Double.parseDouble(fields[7]);
                setting.targetZ = fields[8].isEmpty() ? null : Double.parseDouble(fields[8]);
                actions.add(new ActionEntry(ActionType.valueOf(fields[0]), setting));
            } catch (RuntimeException e) {
                log.warning("Failed to decode restored action: " + part);
            }
        }
        return actions;
    }

    private record ActionEntry(
            @NotNull ActionType type,
            @NotNull ActionSetting setting
    ) {
    }

    private record Entry(
            @NotNull String name,
            @NotNull String creator,
            @NotNull String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            @NotNull List<ActionEntry> actions,
            @Nullable UUID creatorUuid
    ) {
    }

}
