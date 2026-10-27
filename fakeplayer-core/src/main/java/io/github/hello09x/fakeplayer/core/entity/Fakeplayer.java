package io.github.hello09x.fakeplayer.core.entity;

import io.github.hello09x.devtools.command.exception.CommandException;
import io.github.hello09x.fakeplayer.core.util.Schedulers;
import io.github.hello09x.devtools.core.utils.WorldUtils;
import io.github.hello09x.fakeplayer.api.spi.*;
import io.github.hello09x.fakeplayer.core.Main;
import io.github.hello09x.fakeplayer.core.compat.login.LoginCompatManager;
import io.github.hello09x.fakeplayer.core.config.FakeplayerConfig;
import io.github.hello09x.fakeplayer.core.config.PreventKicking;
import io.github.hello09x.fakeplayer.core.constant.MetadataKeys;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerAutofishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerReplenishManager;
import io.github.hello09x.fakeplayer.core.manager.FakeplayerSkinManager;
import io.github.hello09x.fakeplayer.core.manager.action.ActionManager;
import io.github.hello09x.fakeplayer.core.manager.naming.NameManager;
import io.github.hello09x.fakeplayer.core.manager.naming.SequenceName;
import io.github.hello09x.fakeplayer.core.util.Attributes;
import io.github.hello09x.fakeplayer.core.util.InternalAddressGenerator;
import io.github.hello09x.fakeplayer.core.util.OfflineCreator;
import lombok.Getter;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnknownNullability;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public class Fakeplayer {

    private final static InternalAddressGenerator ipGen = new InternalAddressGenerator();
    private final FakeplayerConfig config;
    private final NMSBridge bridge;
    private final FakeplayerSkinManager skinManager;
    private final FakeplayerReplenishManager replenishManager;
    private final FakeplayerAutofishManager autofishManager;
    private final LoginCompatManager loginCompatManager;
    private final ActionManager actionManager;

    @NotNull
    private final NameManager nameManager;


    @NotNull
    @Getter
    private final CommandSender creator;

    @NotNull
    @Getter
    private final NMSServerPlayer handle;

    @NotNull
    @Getter
    private final Player player;

    @NotNull
    @Getter
    private final String creatorIp;

    @NotNull
    @Getter
    private final SequenceName sequenceName;

    @NotNull
    private final FakeplayerTicker ticker;

    @Getter
    @NotNull
    private final String name;

    @NotNull
    private final UUID uuid;

    @Getter
    @UnknownNullability
    private NMSNetwork network;

    private volatile int firstPing = -1;
    private volatile int currentPing = -1;

    public Fakeplayer(
            @NotNull CommandSender creator,
            @NotNull String creatorIp,
            @NotNull SequenceName sequenceName,
            long lifespan
    ) {
        var injector = Main.getInjector();
        this.config = injector.getInstance(FakeplayerConfig.class);
        this.bridge = injector.getInstance(NMSBridge.class);
        this.skinManager = injector.getInstance(FakeplayerSkinManager.class);
        this.replenishManager = injector.getInstance(FakeplayerReplenishManager.class);
        this.autofishManager = injector.getInstance(FakeplayerAutofishManager.class);
        this.loginCompatManager = injector.getInstance(LoginCompatManager.class);
        this.actionManager = injector.getInstance(ActionManager.class);
        this.nameManager = injector.getInstance(NameManager.class);

        this.name = sequenceName.name();
        this.uuid = sequenceName.uuid();

        this.creator = creator;
        this.creatorIp = creatorIp;
        this.sequenceName = sequenceName;
        this.handle = bridge.fromServer(Bukkit.getServer()).newPlayer(uuid, name);
        this.player = handle.getPlayer();

        this.ticker = new FakeplayerTicker(this, lifespan);
        this.player.setPersistent(config.isPersistData());
        this.player.setSleepingIgnored(true);
        this.handle.setPlayBefore();
        this.handle.disableAdvancements(Main.getInstance());
    }

    private void debug(@NotNull String message) {
        if (config.isDebug()) {
            Main.getInstance().getLogger().info("[spawn-tp] " + message);
        }
    }

    private void applyConfiguredPing() {
        var pings = config.getCustomPing();
        if (pings == null || pings.isEmpty()) {
            return;
        }

        var ping = pings.size() == 1
                ? pings.get(0)
                : ThreadLocalRandom.current().nextInt(pings.get(0), pings.get(1) + 1);
        this.setPing(ping);
    }

    public void setPing(int ping) {
        this.firstPing = ping;
        this.currentPing = ping;
        this.pushPing(ping, true);
    }

    public void updateDynamicPing() {
        var base = this.firstPing;
        if (base < 0) {
            return;
        }

        var next = Math.max(0, this.currentPing + ThreadLocalRandom.current().nextInt(-2, 3));
        next = Math.min(base + 8, Math.max(base - 8, next));
        if (next == this.currentPing) {
            return;
        }

        this.currentPing = next;
        this.pushPing(next, false);
    }

    private void pushPing(int ping, boolean broadcast) {
        var network = this.network;
        if (network == null) {
            return;
        }

        network.getServerGamePacketListener().setPing(ping, broadcast);
    }

    public CompletableFuture<Void> spawnAsync(@NotNull SpawnOption option) {
        var address = ipGen.next();
        return Schedulers
                .callAsync(Main.getInstance(), () -> {
                    if (!loginCompatManager.prepare(player, address)) {
                        throw new CommandException(text(
                                "Could not prepare login compatibility for fake player " + name
                        ));
                    }

                    var event = this.callPreLoginEvent(address);
                    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                        throw new CommandException(translatable(
                                "fakeplayer.command.spawn.error.disallowed",
                                text(player.getName(), WHITE),
                                event.kickMessage()
                        ).color(RED));
                    }
                    if (!loginCompatManager.afterPreLogin(player)) {
                        throw new CommandException(text(
                                "Could not complete login compatibility for fake player " + name
                        ));
                    }
                })
                .thenComposeAsync(nul -> Schedulers.callAt(Main.getInstance(), option.spawnAt(), () -> {
                    this.player.setMetadata(MetadataKeys.SPAWNED_AT, new FixedMetadataValue(Main.getInstance(), Bukkit.getCurrentTick()));
                    {
                        var event = this.callLoginEvent(address);
                        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED && config.getPreventKicking().ordinal() < PreventKicking.ON_SPAWNING.ordinal()) {
                            throw new CommandException(translatable(
                                    "fakeplayer.command.spawn.error.disallowed", RED,
                                    text(player.getName(), WHITE),
                                    event.kickMessage()
                            ));
                        }
                    }

                    if (config.isDropInventoryOnQuiting() && !nameManager.isRealPlayerUUID(this.uuid)) {
                        this.player.getInventory().clear();
                    }

                    this.player.setInvulnerable(option.invulnerable());
                    this.player.setCollidable(option.collidable());
                    this.player.setCanPickupItems(option.pickupItems());
                    if (option.lookAtEntity()) {
                        actionManager.setAction(player, ActionType.LOOK_AT_NEAREST_ENTITY, ActionSetting.continuous());
                    }
                    if (option.skin()) {
                        skinManager.useDefaultSkin(creator, player);
                    }
                    if (option.replenish()) {
                        replenishManager.setReplenish(player, true);
                    }
                    if (option.replaceTools()) {
                        replenishManager.setReplaceTools(player, true);
                    }
                    if (option.autofish()) {
                        autofishManager.setAutofish(player, true);
                    }

                    this.network = bridge.createNetwork(address);
                    this.network.placeNewPlayer(Bukkit.getServer(), this.player);
                    this.applyConfiguredPing();
                    this.player.setHealth(Optional.ofNullable(this.player.getAttribute(Attributes.maxHealth()))
                                                  .map(AttributeInstance::getValue)
                                                  .orElse(20D));
                    this.player.setFoodLevel(20);
                    this.setupName();
                    this.handle.setupClientOptions();

                    this.teleportToSpawnpoint(option.spawnAt().clone())
                        .whenComplete((ignored, error) -> this.ticker.run());
                }))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        Schedulers.global(Main.getInstance(), () -> {
                            if (!player.isOnline()) {
                                loginCompatManager.cleanup(player);
                            }
                        });
                    }
                });
    }

    private @NotNull CompletableFuture<Boolean> teleportToSpawnpoint(@NotNull Location to) {
        var logger = Main.getInstance().getLogger();
        var from = this.player.getLocation();
        var chain = CompletableFuture.completedFuture(true);

        var dimensionHop = Main.getInstance().getConfig().getBoolean("spawn-dimension-hop", true);

        debug("%s: placed at %s %.1f %.1f %.1f, target %s %.1f %.1f %.1f, dimensionHop=%s".formatted(
                name,
                from.getWorld().getName(), from.getX(), from.getY(), from.getZ(),
                to.getWorld().getName(), to.getX(), to.getY(), to.getZ(),
                dimensionHop
        ));

        if (dimensionHop && from.getWorld().equals(to.getWorld())) {
            var otherWorld = WorldUtils.getOtherWorld(from.getWorld());
            if (otherWorld == null) {
                logger.warning("[spawn-tp] %s: no other world found for the dimension hop".formatted(name));
                this.creator.sendMessage(translatable(
                        "fakeplayer.command.spawn.error.no-mob-spawning-ability",
                        text(player.getName(), WHITE)
                ).color(GRAY));
            } else {
                chain = this.player.teleportAsync(otherWorld.getSpawnLocation()).thenApply(success -> {
                    debug("%s: hop to %s -> %s".formatted(name, otherWorld.getName(), success ? "success" : "FAILED"));
                    return success;
                });
            }
        }

        var result = chain.thenCompose(ignored -> this.player.teleportAsync(to))
             .thenCompose(success -> {
                 if (success && this.player.getWorld().equals(to.getWorld())) {
                     return CompletableFuture.completedFuture(true);
                 }

                 logger.warning("[spawn-tp] %s: teleport reported %s but player is in %s, retrying in 20 ticks".formatted(
                         name,
                         success,
                         this.player.getWorld().getName()
                 ));

                 var retry = new CompletableFuture<Boolean>();
                 Schedulers.globalLater(Main.getInstance(), 20L, () -> {
                     this.player.teleportAsync(to).whenComplete((retrySuccess, error) -> {
                         if (error != null) {
                             retry.completeExceptionally(error);
                         } else {
                             retry.complete(retrySuccess);
                         }
                     });
                 });
                 return retry;
             })
             .thenApply(success -> {
                 var reallyThere = this.player.getWorld().equals(to.getWorld());
                 var ok = success && reallyThere;

                 debug("%s: final teleport -> %s, actual world %s".formatted(
                         name,
                         ok ? "success" : "FAILED",
                         this.player.getWorld().getName()
                 ));

                 if (ok) {
                     to.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F);
                 } else {
                     this.creator.sendMessage(translatable(
                             "fakeplayer.command.spawn.error.teleport-failed",
                             text(player.getName(), WHITE)
                     ).color(GRAY));
                 }
                 return ok;
             })
             .exceptionally(throwable -> {
                 logger.warning("[spawn-tp] %s: teleport threw %s".formatted(name, throwable));
                 return false;
             });

        this.player.getScheduler().runDelayed(Main.getInstance(), task -> {
            var now = this.player.getLocation();
            var sameWorld = now.getWorld().equals(to.getWorld());
            var distance = sameWorld ? now.distance(to) : -1.0D;
            var ok = sameWorld && distance < 5.0D;
            var status = ok ? "OK" : (sameWorld ? "WRONG POSITION" : "WRONG WORLD");
            var message = "%s: check after 100 ticks: in %s %.1f %.1f %.1f, expected %s %.1f %.1f %.1f -> %s".formatted(
                    name,
                    now.getWorld().getName(), now.getX(), now.getY(), now.getZ(),
                    to.getWorld().getName(), to.getX(), to.getY(), to.getZ(),
                    status
            );
            if (ok) {
                debug(message);
            } else {
                logger.warning("[spawn-tp] " + message);
            }
        }, null, 100L);

        return result;
    }

    public boolean isOnline() {
        return this.player.isOnline();
    }

    public int getTickCount() {
        return handle.getTickCount();
    }

    private @NotNull AsyncPlayerPreLoginEvent callPreLoginEvent(@NotNull InetAddress address) {
        var event = new AsyncPlayerPreLoginEvent(
                this.name,
                address,
                this.uuid
        );
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private @NotNull PlayerLoginEvent callLoginEvent(@NotNull InetAddress address) {
        var event = new PlayerLoginEvent(
                player,
                address.getHostAddress(),
                address
        );
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    public boolean isCreatedBy(@NotNull CommandSender sender) {
        if (this.creator instanceof Player pc && sender instanceof Player ps) {
            return pc.getUniqueId().equals(ps.getUniqueId());
        }

        if (this.creator instanceof OfflineCreator) {
            return this.creator.getName().equals(sender.getName());
        }

        return creator.getClass() == sender.getClass()
                && creator.getName().equals(sender.getName());
    }

    public @NotNull UUID getUUID() {
        return uuid;
    }

    private void setupName() {
        var displayName = text(player.getName(), config.getNameStyleColor(), config.getNameStyleDecorations().toArray(new TextDecoration[0]));
        player.playerListName(displayName);
        player.displayName(displayName);
        player.customName(displayName);
    }
}
