package tc.oc.pgm.spawns.states;

import java.util.List;
import org.bukkit.event.Event;
import org.bukkit.permissions.PermissionAttachment;
import tc.oc.pgm.PGM;
import tc.oc.pgm.api.match.event.MatchFinishEvent;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.spawns.SpawnMatchModule;

public class Participating extends State {

  private PermissionAttachment permissionAttachment;

  public Participating(SpawnMatchModule smm, MatchPlayer player) {
    super(smm, player);
  }

  @Override
  public void enterState() {
    super.enterState();
    permissionAttachment = bukkit.addAttachment(PGM.get());
    permissionAttachment.setPermission(smm.getParticipantPermissions(), true);
  }

  @Override
  public void leaveState(List<Event> events) {
    if (permissionAttachment != null) bukkit.removeAttachment(permissionAttachment);
    super.leaveState(events);
  }

  @Override
  public void onEvent(MatchFinishEvent event) {
    super.onEvent(event);
    transition(new Observing(smm, player, true, false));
  }
}
