package tc.oc.pgm.modules;

import java.util.Random;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import tc.oc.pgm.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.projectile.EntityLaunchEvent;
import tc.oc.world.NMSHacks;

@ListenerScope(MatchScope.RUNNING)
public class ModifyBowProjectileMatchModule extends MatchModule implements Listener {
  protected final Class<? extends Entity> cls;
  protected final float velocityMod;
  protected final Set<PotionEffect> potionEffects;
  protected final Random random = new Random();

  public ModifyBowProjectileMatchModule(
      Match match, Class<? extends Entity> cls, float velocityMod, Set<PotionEffect> effects) {
    super(match);
    this.cls = cls;
    this.velocityMod = velocityMod;
    potionEffects = effects;
  }

  @EventHandler(ignoreCancelled = true)
  public void changeBowProjectile(EntityShootBowEvent event) {
    Plugin plugin = PGM.get();
    Entity newProjectile;

    if (this.cls == Arrow.class && event.getProjectile() instanceof Arrow) {
      // Don't change the projectile if it's an Arrow and the custom entity type is also Arrow
      newProjectile = event.getProjectile();
    } else {
      // Replace the projectile
      World world = event.getEntity().getWorld();
      Projectile oldEntity = (Projectile) event.getProjectile();
      newProjectile = world.spawn(oldEntity.getLocation(), this.cls);
      event.setProjectile(newProjectile);

      // Copy some things from the old projectile
      newProjectile.setVelocity(oldEntity.getVelocity());
      newProjectile.setFallDistance(oldEntity.getFallDistance());
      newProjectile.setFireTicks(oldEntity.getFireTicks());

      if (newProjectile instanceof Projectile) {
        ((Projectile) newProjectile).setShooter(oldEntity.getShooter());
        ((Projectile) newProjectile).setBounce(oldEntity.doesBounce());
      }

      // Save some special properties of Arrows
      if (oldEntity instanceof Arrow) {
        Arrow arrow = (Arrow) oldEntity;
        newProjectile.setMetadata("critical", new FixedMetadataValue(plugin, arrow.isCritical()));
        newProjectile.setMetadata(
            "knockback", new FixedMetadataValue(plugin, arrow.getKnockbackStrength()));
        newProjectile.setMetadata(
            "damage", new FixedMetadataValue(plugin, NMSHacks.getArrowDamage(arrow)));
      }
    }

    // Tag the projectile as custom
    newProjectile.setMetadata("customProjectile", new FixedMetadataValue(plugin, true));

    getMatch().callEvent(new EntityLaunchEvent(newProjectile, event.getEntity()));
  }

  @EventHandler(ignoreCancelled = true)
  public void fixEntityDamage(EntityDamageByEntityEvent event) {
    Entity projectile = event.getDamager();
    if (projectile.hasMetadata("customProjectile")) {

      // If the custom projectile replaced an arrow, recreate some effects specific to arrows
      if (projectile.hasMetadata("damage")) {
        boolean critical = projectile.getMetadata("critical").get(0).asBoolean();
        int knockback = projectile.getMetadata("knockback").get(0).asInt();
        double damage = projectile.getMetadata("damage").get(0).asDouble();
        double speed = projectile.getVelocity().length();

        // Reproduce the damage calculation from nms.EntityArrow with the addition of our modifier
        int finalDamage = (int) Math.ceil(speed * damage * this.velocityMod);
        if (critical) {
          finalDamage += random.nextInt(finalDamage / 2 + 2);
        }
        event.setDamage(finalDamage);

        // Flame arrows - target burns for 5 seconds always
        if (projectile.getFireTicks() > 0) {
          event.getEntity().setFireTicks(100);
        }

        // Reproduce the knockback calculation for punch bows
        if (knockback > 0) {
          Vector projectileVelocity = projectile.getVelocity();
          double horizontalSpeed =
              Math.sqrt(
                  projectileVelocity.getX() * projectileVelocity.getX()
                      + projectileVelocity.getZ() * projectileVelocity.getZ());
          Vector velocity = event.getEntity().getVelocity();
          velocity.setX(
              velocity.getX() + projectileVelocity.getX() * knockback * 0.6 / horizontalSpeed);
          velocity.setY(velocity.getY() + 0.1);
          velocity.setZ(
              velocity.getZ() + projectileVelocity.getZ() * knockback * 0.6 / horizontalSpeed);
          event.getEntity().setVelocity(velocity);
        }
      }

      // Apply any potion effects attached to the projectile
      if (event.getEntity() instanceof LivingEntity) {
        for (PotionEffect potionEffect : this.potionEffects) {
          ((LivingEntity) event.getEntity()).addPotionEffect(potionEffect);
        }
      }
    }
  }
}
