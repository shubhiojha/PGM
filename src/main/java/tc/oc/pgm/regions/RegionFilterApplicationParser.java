package tc.oc.pgm.regions;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.util.Vector;
import org.jdom2.Attribute;
import org.jdom2.Element;
import tc.oc.component.Component;
import tc.oc.pgm.filters.Filter;
import tc.oc.pgm.filters.FilterNode;
import tc.oc.pgm.filters.FilterParser;
import tc.oc.pgm.kits.Kit;
import tc.oc.pgm.map.MapModuleContext;
import tc.oc.pgm.map.ProtoVersions;
import tc.oc.pgm.util.XMLUtils;
import tc.oc.util.SemanticVersion;
import tc.oc.xml.InvalidXMLException;
import tc.oc.xml.Node;

public class RegionFilterApplicationParser {
  private final MapModuleContext context;
  private final FilterParser filterParser;
  private final RegionParser regionParser;
  private final RFAContext rfaContext;
  private final SemanticVersion proto;

  public RegionFilterApplicationParser(MapModuleContext context, RFAContext rfaContext) {
    this.context = context;
    this.rfaContext = rfaContext;

    this.filterParser = context.getFilterParser();
    this.regionParser = context.getRegionParser();

    this.proto = context.getProto();
  }

  private boolean useId() {
    return proto.isNoOlderThan(ProtoVersions.FILTER_FEATURES);
  }

  private void add(Element el, RegionFilterApplication rfa) throws InvalidXMLException {
    context.features().addFeature(el, rfa);
    rfaContext.add(rfa);
  }

  public void parse(Element el) throws InvalidXMLException {
    Region region;
    if (useId()) {
      region = regionParser.parseRegionProperty(el, "region");
      if (region == null) region = EverywhereRegion.INSTANCE;
    } else {
      region = regionParser.parseChildren(el);
    }

    Component message = XMLUtils.parseFormattedText(el, "message");

    boolean earlyWarning = XMLUtils.parseBoolean(el.getAttribute("early-warning"), false);

    Filter effectFilter = filterParser.parseFilterProperty(el, "filter");

    Kit kit = context.getKitParser().parseKitProperty(el, "kit");
    if (kit != null) {
      add(el, new RegionFilterApplication(RFAScope.EFFECT, region, effectFilter, kit, false));
    }

    kit = context.getKitParser().parseKitProperty(el, "lend-kit");
    if (kit != null) {
      add(el, new RegionFilterApplication(RFAScope.EFFECT, region, effectFilter, kit, true));
    }

    Attribute attrVelocity = el.getAttribute("velocity");
    if (attrVelocity != null) {
      // Legacy support
      String velocityText = attrVelocity.getValue();
      if (velocityText.charAt(0) == '@') velocityText = velocityText.substring(1);
      Vector velocity = XMLUtils.parseVector(attrVelocity, velocityText);
      add(el, new RegionFilterApplication(RFAScope.EFFECT, region, effectFilter, velocity));
    }

    for (String tag : RFAScope.byTag.keySet()) {
      Filter filter;
      if (useId()) {
        filter = filterParser.parseFilterProperty(el, tag);
      } else {
        // Legacy syntax allows a list of filter names in the attribute
        Node node = Node.fromAttr(el, tag);
        if (node == null) {
          filter = null;
        } else {
          List<Filter> filters = new ArrayList<>();
          for (String name : Splitter.on(" ").split(node.getValue())) {
            filters.add(filterParser.parseReference(node, name));
          }
          switch (filters.size()) {
            case 0:
              filter = null;
              break;
            case 1:
              filter = filters.get(0);
              break;
            default:
              filter =
                  new FilterNode(
                      filters, Collections.<Filter>emptyList(), Collections.<Filter>emptyList());
          }
        }
      }

      if (filter != null) {
        for (RFAScope scope : RFAScope.byTag.get(tag)) {
          add(el, new RegionFilterApplication(scope, region, filter, message, earlyWarning));
        }
      }
    }
  }
}
