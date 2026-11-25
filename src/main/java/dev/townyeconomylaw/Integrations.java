package dev.townyeconomylaw;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import static dev.townyeconomylaw.State.require;

final class Integrations {
    record Nation(UUID id, String name, UUID leader) {}
    record ShopPrices(String currency, double average, int count) {
        String display() {
            return String.format(Locale.ROOT, "平均単価 %.2f %s / %d 店舗", average, currency, count);
        }

        ShopPrices include(double price) {
            return new ShopPrices(currency, average + (price - average) / (count + 1), count + 1);
        }
    }

    record ShopItem(ItemStack icon, List<ShopPrices> prices) {}

    private final Object towny;
    private final ClassLoader townyLoader;
    private final Class<?> economyType;

    Integrations() {
        Plugin townyPlugin = Bukkit.getPluginManager().getPlugin("Towny");
        Plugin vaultPlugin = Bukkit.getPluginManager().getPlugin("Vault");
        try {
            townyLoader = townyPlugin.getClass().getClassLoader();
            Class<?> api = townyClass("TownyAPI");
            towny = api.getMethod("getInstance").invoke(null);
            economyType = Class.forName("net.milkbowl.vault.economy.Economy", true,
                vaultPlugin.getClass().getClassLoader());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Towny / Vault API unavailable", error);
        }
    }

    Nation nation(UUID player) {
        Object resident = call(towny, "getResident", UUID.class, player);
        return resident == null ? null : nation(call(resident, "getNationOrNull"));
    }

    record Town(UUID id, String name, UUID mayor, Nation nation) {}

    Town town(UUID residentId) {
        Object resident = call(towny, "getResident", UUID.class, residentId);
        return resident == null ? null : townInfo(call(resident, "getTownOrNull"));
    }

    Town town(String name) {
        return townInfo(call(towny, "getTown", String.class, name));
    }

    Town townById(UUID id) {
        return townInfo(call(towny, "getTown", UUID.class, id));
    }

    Nation nation(String name) {
        return nation(call(towny, "getNation", String.class, name));
    }

    Nation nationById(UUID id) {
        return nation(call(towny, "getNation", UUID.class, id));
    }

    List<UUID> citizens(Nation nation) {
        Object nativeNation = call(towny, "getNation", UUID.class, nation.id());
        return ((List<?>) call(nativeNation, "getResidents")).stream()
            .map(resident -> (UUID) call(resident, "getUUID")).toList();
    }

    private Town townInfo(Object town) {
        if (town == null) return null;
        Object mayor = call(town, "getMayor");
        return new Town((UUID) call(town, "getUUID"), (String) call(town, "getName"),
            mayor == null ? null : (UUID) call(mayor, "getUUID"), nation(call(town, "getNationOrNull")));
    }

    Nation createNation(Player actor, String name, Town capital) {
        Object town = call(towny, "getTown", UUID.class, capital.id());
        invoke(townyType("command.NationCommand"), null, "newNation",
            new Class<?>[]{org.bukkit.command.CommandSender.class, String.class, townyType("object.Town"), boolean.class},
            actor, name, town, true);
        Nation created = townById(capital.id()).nation();
        require(created != null, "Towny で建国できませんでした。");
        return created;
    }

    Town createTown(Player founder, String name, Nation parent) {
        Object resident = call(towny, "getResident", UUID.class, founder.getUniqueId());
        require(resident != null, "対象者が Towny に未登録です。");
        invoke(townyType("command.TownCommand"), null, "newTown",
            new Class<?>[]{Player.class, String.class, townyType("object.Resident"), boolean.class},
            founder, name, resident, true);
        Town created = town(founder.getUniqueId());
        require(created != null, "Towny で町を作成できませんでした。");
        Object town = call(towny, "getTown", UUID.class, created.id());
        call(town, "setNation", townyType("object.Nation"), call(towny, "getNation", UUID.class, parent.id()));
        call(town, "save");
        call(call(towny, "getNation", UUID.class, parent.id()), "save");
        return townById(created.id());
    }

    void detachTown(Town town) {
        Object nation = call(towny, "getNation", UUID.class, town.nation().id());
        Object nativeTown = call(towny, "getTown", UUID.class, town.id());
        require(!nativeTown.equals(call(nation, "getCapital")), "首都は離脱できません。");
        call(nativeTown, "removeNation");
        call(nativeTown, "save");
        call(nation, "save");
    }

    void annexPlot(Location location, Nation source, Nation parent) {
        Object plot = call(towny, "getTownBlock", Location.class, location);
        Nation territory = plotNation(location);
        require(plot != null && territory != null && territory.id().equals(source.id()), "指定した傀儡国のプロットに立ってください。");
        require(!(boolean) call(plot, "isHomeBlock") && !(boolean) call(plot, "isOutpost")
            && call(plot, "getResidentOrNull") == null && call(plot, "getPlotObjectGroup") == null,
            "首都・町の中心、前哨地、私有地、グループ所属のプロットは併合できません。");
        Object from = call(plot, "getTownOrNull");
        Object to = call(call(towny, "getNation", UUID.class, parent.id()), "getCapital");
        require(to != null, "併合先の首都が見つかりません。");
        call(plot, "setTown", townyType("object.Town"), to);
        require(to.equals(call(plot, "getTownOrNull")), "Towny がプロットの移管を完了できませんでした。");
        call(plot, "save");
        call(from, "save");
        call(to, "save");
    }

    void changeNationLeader(ArrayDeque<Runnable> undo, UUID country, UUID expected, UUID successor) {
        Object nation = call(towny, "getNation", UUID.class, country);
        Nation current = nation(nation);
        require(current != null && expected.equals(current.leader()), "国のリーダーが変更されています。");
        Town nextTown = town(successor);
        require(nextTown != null && successor.equals(nextTown.mayor()) && nextTown.nation() != null
            && country.equals(nextTown.nation().id()), "後継者は同じ国の町長である必要があります。");

        Object previous = call(nation, "getKing");
        Object next = call(towny, "getResident", UUID.class, successor);
        Class<?> residentType = townyType("object.Resident");
        Class<?> positionType = townyType("object.Position");
        Object spawn = call(nation, "spawnPosition");
        undo.push(() -> {
            if (!expected.equals(nation(nation).leader())) {
                call(nation, "setKing", residentType, previous);
                call(nation, "spawnPosition", positionType, spawn);
                call(nation, "save");
            }
        });
        call(nation, "setKing", residentType, next);
        if (!successor.equals(nation(nation).leader())) {
            throw new IllegalStateException("Towny did not apply the approved leadership change");
        }
    }

    void guardLocalDepartures(Plugin plugin, java.util.function.BiPredicate<UUID, UUID> registered) {
        org.bukkit.event.Listener listener = new org.bukkit.event.Listener() {};
        for (String name : List.of("NationPreTownLeaveEvent", "NationPreTownKickEvent")) {
            try {
                Class<? extends org.bukkit.event.Event> type = townyClass("event.nation." + name)
                    .asSubclass(org.bukkit.event.Event.class);
                Bukkit.getPluginManager().registerEvent(type, listener, org.bukkit.event.EventPriority.HIGHEST,
                    (ignored, event) -> {
                        UUID nationId = (UUID) call(call(event, "getNation"), "getUUID");
                        UUID townId = (UUID) call(call(event, "getTown"), "getUUID");
                        if (registered.test(nationId, townId)) {
                            call(event, "setCancelMessage", String.class, "自治体の離脱は /tel plot decentralization remove で承認してください。");
                            ((org.bukkit.event.Cancellable) event).setCancelled(true);
                        }
                    }, plugin, true);
            } catch (ClassNotFoundException error) {
                plugin.getLogger().warning("Towny event unavailable: " + name);
            }
        }
    }

    private Class<?> townyClass(String type) throws ClassNotFoundException {
        return Class.forName("com.palmergames.bukkit.towny." + type, true, townyLoader);
    }

    private Class<?> townyType(String type) {
        try {
            return townyClass(type);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Towny API unavailable: " + type, error);
        }
    }

    Nation plotNation(Location location) {
        Object plot = call(towny, "getTownBlock", Location.class, location);
        Object town = plot == null ? null : call(plot, "getTownOrNull");
        return town == null ? null : nation(call(town, "getNationOrNull"));
    }

    record PlotAccess(String key, UUID town, UUID resident, UUID controller) {
        String ownership() {
            return town + ":" + (resident == null ? "" : resident);
        }
    }

    PlotAccess plotAccess(Location location) {
        Object plot = call(towny, "getTownBlock", Location.class, location);
        return plot == null ? null : plotAccess(plotKey(location, plot), plot);
    }

    private PlotAccess plotAccess(String key, Object plot) {
        Object town = call(plot, "getTownOrNull");
        if (town == null) return null;
        Object resident = call(plot, "getResidentOrNull");
        Object controller = resident == null ? call(town, "getMayor") : resident;
        return new PlotAccess(key, (UUID) call(town, "getUUID"),
            resident == null ? null : (UUID) call(resident, "getUUID"),
            controller == null ? null : (UUID) call(controller, "getUUID"));
    }

    boolean registerPlotTenancy(Plugin plugin, java.util.function.BiPredicate<UUID, PlotAccess> allowed) {
        try {
            Class<? extends org.bukkit.event.Event> type = townyClass("event.player.PlayerCacheGetTownBlockStatusEvent")
                .asSubclass(org.bukkit.event.Event.class);
            Class<?> coordinateType = townyClass("object.WorldCoord");
            Class<?> statusType = type.getMethod("getTownBlockStatus").getReturnType();
            Object trusted = statusType.getField("PLOT_TRUSTED").get(null);
            type.getMethod("setTownBlockStatus", statusType);
            Bukkit.getPluginManager().getPlugin("Towny").getClass().getMethod("deleteCache", UUID.class);

            org.bukkit.event.Listener listener = new org.bukkit.event.Listener() {};
            Bukkit.getPluginManager().registerEvent(type, listener, org.bukkit.event.EventPriority.HIGH,
                (ignored, event) -> {
                    String status = ((Enum<?>) call(event, "getTownBlockStatus")).name();
                    if (!List.of("OUTSIDER", "PLOT_FRIEND", "PLOT_TOWN", "PLOT_ALLY", "TOWN_RESIDENT",
                            "TOWN_ALLY", "TOWN_NATION").contains(status)) return;

                    Player player = (Player) call(event, "getPlayer");
                    Object coordinate = call(event, "getWorldCoord");
                    org.bukkit.World world = Bukkit.getWorld((String) call(coordinate, "getWorldName"));
                    if (world == null) return;
                    Object plot = call(towny, "getTownBlock", coordinateType, coordinate);
                    if (plot == null) return;
                    String key = world.getUID() + ":" + call(coordinate, "getX") + ":" + call(coordinate, "getZ");
                    PlotAccess access = plotAccess(key, plot);
                    if (access != null && allowed.test(player.getUniqueId(), access)) {
                        call(event, "setTownBlockStatus", statusType, trusted);
                    }
                }, plugin);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            plugin.getLogger().warning("Towny plot rental access unavailable: " + error.getMessage());
            return false;
        }
    }

    void invalidatePlotTenant(UUID tenant) {
        call(Bukkit.getPluginManager().getPlugin("Towny"), "deleteCache", UUID.class, tenant);
    }

    String plotKey(Location location) {
        Object plot = call(towny, "getTownBlock", Location.class, location);
        return plot == null ? null : plotKey(location, plot);
    }

    String plotCoordinate(Location location) {
        Class<?> type = townyType("object.WorldCoord");
        Object coordinate = invoke(type, null, "parseWorldCoord", new Class<?>[]{Location.class}, location);
        return location.getWorld().getUID() + ":" + call(coordinate, "getX") + ":" + call(coordinate, "getZ");
    }

    private String plotKey(Location location, Object plot) {
        Object coord = call(plot, "getCoord");
        return location.getWorld().getUID() + ":" + call(coord, "getX") + ":" + call(coord, "getZ");
    }

    private Nation nation(Object nation) {
        if (nation == null) return null;
        Object king = call(nation, "getKing");
        return new Nation((UUID) call(nation, "getUUID"), (String) call(nation, "getName"),
            king == null ? null : (UUID) call(king, "getUUID"));
    }

    void economy(ArrayDeque<Runnable> undo, UUID account, long cents, boolean deposit) {
        if (cents == 0) return;
        RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyType);
        require(registration != null, "Vault の経済プロバイダーが登録されていません。");
        Object provider = registration.getProvider();
        int digits = (int) invoke(economyType, provider, "fractionalDigits", new Class<?>[0]);
        require(digits < 0 || digits >= 2, "小数第2位に対応する Vault 経済プロバイダーが必要です。");
        OfflinePlayer player = Bukkit.getOfflinePlayer(account);
        require(move(provider, player, cents, deposit),
            deposit ? "Vault への入金に失敗しました。" : "Vault から出金できません。残高を確認してください。");
        undo.push(() -> {
            if (!move(provider, player, cents, !deposit)) {
                throw new IllegalStateException("Vault compensation failed for " + account);
            }
        });
    }

    private boolean move(Object provider, OfflinePlayer player, long cents, boolean deposit) {
        Object result = invoke(economyType, provider, deposit ? "depositPlayer" : "withdrawPlayer",
            new Class<?>[]{OfflinePlayer.class, double.class}, player, cents / 100.0);
        return (boolean) call(result, "transactionSuccess");
    }

    List<ShopPrices> prices(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        require(!hand.getType().isAir(), "メインハンドに調べたいアイテムを持ってください。");
        return shopItems(player, hand).stream().findFirst().map(ShopItem::prices).orElseGet(List::of);
    }

    List<ShopItem> shopItems(Player player, ItemStack filter) {
        Plugin shopPlugin = Bukkit.getPluginManager().getPlugin("QuickShop-Hikari");
        if (shopPlugin == null || !shopPlugin.isEnabled()) {
            shopPlugin = Bukkit.getPluginManager().getPlugin("QuickShop");
        }
        require(shopPlugin != null && shopPlugin.isEnabled(), "QuickShop-Hikari または QuickShop が必要です。");
        Object manager = call(shopCore(shopPlugin), "getShopManager");
        Iterable<?> shops = (Iterable<?>) call(manager, "getAllShops");
        Map<ItemStack, Map<String, ShopPrices>> items = new LinkedHashMap<>();
        for (Object shop : shops) {
            Object shopLocation = call(shop, "getLocation");
            if (!(shopLocation instanceof Location location) || !location.isWorldLoaded()
                    || !player.getWorld().equals(location.getWorld())) continue;
            Object shopItem = call(shop, "getItem");
            if (!(shopItem instanceof ItemStack item) || item.getAmount() < 1 || item.getType().isAir()
                    || (filter != null && !filter.isSimilar(item))) continue;
            double price = ((Number) call(shop, "getPrice")).doubleValue() / item.getAmount();
            if (!Double.isFinite(price) || price < 0) continue;
            String currency = (String) call(shop, "getCurrency");
            String label = currency == null || currency.isBlank() ? "既定通貨" : currency;
            ItemStack icon = item.clone();
            icon.setAmount(1);
            Map<String, ShopPrices> averages = items.computeIfAbsent(icon, ignored -> new TreeMap<>());
            ShopPrices prices = averages.getOrDefault(label, new ShopPrices(label, 0, 0));
            averages.put(label, prices.include(price));
        }
        return items.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getType().name()))
            .map(entry -> new ShopItem(entry.getKey(), List.copyOf(entry.getValue().values())))
            .toList();
    }

    private static Object shopCore(Plugin plugin) {
        // Hikari 6.3 loads a Bukkit bootstrap; earlier builds expose the manager on the plugin.
        try {
            plugin.getClass().getMethod("getQuickShop");
        } catch (NoSuchMethodException olderPlugin) {
            return plugin;
        }
        Object core = call(plugin, "getQuickShop");
        require(core != null, "QuickShop の初期化が完了していません。");
        return core;
    }

    private static Object call(Object target, String method) {
        return invoke(target.getClass(), target, method, new Class<?>[0]);
    }

    private static Object call(Object target, String method, Class<?> parameter, Object value) {
        return invoke(target.getClass(), target, method, new Class<?>[]{parameter}, value);
    }

    private static Object invoke(Class<?> type, Object target, String method, Class<?>[] parameters, Object... args) {
        try {
            return type.getMethod(method, parameters).invoke(target, args);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException(type.getName() + "." + method, error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(type.getName() + "." + method, error);
        }
    }
}
