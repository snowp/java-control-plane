package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;
import envoy.api.v2.core.Base.Node;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SimpleCache} is a snapshot-based cache that maintains a single versioned snapshot of responses per node group,
 * with no canary updates. SimpleCache does not track status of remote nodes and consistently replies with the latest
 * snapshot. For the protocol to work correctly, EDS/RDS requests are responded only when all resources in the snapshot
 * xDS response are named as part of the request. It is expected that the CDS response names all EDS clusters, and the
 * LDS response names all RDS routes in a snapshot, to ensure that Envoy makes the request for all EDS clusters or RDS
 * routes eventually.
 *
 * <p>SimpleCache can also be used as a config cache for distinct xDS requests. The snapshots are required to contain
 * only the responses for the particular type of the xDS service that the cache serves. Synchronization of multiple
 * caches for different response types is left to the configuration producer.
 */
public class SimpleCache implements Cache {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

  private final Consumer<String> callback;
  private final NodeGroup groups;

  private final Object lock = new Object();
  private final Map<String, Snapshot> snapshots = new HashMap<>();
  private final Map<String, Map<Long, Watch>> watches = new HashMap<>();

  private long watchCount;

  public SimpleCache(Consumer<String> callback, NodeGroup groups) {
    this.callback = callback;
    this.groups = groups;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSnapshot(String group, Snapshot snapshot) {
    synchronized (lock) {
      // Update the existing entry.
      snapshots.put(group, snapshot);

      // Trigger existing watches.
      if (watches().containsKey(group)) {
        watches().get(group).values().forEach(watch -> respond(watch, snapshot, group));

        // Discard all watches; the client must request a new watch to receive updates and ACK/NACK.
        watches().remove(group);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Watch watch(ResourceType type, Node node, String version, Collection<String> names) {
    Watch watch = new Watch(names, type);

    final String group;

    // Do nothing if group hashing failed.
    try {
      group = groups.hash(node);
    } catch (Exception e) {
      LOGGER.error("failed to hash node {} into group", node, e);

      return watch;
    }

    synchronized (lock) {
      // If the requested version is up-to-date or missing a response, leave an open watch.
      Snapshot snapshot = snapshots.get(group);

      if (snapshot == null || snapshot.version().equals(version)) {
        if (snapshot == null && callback != null) {
          LOGGER.info("callback {} at {}", group, version);

          // TODO(jbratton): Should we track these CFs somewhere (e.g. to force completion on shutdown)?
          CompletableFuture.runAsync(() -> callback.accept(group));
        }

        LOGGER.info("open watch for {}[{}] from key {} from version {}",
            type,
            String.join(", ", names),
            group,
            version);

        watchCount++;

        long id = watchCount;

        watches().computeIfAbsent(group, g -> new HashMap<>()).put(id, watch);

        watch.setStop(() -> {
          synchronized (lock) {
            Map<Long, Watch> groupWatches = watches().get(group);

            if (groupWatches != null) {
              groupWatches.remove(id);
            }
          }
        });

        return watch;
      }

      // Otherwise, the watch may be responded immediately.
      respond(watch, snapshot, group);
      return watch;
    }
  }

  private void respond(Watch watch, Snapshot snapshot, String group) {
    Collection<Message> resources = snapshot.resources().get(watch.type());

    // Remove clean-up as the watch is discarded immediately
    watch.setStop(null);

    // The request names must match the snapshot names. If they do not, then the watch is never responded, and it is
    // expected that envoy makes another request.
    if (!watch.names().isEmpty()) {
      Map<String, Boolean> names = watch.names().stream().collect(Collectors.toMap(name -> name, name -> true));

      Optional<String> missingResourceName = resources.stream()
          .map(Resources::getResourceName)
          .filter(n -> !names.containsKey(n))
          .findFirst();

      if (missingResourceName.isPresent()) {
        LOGGER.info("not responding for {} from {} at {} since {} not requested [{}]",
            watch.type(),
            group,
            snapshot.version(),
            missingResourceName.get(),
            String.join(", ", watch.names()));

        return;
      }
    }

    watch.valueEmitter().onNext(Response.create(false, resources, snapshot.version()));
  }

  @VisibleForTesting
  Map<String, Map<Long, Watch>> watches() {
    return watches;
  }
}
