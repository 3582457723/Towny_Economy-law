package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

final class TransitRecipes {
    private final TownyEconomyLaw plugin;
    private final MinecartCouplers couplers;
    private final NamespacedKey kindKey;
    private final List<NamespacedKey> registered = new ArrayList<>();

    TransitRecipes(TownyEconomyLaw plugin, MinecartCouplers couplers) {
        this.plugin = plugin;
        this.couplers = couplers;
        kindKey = new NamespacedKey(plugin, "transit_recipe_item");
    }

    void register() {
        ItemStack ticketMachine = item(Material.DROPPER, "ticket_machine", "汎用券売機");
        add(recipe("ticket_machine", ticketMachine, "IBI", "IGI", "DOI")
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('B', Material.BLACK_DYE)
            .setIngredient('G', Material.GLASS_PANE)
            .setIngredient('D', Material.DROPPER)
            .setIngredient('O', Material.OBSERVER));

        ItemStack stationRail = item(Material.RAIL, "station_rail", "ステーションレール");
        add(recipe("station_rail", stationRail, "ICI", "ISI", "IOI")
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('C', Material.COMPARATOR)
            .setIngredient('S', Material.STICK)
            .setIngredient('O', Material.OBSERVER));

        ItemStack rom = item(Material.CHEST, "rom", "ROM");
        add(recipe("rom", rom, "III", "RCR", "III")
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('R', Material.REPEATER)
            .setIngredient('C', Material.CHEST));

        ItemStack settlementChip = item(Material.COMPARATOR, "settlement_chip", "のりこし精算計算チップ");
        add(recipe("settlement_chip", settlementChip, "ICD", "DDR", "IMI")
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('C', Material.COMPARATOR)
            .setIngredient('D', Material.REDSTONE)
            .setIngredient('R', Material.REPEATER)
            .setIngredient('M', new RecipeChoice.ExactChoice(rom)));

        ItemStack settlementMachine = item(Material.DROPPER, "settlement_machine", "汎用のりこし精算機");
        add(recipe("settlement_machine", settlementMachine, "ICI", "IGI", "DOD")
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('C', new RecipeChoice.ExactChoice(settlementChip))
            .setIngredient('G', Material.GLASS_PANE)
            .setIngredient('D', Material.DROPPER)
            .setIngredient('O', Material.OBSERVER));

        ItemStack gateChip = item(Material.REPEATER, "gate_chip", "改札チップ");
        add(recipe("gate_chip", gateChip, "CDI", "DRD", "CDI")
            .setIngredient('C', Material.COMPARATOR)
            .setIngredient('D', Material.REDSTONE)
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('R', Material.REPEATER));

        ItemStack gate = item(Material.IRON_DOOR, "gate", "改札");
        add(recipe("gate", gate, " CB", "  D", " TO")
            .setIngredient('C', new RecipeChoice.ExactChoice(gateChip))
            .setIngredient('B', Material.IRON_BLOCK)
            .setIngredient('D', Material.DROPPER)
            .setIngredient('T', Material.IRON_TRAPDOOR)
            .setIngredient('O', Material.OBSERVER));

        add(recipe("minecart_coupler", couplers.item(), "   ", "CIC", "H H")
            .setIngredient('C', Material.IRON_CHAIN)
            .setIngredient('I', Material.IRON_INGOT)
            .setIngredient('H', Material.TRIPWIRE_HOOK));
    }

    void unregister() {
        for (NamespacedKey key : registered) plugin.getServer().removeRecipe(key);
        registered.clear();
    }

    private ShapedRecipe recipe(String id, ItemStack result, String... shape) {
        return new ShapedRecipe(new NamespacedKey(plugin, id), result).shape(shape);
    }

    private void add(ShapedRecipe recipe) {
        if (!plugin.getServer().addRecipe(recipe)) {
            throw new IllegalStateException("Could not register transit recipe " + recipe.getKey());
        }
        registered.add(recipe.getKey());
    }

    private ItemStack item(Material material, String id, String name) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }
}
