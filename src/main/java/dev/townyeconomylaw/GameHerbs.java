package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

final class GameHerbs implements Listener {
    enum Kind {
        POPPY_SEEDS(Material.WHEAT_SEEDS, "ケシの種"),
        IMMATURE_POPPY(Material.BEETROOT, "未熟成のケシの実"),
        RIPE_POPPY(Material.BEETROOT, "熟したケシの実"),
        POPPY_LATEX(Material.GLASS_BOTTLE, "ケシの乳液瓶"),
        HEMP_SEEDS(Material.WHEAT_SEEDS, "麻の実"),
        WILLOW_LOG(Material.BIRCH_LOG, "柳の原木"),
        WILLOW_BARK(Material.STICK, "柳の樹皮"),
        BELLADONNA_SEEDS(Material.WHEAT_SEEDS, "ベラドンナの種"),
        BELLADONNA(Material.NETHER_WART, "ベラドンナの葉・根"),
        LICORICE(Material.STICK, "カンゾウの根"),
        EPHEDRA(Material.SUGAR_CANE, "マオウの茎"),
        TEA_SEEDS(Material.WHEAT_SEEDS, "茶の種"),
        TEA_LEAVES(Material.DRIED_KELP, "茶葉"),
        COFFEE_SEEDS(Material.WHEAT_SEEDS, "コーヒーの種"),
        COFFEE_BEANS(Material.COCOA_BEANS, "コーヒー豆"),
        RAPESEED(Material.WHEAT_SEEDS, "菜の花の種"),
        RAPE_FLOWER(Material.DANDELION, "菜の花"),
        RAPE_OIL(Material.GLASS_BOTTLE, "菜種油"),
        COLD_ROOT(Material.STICK, "風邪効薬の根"),
        MORTAR(Material.BOWL, "すり鉢"),
        COLD_BASE(Material.SUGAR, "風邪薬のもと"),
        COLD_MEDICINE(Material.SUGAR, "風邪薬"),
        INFLUENZA_BASE(Material.SUGAR, "抗インフルエンザウイルス薬のもと"),
        INFLUENZA_MEDICINE(Material.SUGAR, "抗インフルエンザウイルス薬"),
        GLOVES(Material.LEATHER, "手袋"),
        PARASITE_MEDICINE(Material.GLASS_BOTTLE, "寄生生物感染症特効液体薬"),
        SYRINGE(Material.STICK, "シリンジ"),
        PARASITE_SYRINGE(Material.STICK, "寄生生物感染症特効液体薬入りシリンジ"),
        ANTIVIRAL(Material.SUGAR, "抗ウイルス薬");

        final Material material;
        final String label;
        final String id;

        Kind(Material material, String label) {
            this.material = material;
            this.label = label;
            this.id = name().toLowerCase(Locale.ROOT);
        }
    }

    private record Plant(Kind seed, Kind produce, Kind young) {}

    private static final List<Plant> PLANTS = List.of(
        new Plant(Kind.POPPY_SEEDS, Kind.RIPE_POPPY, Kind.IMMATURE_POPPY),
        new Plant(Kind.HEMP_SEEDS, Kind.HEMP_SEEDS, null),
        new Plant(Kind.BELLADONNA_SEEDS, Kind.BELLADONNA, null),
        new Plant(Kind.TEA_SEEDS, Kind.TEA_LEAVES, null),
        new Plant(Kind.COFFEE_SEEDS, Kind.COFFEE_BEANS, null),
        new Plant(Kind.RAPESEED, Kind.RAPE_FLOWER, null)
    );

    private final TownyEconomyLaw plugin;
    private final State state;
    private final Government government;
    private final NamespacedKey itemKind;
    private final List<NamespacedKey> registered = new ArrayList<>();

    GameHerbs(TownyEconomyLaw plugin, State state, Government government) {
        this.plugin = plugin;
        this.state = state;
        this.government = government;
        itemKind = new NamespacedKey(plugin, "game_herb");
    }

    ItemStack item(Kind kind) {
        ItemStack stack = new ItemStack(kind.material);
        var meta = stack.getItemMeta();
        meta.displayName(Component.text(kind.label).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemKind, PersistentDataType.STRING, kind.id);
        stack.setItemMeta(meta);
        return stack;
    }

    Kind kind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(itemKind, PersistentDataType.STRING);
        Kind kind = id == null ? null : find(id);
        return kind != null && kind.material == item.getType() ? kind : null;
    }

    private Kind find(String id) {
        for (Kind kind : Kind.values()) if (kind.id.equals(id)) return kind;
        return null;
    }

    private Plant plant(Kind kind) {
        if (kind == null) return null;
        for (Plant plant : PLANTS) if (plant.seed == kind) return plant;
        return null;
    }

    void register() {
        add(shaped(Kind.POPPY_LATEX, " PS", " B ", "   ")
            .setIngredient('P', exact(Kind.IMMATURE_POPPY))
            .setIngredient('S', Material.SHEARS)
            .setIngredient('B', exact(Material.GLASS_BOTTLE)));
        add(shaped(Kind.HEMP_SEEDS, "   ", " PS", " A ")
            .setIngredient('P', exact(Kind.RIPE_POPPY))
            .setIngredient('S', Material.SHEARS)
            .setIngredient('A', Material.PAPER));
        add(shaped(Kind.WILLOW_BARK, "   ", " LA", "   ")
            .setIngredient('L', exact(Kind.WILLOW_LOG))
            .setIngredient('A', materialsEndingIn("_AXE")));
        add(shaped(Kind.COLD_ROOT, "EL", "B ")
            .setIngredient('E', exact(Kind.EPHEDRA))
            .setIngredient('L', exact(Kind.LICORICE))
            .setIngredient('B', exact(Kind.BELLADONNA)));
        add(shaped(Kind.MORTAR, " T ", "S S", " S ")
            .setIngredient('T', Material.STICK)
            .setIngredient('S', Material.STONE));
        add(shaped(Kind.COLD_BASE, "LPW", "TRC", " MB")
            .setIngredient('L', exact(Kind.POPPY_LATEX))
            .setIngredient('P', Material.POTATO)
            .setIngredient('W', exact(Kind.WILLOW_BARK))
            .setIngredient('T', exact(Kind.TEA_LEAVES))
            .setIngredient('R', exact(Kind.COLD_ROOT))
            .setIngredient('C', exact(Kind.COFFEE_BEANS))
            .setIngredient('M', exact(Kind.MORTAR))
            .setIngredient('B', exact(Material.GLASS_BOTTLE)));
        add(shaped(Kind.RAPE_OIL, "RSF", "   ", " B ")
            .setIngredient('R', exact(Kind.RAPESEED))
            .setIngredient('S', exact(Material.WHEAT_SEEDS))
            .setIngredient('F', exact(Kind.RAPE_FLOWER))
            .setIngredient('B', exact(Material.GLASS_BOTTLE)));
        add(shaped(Kind.INFLUENZA_BASE, "LPO", " MB", "   ")
            .setIngredient('L', Material.MILK_BUCKET)
            .setIngredient('P', Material.POTATO)
            .setIngredient('O', exact(Kind.RAPE_OIL))
            .setIngredient('M', exact(Kind.MORTAR))
            .setIngredient('B', exact(Material.GLASS_BOTTLE)));
        add(shaped(Kind.GLOVES, "WW ", "WWW", " WW")
            .setIngredient('W', materialsEndingIn("_WOOL")));
        add(shaped(Kind.PARASITE_MEDICINE, "PLO", "TRC", " MB")
            .setIngredient('P', Material.POTATO)
            .setIngredient('L', Material.MILK_BUCKET)
            .setIngredient('O', exact(Kind.RAPE_OIL))
            .setIngredient('T', exact(Kind.TEA_LEAVES))
            .setIngredient('R', exact(Kind.COLD_ROOT))
            .setIngredient('C', exact(Kind.COFFEE_BEANS))
            .setIngredient('M', exact(Kind.MORTAR))
            .setIngredient('B', exact(Material.GLASS_BOTTLE)));
        add(shaped(Kind.SYRINGE, " P ", " G ", " I ")
            .setIngredient('P', Material.PISTON)
            .setIngredient('G', Material.GLASS_PANE)
            .setIngredient('I', Material.IRON_BARS));
        add(shaped(Kind.PARASITE_SYRINGE, " L", "S ")
            .setIngredient('L', exact(Kind.PARASITE_MEDICINE))
            .setIngredient('S', exact(Kind.SYRINGE)));
        add(shaped(Kind.ANTIVIRAL, "OPH", " MB", "   ")
            .setIngredient('O', exact(Kind.RAPE_OIL))
            .setIngredient('P', Material.POTATO)
            .setIngredient('H', exact(Kind.HEMP_SEEDS))
            .setIngredient('M', exact(Kind.MORTAR))
            .setIngredient('B', exact(Material.GLASS_BOTTLE)));

        smelt(Kind.COLD_BASE, Kind.COLD_MEDICINE);
        smelt(Kind.INFLUENZA_BASE, Kind.INFLUENZA_MEDICINE);
    }

    void unregister() {
        for (NamespacedKey key : registered) plugin.getServer().removeRecipe(key);
        registered.clear();
    }

    private ShapedRecipe shaped(Kind result, String... shape) {
        return new ShapedRecipe(recipeKey(result), item(result)).shape(shape);
    }

    private RecipeChoice.ExactChoice exact(Kind material) {
        return new RecipeChoice.ExactChoice(item(material));
    }

    private RecipeChoice.ExactChoice exact(Material material) {
        return new RecipeChoice.ExactChoice(new ItemStack(material));
    }

    private RecipeChoice.MaterialChoice materialsEndingIn(String suffix) {
        return new RecipeChoice.MaterialChoice(Arrays.stream(Material.values())
            .filter(material -> !material.isLegacy() && material.isItem() && material.name().endsWith(suffix)).toList());
    }

    private void smelt(Kind ingredient, Kind result) {
        ItemStack medicine = item(result);
        medicine.setAmount(16);
        add(new FurnaceRecipe(recipeKey(result), medicine, exact(ingredient), 0, 200));
    }

    private NamespacedKey recipeKey(Kind kind) {
        return new NamespacedKey(plugin, "herbs_" + kind.id);
    }

    private <T extends Recipe & Keyed> void add(T recipe) {
        if (!plugin.getServer().addRecipe(recipe)) throw new IllegalStateException("Could not register game herb recipe " + recipe);
        registered.add(recipe.getKey());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void plant(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.WHEAT) return;
        String path = plantPath(block);
        Plant seed = plant(kind(event.getItemInHand()));
        if (seed == null && !state.data().contains(path)) return;
        if (government.jailed(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        try {
            state.transaction(undo -> state.data().set(path, seed == null ? null : seed.seed.id));
        } catch (RuntimeException error) {
            event.setCancelled(true);
            plugin.run(event.getPlayer(), () -> { throw error; });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void harvest(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.OAK_LOG && event.isDropItems() && survival(event.getPlayer()) && block.getBiome() == Biome.SWAMP) {
            event.setDropItems(false);
            drop(block, Kind.WILLOW_LOG, 1);
            return;
        }
        if (block.getType() != Material.WHEAT) return;
        String path = plantPath(block);
        String recorded = state.data().getString(path);
        Plant plant = recorded == null ? null : plant(find(recorded));
        if (plant == null || !(block.getBlockData() instanceof Ageable age)) return;
        boolean drop = event.isDropItems() && survival(event.getPlayer());
        try {
            state.transaction(undo -> state.data().set(path, null));
        } catch (RuntimeException error) {
            event.setCancelled(true);
            plugin.run(event.getPlayer(), () -> { throw error; });
            return;
        }
        event.setDropItems(false);
        if (!drop) return;
        drop(block, plant.seed, 1);
        if (age.getAge() == age.getMaximumAge()) {
            drop(block, plant.produce, plant.seed == plant.produce ? 2 : 1);
        } else if (age.getAge() > 0 && plant.young != null) {
            drop(block, plant.young, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void forage(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!event.isDropItems() || !survival(event.getPlayer())) return;
        switch (block.getType()) {
            case SHORT_GRASS, TALL_GRASS -> {
                forageGrass(block, Kind.BELLADONNA);
                forageGrass(block, Kind.LICORICE);
                forageGrass(block, Kind.EPHEDRA);
            }
            case POPPY -> drop(block, Kind.POPPY_SEEDS, 1);
            case DANDELION -> drop(block, Kind.RAPESEED, 1);
            case FERN, LARGE_FERN -> drop(block, Kind.TEA_SEEDS, 1);
            case JUNGLE_LEAVES -> {
                if (block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent() && ThreadLocalRandom.current().nextInt(4) == 0) {
                    drop(block, Kind.COFFEE_SEEDS, 1);
                }
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        forgetPlants(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(BlockExplodeEvent event) {
        forgetPlants(event.blockList());
    }

    private void forageGrass(Block block, Kind produce) {
        if (ThreadLocalRandom.current().nextInt(4) != 0) return;
        drop(block, Kind.BELLADONNA_SEEDS, 1);
        drop(block, produce, 1);
    }

    private void forgetPlants(List<Block> blocks) {
        List<String> removed = blocks.stream().filter(block -> block.getType() == Material.WHEAT)
            .map(this::plantPath).filter(state.data()::contains).toList();
        if (!removed.isEmpty()) {
            state.transaction(undo -> removed.forEach(path -> state.data().set(path, null)));
        }
    }

    private String plantPath(Block block) {
        return "game-herbs.plants." + block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private boolean survival(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL && !government.jailed(player);
    }

    private void drop(Block block, Kind kind, int amount) {
        ItemStack stack = item(kind);
        stack.setAmount(amount);
        block.getWorld().dropItemNaturally(block.getLocation().toCenterLocation(), stack);
    }
}
