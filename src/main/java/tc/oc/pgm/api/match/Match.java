package tc.oc.pgm.api.match;

import java.util.Collection;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.joda.time.Duration;
import tc.oc.pgm.api.chat.MultiAudience;
import tc.oc.pgm.api.match.event.*;
import tc.oc.pgm.api.party.Competitor;
import tc.oc.pgm.api.party.Party;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.time.Tick;
import tc.oc.pgm.countdowns.CountdownContext;
import tc.oc.pgm.features.MatchFeatureContext;
import tc.oc.pgm.filters.query.Query;
import tc.oc.pgm.map.MapModuleContext;
import tc.oc.pgm.map.PGMMap;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.match.MatchModuleContext;
import tc.oc.pgm.module.ModuleLoadException;
import tc.oc.pgm.result.VictoryCondition;
import tc.oc.server.Scheduler;

/**
 * A PvP game that takes place in a {@link World} loaded with a {@link PGMMap}.
 *
 * <p>Each {@link Match} should operate like an independent {@link org.bukkit.Server}, and ensure
 * its resources, such as {@link MatchPlayer}s, can only interact with other resources in the same
 * {@link Match}. This should allow multiple {@link Match}es to run concurrently on the same {@link
 * org.bukkit.Server}, as long as resources are cleaned up after {@link #unload()}.
 */
public interface Match extends MatchPlayerResolver, MultiAudience {

  /**
   * Get the global {@link Logger} for the {@link Match}.
   *
   * @return The global {@link Logger}.
   */
  Logger getLogger();

  /**
   * Get a unique identifier for the {@link Match}, used for book-keeping purposes only.
   *
   * @return A unique identifier.
   */
  String getId();

  /**
   * Get the {@link PGMMap} that is playing for the {@link Match}.
   *
   * @return The {@link PGMMap}.
   */
  PGMMap getMap();

  /**
   * Get the {@link World} that represents the {@link Match#getMap()}.
   *
   * <p>On {@link #unload()}, the {@link World} will be unloaded and could possibly be {@code null}.
   *
   * @return The {@link World}.
   */
  World getWorld();

  /**
   * Get the current {@link MatchScope} of {@link MatchModule}s and {@link Listener}s.
   *
   * <p>Before {@link #load()}, the {@link MatchScope} will be {@code null}.
   *
   * @see MatchScope
   * @return The {@link MatchScope}.
   */
  MatchScope getScope();

  /**
   * Get whether the {@link MatchScope} is currently {@link MatchScope#LOADED}.
   *
   * @see #getScope()
   * @return If the {@link MatchScope} is {@link MatchScope#LOADED}.
   */
  default boolean isLoaded() {
    return getScope() == MatchScope.LOADED;
  }

  /**
   * Attempts to load all {@link MatchModule}s and transition to {@link MatchScope#LOADED}.
   *
   * <p>On failure, {@link #unload()} and {@link MatchModule#unload()} will be called.
   *
   * @see MatchLoadEvent
   * @throws ModuleLoadException If any {@link MatchModule}s failed on {@link MatchModule#load()}.
   */
  void load() throws ModuleLoadException;

  /**
   * The de-constructor for {@link Match}, which should reset all state and ensure that resources
   * are eligible for garbage collection.
   *
   * @see MatchUnloadEvent
   */
  void unload();

  /**
   * Get the {@link MatchPhase} of the {@link Match}.
   *
   * @see MatchPhase
   * @return The {@link MatchPhase}.
   */
  MatchPhase getPhase();

  /**
   * Get whether the {@link MatchPhase} is currently {@link MatchPhase#RUNNING}.
   *
   * @return If the {@link MatchPhase} is {@link MatchPhase#RUNNING}.
   */
  default boolean isRunning() {
    return getPhase() == MatchPhase.RUNNING;
  }

  /**
   * Get whether the {@link MatchPhase} is {@link MatchPhase#FINISHED}.
   *
   * @return If the {@link MatchPhase} is {@link MatchPhase#FINISHED}.
   */
  default boolean isFinished() {
    return getPhase() == MatchPhase.FINISHED;
  }

  /**
   * Transition the {@link Match} to another {@link MatchPhase}.
   *
   * @see MatchPhaseChangeEvent
   * @see MatchStartEvent
   * @see MatchFinishEvent
   * @param phase The next {@link MatchPhase}.
   * @return If the transition was a success.
   */
  boolean setPhase(MatchPhase phase);

  /**
   * Transition the {@link Match} to {@link MatchPhase#RUNNING}.
   *
   * @see #setPhase(MatchPhase)
   * @see MatchStartEvent
   * @return If the transition was a success.
   */
  default boolean start() {
    return setPhase(MatchPhase.RUNNING);
  }

  /**
   * Transition the {@link Match} to {@link MatchPhase#FINISHED}.
   *
   * @see #setPhase(MatchPhase)
   * @see MatchFinishEvent
   * @param winner The winner, or {@code null} for no winner.
   * @return If the transition was a success.
   */
  boolean finish(@Nullable Competitor winner);

  /**
   * Transition the {@link Match} to {@link MatchPhase#FINISHED}, with no winners.
   *
   * @see #finish(Competitor)
   * @return If the transition was a success.
   */
  default boolean finish() {
    return finish(null);
  }

  /**
   * Get a reusable {@link Scheduler} that is bound to a {@link MatchScope}.
   *
   * <p>Once the {@link Match} exits the given {@link MatchScope}, all tasks scheduled for that
   * {@link MatchScope} are cancelled and the {@link Scheduler} rejects any new tasks.
   *
   * @param scope The {@link MatchScope} to bind tasks to.
   * @return The {@link MatchScope}-bound {@link Scheduler}.
   */
  Scheduler getScheduler(MatchScope scope);

  /**
   * Sends an {@link Event} to the {@link Bukkit} event-bus.
   *
   * @see Bukkit#getPluginManager()#callEvent(Event)
   * @param event The event to submit.
   */
  void callEvent(Event event);

  /**
   * Get a {@link Tick} that is guaranteed to return the current Minecraft server tick.
   *
   * @return The {@link Tick}.
   */
  Tick getTick();

  /**
   * Get a random number between 0 and 1, consistent by the current {@link #getTick()}.
   *
   * @param seed The seed of the operation.
   * @return A random number between 0 and 1.
   */
  double getRandomFromTick(long seed);

  /**
   * Get a shared {@link Random} for all objects related to the {@link Match}.
   *
   * @return The random number generator.
   */
  Random getRandom();

  /**
   * Try to get a {@link MatchModule}, if one is loaded for the {@link Match}.
   *
   * @param moduleClass The class of the module.
   * @param <T> The type of the module.
   * @return The optional {@link MatchModule}.
   */
  <T extends MatchModule> Optional<T> getModule(Class<T> moduleClass);

  @Deprecated
  default <T extends MatchModule> T getMatchModule(Class<T> moduleClass) {
    return getModule(moduleClass).orElse(null);
  }

  @Deprecated
  default <T extends MatchModule> boolean hasMatchModule(Class<T> moduleClass) {
    return getModule(moduleClass).isPresent();
  }

  /**
   * Assert and get a {@link MatchModule} for the {@link Match}.
   *
   * @see #getModule(Class)
   * @param moduleClass The class of the module.
   * @param <T> The type of the module.
   * @return The {@link MatchModule}.
   * @throws IllegalStateException If the module is not found.
   */
  default <T extends MatchModule> T needModule(Class<T> moduleClass) {
    return getModule(moduleClass)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    toString()
                        + " required "
                        + moduleClass.getSimpleName()
                        + ", but was not found"));
  }

  @Deprecated
  default <T extends MatchModule> T needMatchModule(Class<T> moduleClass) {
    return needModule(moduleClass);
  }

  /**
   * Registers a {@link Listener}, but only for the duration of the {@link MatchScope}.
   *
   * @param listener The {@link Listener} to register.
   * @param scope The {@link MatchScope} to bind the {@link Listener} to.
   */
  void addListener(Listener listener, MatchScope scope);

  /**
   * Registers a {@link Tickable}, but only for the duration of the {@link MatchScope}.
   *
   * @param tickable The {@link Tickable} to register.
   * @param scope The {@link MatchScope} to bind the {@link Tickable} to.
   */
  void addTickable(Tickable tickable, MatchScope scope);

  /**
   * Get the {@link CountdownContext} for the {@link Match}.
   *
   * <p>There are several places in the code that assume this should be a {@link
   * tc.oc.pgm.countdowns.SingleCountdownContext}. Will need to be fixed later.
   *
   * @return The {@link CountdownContext}.
   */
  CountdownContext getCountdown();

  /**
   * Get the {@link MatchModuleContext} for the {@link Match}.
   *
   * @return The {@link MatchModuleContext}.
   */
  MatchModuleContext getModuleContext();

  /**
   * Get the {@link MatchFeatureContext} for the {@link Match}.
   *
   * @return The {@link MatchFeatureContext}.
   */
  MatchFeatureContext getFeatureContext();

  /**
   * Get the {@link MapModuleContext} for the {@link Match}.
   *
   * @return The {@link MapModuleContext}.
   */
  MapModuleContext getMapContext();

  /**
   * Get the maximum number of {@link MatchPlayer}s for the {@link Match}.
   *
   * @return The maximum number of players.
   */
  int getMaxPlayers();

  /**
   * Set the maximum number of {@link MatchPlayer}s for the {@link Match}.
   *
   * @param players The new maximum number of players.
   */
  void setMaxPlayers(int players);

  /**
   * Get all the {@link MatchPlayer}s in the {@link Match}.
   *
   * @return The collection of {@link MatchPlayer}s.
   */
  Collection<MatchPlayer> getPlayers();

  /**
   * Get all the observing {@link MatchPlayer}s in the {@link Match}.
   *
   * @return All the observers.
   */
  Collection<MatchPlayer> getObservers();

  /**
   * Get all the participating {@link MatchPlayer}s in the {@link Match}.
   *
   * @return All the participants.
   */
  Collection<MatchPlayer> getParticipants();

  /**
   * Add a {@link Player} to the {@link Match}.
   *
   * @param player The (bukkit) {@link Player} to add.
   * @return The {@link MatchPlayer}.
   */
  MatchPlayer addPlayer(Player player);

  /**
   * Remove a {@link Player} from the {@link Match}.
   *
   * @param player The (bukkit) {@link Player} to remove.
   */
  void removePlayer(Player player);

  /**
   * Get the default {@link Party} when a {@link MatchPlayer} joins the {@link Match}.
   *
   * @return The default {@link Party}.
   */
  Party getDefaultParty();

  /**
   * Get all the {@link Party}s in the {@link Match}.
   *
   * @return All the {@link Party}s.
   */
  Collection<Party> getParties();

  /**
   * Get all the {@link Competitor}s in the {@link Match}.
   *
   * @return All the {@link Competitor}s.
   */
  Collection<Competitor> getCompetitors();

  /**
   * Set or change the {@link Party} of a {@link MatchPlayer}.
   *
   * @param player The {@link MatchPlayer}.
   * @param party The new {@link Party}.
   * @return Whether the operation was a success.
   */
  boolean setParty(MatchPlayer player, Party party);

  /**
   * Add a {@link Party} to the {@link Match}.
   *
   * @param party The {@link Party} to add.
   */
  void addParty(Party party);

  /**
   * Remove a {@link Party} from the {@link Match}.
   *
   * @param party The {@link Party} to remove.
   */
  void removeParty(Party party);

  /**
   * Get the {@link Query} associated with the {@link Match}.
   *
   * @return The filter {@link Query}.
   */
  Query getQuery();

  /**
   * Get the {@link Duration} of the {@link Match}, or {@link Duration#ZERO} if not yet started.
   *
   * @return The {@link Duration} of the {@link Match}.
   */
  Duration getDuration();

  /**
   * Add a {@link VictoryCondition} to the {@link Match}.
   *
   * @param condition The {@link VictoryCondition} to add.
   */
  void addVictoryCondition(VictoryCondition condition);

  /**
   * Remove a {@link VictoryCondition} from the {@link Match}.
   *
   * @param condition The {@link VictoryCondition} to remove.
   */
  void removeVictoryCondition(VictoryCondition condition);

  /**
   * Get all {@link VictoryCondition}s for the {@link Match}.
   *
   * @return All the {@link VictoryCondition}s.
   */
  Collection<VictoryCondition> getVictoryConditions();

  /**
   * Calculate whether a {@link VictoryCondition} has been meet, and if so, transition the {@link
   * Match} to {@link MatchPhase#FINISHED}.
   *
   * @return If the {@link Match} was just ended.
   */
  boolean calculateVictory();
}
