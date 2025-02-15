package tc.oc.pgm.projectile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.potion.PotionEffect;
import org.jdom2.Document;
import org.jdom2.Element;
import org.joda.time.Duration;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.filters.Filter;
import tc.oc.pgm.filters.FilterModule;
import tc.oc.pgm.filters.FilterParser;
import tc.oc.pgm.kits.KitParser;
import tc.oc.pgm.map.MapModule;
import tc.oc.pgm.map.MapModuleContext;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.module.ModuleDescription;
import tc.oc.pgm.util.XMLUtils;
import tc.oc.xml.InvalidXMLException;
import tc.oc.xml.Node;

@ModuleDescription(
    name = "Projectile",
    depends = {FilterModule.class})
public class ProjectileModule extends MapModule {
  Set<ProjectileDefinition> projectileDefinitions = new HashSet<>();

  @Override
  public MatchModule createMatchModule(Match match) {
    return new ProjectileMatchModule(match, this.projectileDefinitions);
  }

  public static MapModule parse(MapModuleContext context, Logger logger, Document doc)
      throws InvalidXMLException {
    ProjectileModule projectileModule = new ProjectileModule();
    KitParser kitParser = context.getKitParser();
    FilterParser filterParser = context.getFilterParser();

    for (Element projectileElement :
        XMLUtils.flattenElements(doc.getRootElement(), "projectiles", "projectile")) {
      String id = XMLUtils.getRequiredAttribute(projectileElement, "id").getValue();
      String name = projectileElement.getAttributeValue("name");
      Double damage =
          XMLUtils.parseNumber(
              projectileElement.getAttribute("damage"), Double.class, (Double) null);
      double velocity =
          XMLUtils.parseNumber(
              Node.fromChildOrAttr(projectileElement, "velocity"), Double.class, 1.0);
      ClickAction clickAction =
          XMLUtils.parseEnum(
              Node.fromAttr(projectileElement, "click"),
              ClickAction.class,
              "click action",
              ClickAction.BOTH);
      Class<? extends Entity> entity =
          XMLUtils.parseEntityTypeAttribute(projectileElement, "projectile", Arrow.class);
      List<PotionEffect> potionKit = kitParser.parsePotions(projectileElement);
      Filter destroyFilter = filterParser.parseFilterProperty(projectileElement, "destroy-filter");
      Duration coolDown = XMLUtils.parseDuration(projectileElement.getAttribute("cooldown"));
      boolean throwable = XMLUtils.parseBoolean(projectileElement.getAttribute("throwable"), true);

      ProjectileDefinition projectileDefinition =
          new ProjectileDefinition(
              id,
              name,
              damage,
              velocity,
              clickAction,
              entity,
              potionKit,
              destroyFilter,
              coolDown,
              throwable);

      context.features().addFeature(projectileElement, projectileDefinition);
      projectileModule.projectileDefinitions.add(projectileDefinition);
    }

    return projectileModule;
  }
}
