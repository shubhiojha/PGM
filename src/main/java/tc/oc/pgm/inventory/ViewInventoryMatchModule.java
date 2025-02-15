package tc.oc.pgm.inventory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.*;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.joda.time.Duration;
import org.joda.time.Instant;
import tc.oc.pgm.AllTranslations;
import tc.oc.pgm.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.player.event.ObserverInteractEvent;
import tc.oc.pgm.blitz.BlitzMatchModule;
import tc.oc.pgm.doublejump.DoubleJumpMatchModule;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.events.PlayerBlockTransformEvent;
import tc.oc.pgm.events.PlayerPartyChangeEvent;
import tc.oc.pgm.kits.WalkSpeedKit;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.match.MatchModuleFactory;
import tc.oc.pgm.module.ModuleLoadException;
import tc.oc.pgm.spawns.events.ParticipantSpawnEvent;
import tc.oc.pgm.util.InventoryTrackerEntry;
import tc.oc.server.BukkitUtils;

@ListenerScope(MatchScope.LOADED)
public class ViewInventoryMatchModule extends MatchModule implements Listener {
  public static class Factory implements MatchModuleFactory<ViewInventoryMatchModule> {
    @Override
    public ViewInventoryMatchModule createMatchModule(Match match) throws ModuleLoadException {
      return new ViewInventoryMatchModule(match);
    }
  }

  /**
   * Amount of milliseconds after the match begins where players may not add / remove items from
   * chests.
   */
  public static final Duration CHEST_PROTECT_TIME = Duration.standardSeconds(2);

  public static final Duration TICK = Duration.millis(50);

  protected final HashMap<String, InventoryTrackerEntry> monitoredInventories =
      new HashMap<String, InventoryTrackerEntry>();
  protected final HashMap<String, Instant> updateQueue = Maps.newHashMap();

  public static int getInventoryPreviewSlot(int inventorySlot) {
    if (inventorySlot < 9) {
      return inventorySlot + 36; // put hotbar on bottom
    }
    if (inventorySlot < 36) {
      return inventorySlot; // rest of inventory
    }
    // TODO: investigate why this method doesn't work with CraftBukkit's armor slots
    return inventorySlot; // default
  }

  private BukkitTask task;

  public ViewInventoryMatchModule(Match match) {
    super(match);

    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                PGM.get(),
                new Runnable() {
                  @Override
                  public void run() {
                    if (ViewInventoryMatchModule.this.updateQueue.size() == 0) return;

                    for (Iterator<Map.Entry<String, Instant>> iterator =
                            ViewInventoryMatchModule.this.updateQueue.entrySet().iterator();
                        iterator.hasNext(); ) {
                      Map.Entry<String, Instant> entry = iterator.next();
                      if (entry.getValue().isAfterNow()) continue;

                      Player player = Bukkit.getPlayerExact(entry.getKey());
                      if (player != null) {
                        ViewInventoryMatchModule.this.checkMonitoredInventories(player);
                      }

                      iterator.remove();
                    }
                  }
                },
                0,
                4);
  }

  @EventHandler(ignoreCancelled = true)
  public void checkInventoryClick(final InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player) {
      MatchPlayer player = this.match.getPlayer((Player) event.getWhoClicked());
      if (player == null) {
        return;
      }
      // we only cancel when the view is a chest because the other views tend to crash
      if (!allowedInventoryType(event.getInventory().getType())) {
        // cancel the click if the player cannot interact with the world or if the match has just
        // started
        if (!player.canInteract()
            || (player.getMatch().isRunning()
                && player.getMatch().getDuration().isShorterThan(CHEST_PROTECT_TIME))) {
          event.setCancelled(true);
        }
      }
    }
  }

  @EventHandler
  public void closeMonitoredInventory(final InventoryCloseEvent event) {
    this.monitoredInventories.remove(event.getPlayer().getName());
  }

  @EventHandler
  public void playerQuit(final PlayerPartyChangeEvent event) {
    this.monitoredInventories.remove(event.getPlayer().getBukkit().getName());
  }

  @EventHandler(ignoreCancelled = true)
  public void showInventories(final ObserverInteractEvent event) {
    if (event.getClickType() != ClickType.RIGHT) return;
    if (event.getPlayer().isDead()) return;

    if (event.getClickedParticipant() != null) {
      event.setCancelled(true);
      if (canPreviewInventory(event.getPlayer(), event.getClickedParticipant())) {
        this.previewPlayerInventory(
            event.getPlayer().getBukkit(), event.getClickedParticipant().getInventory());
      }
    } else if (event.getClickedEntity() instanceof InventoryHolder
        && !(event.getClickedEntity() instanceof Player)) {
      event.setCancelled(true);
      this.previewInventory(
          event.getPlayer().getBukkit(),
          ((InventoryHolder) event.getClickedEntity()).getInventory());
    } else if (event.getClickedBlockState() instanceof InventoryHolder) {
      event.setCancelled(true);
      this.previewInventory(
          event.getPlayer().getBukkit(),
          ((InventoryHolder) event.getClickedBlockState()).getInventory());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void updateMonitoredClick(final InventoryClickedEvent event) {
    if (event.getWhoClicked() instanceof Player) {
      Player player = (Player) event.getWhoClicked();

      boolean playerInventory =
          event.getInventory().getType() == InventoryType.CRAFTING; // cb bug fix
      Inventory inventory;

      if (playerInventory) {
        inventory = player.getInventory();
      } else {
        inventory = event.getInventory();
      }

      invLoop:
      for (Map.Entry<String, InventoryTrackerEntry> entry :
          new HashSet<>(
              this.monitoredInventories.entrySet())) { // avoid ConcurrentModificationException
        String pl = entry.getKey();
        InventoryTrackerEntry tracker = entry.getValue();

        // because a player can only be viewing one inventory at a time,
        // this is how we determine if we have a match
        if (inventory.getViewers().isEmpty()
            || tracker.getWatched().getViewers().isEmpty()
            || inventory.getViewers().size() > tracker.getWatched().getViewers().size())
          continue invLoop;

        for (int i = 0; i < inventory.getViewers().size(); i++) {
          if (!inventory.getViewers().get(i).equals(tracker.getWatched().getViewers().get(i))) {
            continue invLoop;
          }
        }

        // a watched user is in a chest
        if (tracker.isPlayerInventory() && !playerInventory) {
          inventory = tracker.getPlayerInventory().getHolder().getInventory();
          playerInventory = true;
        }

        if (playerInventory) {
          this.previewPlayerInventory(
              Bukkit.getServer().getPlayerExact(pl), (PlayerInventory) inventory);
        } else {
          this.previewInventory(Bukkit.getServer().getPlayerExact(pl), inventory);
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredInventory(final InventoryClickEvent event) {
    this.scheduleCheck((Player) event.getWhoClicked());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredInventory(final InventoryDragEvent event) {
    this.scheduleCheck((Player) event.getWhoClicked());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredTransform(final PlayerBlockTransformEvent event) {
    MatchPlayer player = event.getPlayer();
    if (player != null) this.scheduleCheck(player.getBukkit());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredPickup(final PlayerPickupItemEvent event) {
    this.scheduleCheck(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredDrop(final PlayerDropItemEvent event) {
    this.scheduleCheck(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredDamage(final EntityDamageEvent event) {
    if (event.getEntity() instanceof Player) {
      this.scheduleCheck((Player) event.getEntity());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredHealth(final EntityRegainHealthEvent event) {
    if (event.getEntity() instanceof Player) {
      Player player = (Player) event.getEntity();
      if (player.getHealth() == player.getMaxHealth()) return;
      this.scheduleCheck((Player) event.getEntity());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredHunger(final FoodLevelChangeEvent event) {
    this.scheduleCheck((Player) event.getEntity());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void updateMonitoredSpawn(final ParticipantSpawnEvent event) {
    // must have this hack so we update player's inventories when they respawn and recieve a kit
    ViewInventoryMatchModule.this.scheduleCheck(event.getPlayer().getBukkit());
  }

  @Override
  public void unload() {
    task.cancel();
  }

  public boolean canPreviewInventory(Player viewer, Player holder) {
    MatchPlayer matchViewer = getMatch().getPlayer(viewer);
    MatchPlayer matchHolder = getMatch().getPlayer(holder);
    return matchViewer != null
        && matchHolder != null
        && canPreviewInventory(matchViewer, matchHolder);
  }

  public boolean canPreviewInventory(MatchPlayer viewer, MatchPlayer holder) {
    return viewer.isObserving() && holder.isAlive();
  }

  protected static boolean allowedInventoryType(InventoryType type) {
    switch (type) {
      case CREATIVE:
      case PLAYER:
        return true;
      default:
        return false;
    }
  }

  protected void scheduleCheck(Player updater) {
    if (this.updateQueue.containsKey(updater.getName())) return;

    this.updateQueue.put(updater.getName(), Instant.now().plus(TICK));
  }

  protected void checkMonitoredInventories(Player updater) {
    for (Map.Entry<String, InventoryTrackerEntry> entry : this.monitoredInventories.entrySet()) {
      String pl = entry.getKey();
      InventoryTrackerEntry tracker = entry.getValue();

      if (tracker.isPlayerInventory()) {
        Player holder = (Player) tracker.getPlayerInventory().getHolder();
        if (updater.getName().equals(holder.getName())) {
          this.previewPlayerInventory(
              Bukkit.getServer().getPlayerExact(pl), tracker.getPlayerInventory());
        }
      }
    }
  }

  protected void previewPlayerInventory(Player viewer, PlayerInventory inventory) {
    if (viewer == null) {
      return;
    }

    Player holder = (Player) inventory.getHolder();
    // Ensure that the title of the inventory is <= 32 characters long to appease Minecraft's
    // restrictions on inventory titles
    String title = StringUtils.substring(holder.getDisplayName(viewer), 0, 32);

    Inventory preview = Bukkit.getServer().createInventory(viewer, 45, title);

    // handle inventory mapping
    for (int i = 0; i <= 35; i++) {
      preview.setItem(getInventoryPreviewSlot(i), inventory.getItem(i));
    }

    MatchPlayer matchHolder = this.match.getPlayer(holder);
    if (matchHolder != null && matchHolder.isParticipating()) {
      BlitzMatchModule module = matchHolder.getMatch().getMatchModule(BlitzMatchModule.class);
      if (module != null) {
        int livesLeft = module.lifeManager.getLives(holder.getUniqueId());
        ItemStack lives = new ItemStack(Material.EGG, livesLeft);
        ItemMeta lifeMeta = lives.getItemMeta();
        String key =
            livesLeft == 1
                ? "match.blitz.livesRemaining.singularLives"
                : "match.blitz.livesRemaining.pluralLives";
        lifeMeta.setDisplayName(
            ChatColor.GREEN
                + AllTranslations.get()
                    .translate(
                        key, viewer, ChatColor.AQUA + String.valueOf(livesLeft) + ChatColor.GREEN));
        lives.setItemMeta(lifeMeta);
        preview.setItem(4, lives);
      }

      List<String> specialLore = new ArrayList<>();

      if (holder.getAllowFlight()) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE
                + AllTranslations.get().translate("specialAbility.flying", viewer));
      }

      DoubleJumpMatchModule djmm =
          matchHolder.getMatch().getMatchModule(DoubleJumpMatchModule.class);
      if (djmm != null && djmm.hasKit(matchHolder)) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE
                + AllTranslations.get().translate("specialAbility.doubleJump", viewer));
      }

      double knockbackResistance =
          holder.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).getValue();
      if (knockbackResistance > 0) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE
                + AllTranslations.get()
                    .translate(
                        "specialAbility.knockbackResistance",
                        viewer,
                        (int) Math.ceil(knockbackResistance * 100)));
      }

      double knockbackReduction = holder.getKnockbackReduction();
      if (knockbackReduction > 0) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE
                + AllTranslations.get()
                    .translate(
                        "specialAbility.knockbackReduction",
                        viewer,
                        (int) Math.ceil(knockbackReduction * 100)));
      }

      double walkSpeed = holder.getWalkSpeed();
      if (walkSpeed != WalkSpeedKit.BUKKIT_DEFAULT) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE
                + AllTranslations.get()
                    .translate(
                        "specialAbility.walkSpeed",
                        viewer,
                        String.format("%.1f", walkSpeed / WalkSpeedKit.BUKKIT_DEFAULT)));
      }

      if (!specialLore.isEmpty()) {
        ItemStack special = new ItemStack(Material.NETHER_STAR);
        ItemMeta specialMeta = special.getItemMeta();
        specialMeta.setDisplayName(
            ChatColor.AQUA.toString()
                + ChatColor.ITALIC
                + AllTranslations.get()
                    .translate("player.inventoryPreview.specialAbilities", viewer));
        specialMeta.setLore(specialLore);
        special.setItemMeta(specialMeta);
        preview.setItem(5, special);
      }
    }

    // potions
    boolean hasPotions = holder.getActivePotionEffects().size() > 0;
    ItemStack potions = new ItemStack(hasPotions ? Material.POTION : Material.GLASS_BOTTLE);
    ItemMeta potionMeta = potions.getItemMeta();
    potionMeta.setDisplayName(
        ChatColor.AQUA.toString()
            + ChatColor.ITALIC
            + AllTranslations.get().translate("player.inventoryPreview.potionEffects", viewer));
    List<String> lore = Lists.newArrayList();
    if (hasPotions) {
      for (PotionEffect effect : holder.getActivePotionEffects()) {
        lore.add(
            ChatColor.YELLOW
                + BukkitUtils.potionEffectTypeName(effect.getType())
                + " "
                + (effect.getAmplifier() + 1));
      }
    } else {
      lore.add(
          ChatColor.YELLOW
              + AllTranslations.get().translate("player.inventoryPreview.noPotionEffects", viewer));
    }
    potionMeta.setLore(lore);
    potions.setItemMeta(potionMeta);
    preview.setItem(6, potions);

    // hunger and health
    ItemStack hunger = new ItemStack(Material.COOKED_BEEF, holder.getFoodLevel());
    ItemMeta hungerMeta = hunger.getItemMeta();
    hungerMeta.setDisplayName(
        ChatColor.AQUA.toString()
            + ChatColor.ITALIC
            + AllTranslations.get().translate("player.inventoryPreview.hungerLevel", viewer));
    hungerMeta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
    hunger.setItemMeta(hungerMeta);
    preview.setItem(7, hunger);

    ItemStack health = new ItemStack(Material.REDSTONE, (int) holder.getHealth());
    ItemMeta healthMeta = health.getItemMeta();
    healthMeta.setDisplayName(
        ChatColor.AQUA.toString()
            + ChatColor.ITALIC
            + AllTranslations.get().translate("player.inventoryPreview.healthLevel", viewer));
    healthMeta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
    health.setItemMeta(healthMeta);
    preview.setItem(8, health);

    // set armor manually because craftbukkit is a derp
    preview.setItem(0, inventory.getHelmet());
    preview.setItem(1, inventory.getChestplate());
    preview.setItem(2, inventory.getLeggings());
    preview.setItem(3, inventory.getBoots());

    this.showInventoryPreview(viewer, inventory, preview);
  }

  public void previewInventory(Player viewer, Inventory realInventory) {
    if (viewer == null) {
      return;
    }

    if (realInventory instanceof PlayerInventory) {
      previewPlayerInventory(viewer, (PlayerInventory) realInventory);
    } else {
      Inventory fakeInventory;
      if (realInventory instanceof DoubleChestInventory) {
        if (realInventory.hasCustomName()) {
          fakeInventory =
              Bukkit.createInventory(viewer, realInventory.getSize(), realInventory.getName());
        } else {
          fakeInventory = Bukkit.createInventory(viewer, realInventory.getSize());
        }
      } else {
        if (realInventory.hasCustomName()) {
          fakeInventory =
              Bukkit.createInventory(viewer, realInventory.getType(), realInventory.getName());
        } else {
          fakeInventory = Bukkit.createInventory(viewer, realInventory.getType());
        }
      }
      fakeInventory.setContents(realInventory.getContents());

      this.showInventoryPreview(viewer, realInventory, fakeInventory);
    }
  }

  protected void showInventoryPreview(
      Player viewer, Inventory realInventory, Inventory fakeInventory) {
    if (viewer == null) {
      return;
    }

    InventoryTrackerEntry entry = this.monitoredInventories.get(viewer.getName());
    if (entry != null
        && entry.getWatched().equals(realInventory)
        && entry.getPreview().getSize() == fakeInventory.getSize()) {
      entry.getPreview().setContents(fakeInventory.getContents());
    } else {
      entry = new InventoryTrackerEntry(realInventory, fakeInventory);
      this.monitoredInventories.put(viewer.getName(), entry);
      viewer.openInventory(fakeInventory);
    }
  }
}
