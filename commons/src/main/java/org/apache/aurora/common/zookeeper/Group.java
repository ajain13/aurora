/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.common.zookeeper;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.apache.aurora.common.base.Command;
import org.apache.aurora.common.base.Commands;
import org.apache.aurora.common.base.ExceptionalSupplier;
import org.apache.aurora.common.base.MorePreconditions;
import org.apache.aurora.common.util.BackoffHelper;
import org.apache.aurora.common.zookeeper.ZooKeeperClient.ZooKeeperConnectionException;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class exposes methods for joining and monitoring distributed groups.  The groups this class
 * monitors are realized as persistent paths in ZooKeeper with ephemeral child nodes for
 * each member of a group.
 */
public class Group {
  private static final Logger LOG = LoggerFactory.getLogger(Group.class);

  private static final Supplier<byte[]> NO_MEMBER_DATA = Suppliers.ofInstance(null);
  private static final String DEFAULT_NODE_NAME_PREFIX = "member_";

  private final ZooKeeperClient zkClient;
  private final ImmutableList<ACL> acl;
  private final String path;

  private final NodeScheme nodeScheme;
  private final Predicate<String> nodeNameFilter;

  private final BackoffHelper backoffHelper;

  /**
   * Creates a group rooted at the given {@code path}.  Paths must be absolute and trailing or
   * duplicate slashes will be normalized.  For example, all the following paths would create a
   * group at the normalized path /my/distributed/group:
   * <ul>
   *   <li>/my/distributed/group
   *   <li>/my/distributed/group/
   *   <li>/my/distributed//group
   * </ul>
   *
   * @param zkClient the client to use for interactions with ZooKeeper
   * @param acl the ACL to use for creating the persistent group path if it does not already exist
   * @param path the absolute persistent path that represents this group
   * @param nodeScheme the scheme that defines how nodes are created
   */
  public Group(ZooKeeperClient zkClient, Iterable<ACL> acl, String path, NodeScheme nodeScheme) {
    this.zkClient = Preconditions.checkNotNull(zkClient);
    this.acl = ImmutableList.copyOf(acl);
    this.path = ZooKeeperUtils.normalizePath(Preconditions.checkNotNull(path));

    this.nodeScheme = Preconditions.checkNotNull(nodeScheme);
    nodeNameFilter = Group.this.nodeScheme::isMember;

    backoffHelper = new BackoffHelper();
  }

  /**
   * Equivalent to {@link #Group(ZooKeeperClient, Iterable, String, String)} with a
   * {@code namePrefix} of 'member_'.
   */
  public Group(ZooKeeperClient zkClient, Iterable<ACL> acl, String path) {
    this(zkClient, acl, path, DEFAULT_NODE_NAME_PREFIX);
  }

  /**
   * Equivalent to {@link #Group(ZooKeeperClient, Iterable, String, NodeScheme)} with a
   * {@link DefaultScheme} using {@code namePrefix}.
   */
  public Group(ZooKeeperClient zkClient, Iterable<ACL> acl, String path, String namePrefix) {
    this(zkClient, acl, path, new DefaultScheme(namePrefix));
  }

  public String getMemberPath(String memberId) {
    return path + "/" + MorePreconditions.checkNotBlank(memberId);
  }

  public String getPath() {
    return path;
  }

  public String getMemberId(String nodePath) {
    MorePreconditions.checkNotBlank(nodePath);
    Preconditions.checkArgument(nodePath.startsWith(path + "/"),
        "Not a member of this group[%s]: %s", path, nodePath);

    String memberId = StringUtils.substringAfterLast(nodePath, "/");
    Preconditions.checkArgument(nodeScheme.isMember(memberId),
        "Not a group member: %s", memberId);
    return memberId;
  }

  /**
   * Returns the current list of group member ids by querying ZooKeeper synchronously.
   *
   * @return the ids of all the present members of this group
   * @throws ZooKeeperConnectionException if there was a problem connecting to ZooKeeper
   * @throws KeeperException if there was a problem reading this group's member ids
   * @throws InterruptedException if this thread is interrupted listing the group members
   */
  public Iterable<String> getMemberIds()
      throws ZooKeeperConnectionException, KeeperException, InterruptedException {
    return Iterables.filter(zkClient.get().getChildren(path, false), nodeNameFilter);
  }

  /**
   * Gets the data for one of this groups members by querying ZooKeeper synchronously.
   *
   * @param memberId the id of the member whose data to retrieve
   * @return the data associated with the {@code memberId}
   * @throws ZooKeeperConnectionException if there was a problem connecting to ZooKeeper
   * @throws KeeperException if there was a problem reading this member's data
   * @throws InterruptedException if this thread is interrupted retrieving the member data
   */
  public byte[] getMemberData(String memberId)
      throws ZooKeeperConnectionException, KeeperException, InterruptedException {
    return zkClient.get().getData(getMemberPath(memberId), false, null);
  }

  /**
   * Represents membership in a distributed group.
   */
  public interface Membership {

    /**
     * Returns the persistent ZooKeeper path that represents this group.
     */
    String getGroupPath();

    /**
     * Returns the id (ZooKeeper node name) of this group member.  May change over time if the
     * ZooKeeper session expires.
     */
    String getMemberId();

    /**
     * Returns the full ZooKeeper path to this group member.  May change over time if the
     * ZooKeeper session expires.
     */
    String getMemberPath();

    /**
     * Updates the membership data synchronously using the {@code Supplier<byte[]>} passed to
     * {@link Group#join()}.
     *
     * @return the new membership data
     * @throws UpdateException if there was a problem updating the membership data
     */
    byte[] updateMemberData() throws UpdateException;

    /**
     * Cancels group membership by deleting the associated ZooKeeper member node.
     *
     * @throws JoinException if there is a problem deleting the node
     */
    void cancel() throws JoinException;
  }

  /**
   * Indicates an error joining a group.
   */
  public static class JoinException extends Exception {
    public JoinException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Indicates an error updating a group member's data.
   */
  public static class UpdateException extends Exception {
    public UpdateException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Equivalent to calling {@code join(null, null)}.
   */
  public final Membership join() throws JoinException, InterruptedException {
    return join(NO_MEMBER_DATA, null);
  }

  /**
   * Equivalent to calling {@code join(memberData, null)}.
   */
  public final Membership join(Supplier<byte[]> memberData)
      throws JoinException, InterruptedException {

    return join(memberData, null);
  }

  /**
   * Equivalent to calling {@code join(null, onLoseMembership)}.
   */
  public final Membership join(@Nullable final Command onLoseMembership)
      throws JoinException, InterruptedException {

    return join(NO_MEMBER_DATA, onLoseMembership);
  }

  /**
   * Joins this group and returns the resulting Membership when successful.  Membership will be
   * automatically cancelled when the current jvm process dies; however the returned Membership
   * object can be used to cancel membership earlier.  Unless
   * {@link Group.Membership#cancel()} is called the membership will
   * be maintained by re-establishing it silently in the background.
   *
   * <p>Any {@code memberData} given is persisted in the member node in ZooKeeper.  If an
   * {@code onLoseMembership} callback is supplied, it will be notified each time this member loses
   * membership in the group.
   *
   * @param memberData a supplier of the data to store in the member node
   * @param onLoseMembership a callback to notify when membership is lost
   * @return a Membership object with the member details
   * @throws JoinException if there was a problem joining the group
   * @throws InterruptedException if this thread is interrupted awaiting completion of the join
   */
  public final Membership join(Supplier<byte[]> memberData, @Nullable Command onLoseMembership)
      throws JoinException, InterruptedException {

    Preconditions.checkNotNull(memberData);
    ensurePersistentGroupPath();

    final ActiveMembership groupJoiner = new ActiveMembership(memberData, onLoseMembership);
    return backoffHelper.doUntilResult(() -> {
      try {
        return groupJoiner.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new JoinException("Interrupted trying to join group at path: " + path, e);
      } catch (ZooKeeperConnectionException e) {
        LOG.warn("Temporary error trying to join group at path: " + path, e);
        return null;
      } catch (KeeperException e) {
        if (zkClient.shouldRetry(e)) {
          LOG.warn("Temporary error trying to join group at path: " + path, e);
          return null;
        } else {
          throw new JoinException("Problem joining partition group at path: " + path, e);
        }
      }
    });
  }

  private void ensurePersistentGroupPath() throws JoinException, InterruptedException {
    backoffHelper.doUntilSuccess(() -> {
      try {
        ZooKeeperUtils.ensurePath(zkClient, acl, path);
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new JoinException("Interrupted trying to ensure group at path: " + path, e);
      } catch (ZooKeeperConnectionException e) {
        LOG.warn("Problem connecting to ZooKeeper, retrying", e);
        return false;
      } catch (KeeperException e) {
        if (zkClient.shouldRetry(e)) {
          LOG.warn("Temporary error ensuring path: " + path, e);
          return false;
        } else {
          throw new JoinException("Problem ensuring group at path: " + path, e);
        }
      }
    });
  }

  private class ActiveMembership implements Membership {
    private final Supplier<byte[]> memberData;
    private final Command onLoseMembership;
    private String nodePath;
    private String memberId;
    private volatile boolean cancelled;
    private byte[] membershipData;

    public ActiveMembership(Supplier<byte[]> memberData, @Nullable Command onLoseMembership) {
      this.memberData = memberData;
      this.onLoseMembership = (onLoseMembership == null) ? Commands.NOOP : onLoseMembership;
    }

    @Override
    public String getGroupPath() {
      return path;
    }

    @Override
    public synchronized String getMemberId() {
      return memberId;
    }

    @Override
    public synchronized String getMemberPath() {
      return nodePath;
    }

    @Override
    public synchronized byte[] updateMemberData() throws UpdateException {
      byte[] membershipData = memberData.get();
      if (!ArrayUtils.isEquals(this.membershipData, membershipData)) {
        try {
          zkClient.get().setData(nodePath, membershipData, ZooKeeperUtils.ANY_VERSION);
          this.membershipData = membershipData;
        } catch (KeeperException e) {
          throw new UpdateException("Problem updating membership data.", e);
        } catch (InterruptedException e) {
          throw new UpdateException("Interrupted attempting to update membership data.", e);
        } catch (ZooKeeperConnectionException e) {
          throw new UpdateException(
              "Could not connect to the ZooKeeper cluster to update membership data.", e);
        }
      }
      return membershipData;
    }

    @Override
    public synchronized void cancel() throws JoinException {
      if (!cancelled) {
        try {
          backoffHelper.doUntilSuccess(() -> {
            try {
              zkClient.get().delete(nodePath, ZooKeeperUtils.ANY_VERSION);
              return true;
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new JoinException("Interrupted trying to cancel membership: " + nodePath, e);
            } catch (ZooKeeperConnectionException e) {
              LOG.warn("Problem connecting to ZooKeeper, retrying", e);
              return false;
            } catch (NoNodeException e) {
              LOG.info("Membership already cancelled, node at path: " + nodePath +
                       " has been deleted");
              return true;
            } catch (KeeperException e) {
              if (zkClient.shouldRetry(e)) {
                LOG.warn("Temporary error cancelling membership: " + nodePath, e);
                return false;
              } else {
                throw new JoinException("Problem cancelling membership: " + nodePath, e);
              }
            }
          });
          cancelled = true; // Prevent auto-re-join logic from undoing this cancel.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new JoinException("Problem cancelling membership: " + nodePath, e);
        }
      }
    }

    private class CancelledException extends IllegalStateException { /* marker */ }

    synchronized Membership join()
        throws ZooKeeperConnectionException, InterruptedException, KeeperException {

      if (cancelled) {
        throw new CancelledException();
      }

      if (nodePath == null) {
        // Re-join if our ephemeral node goes away due to session expiry - only needs to be
        // registered once.
        zkClient.registerExpirationHandler(this::tryJoin);
      }

      byte[] membershipData = memberData.get();
      String nodeName = nodeScheme.createName(membershipData);
      CreateMode createMode = nodeScheme.isSequential()
          ? CreateMode.EPHEMERAL_SEQUENTIAL
          : CreateMode.EPHEMERAL;
      nodePath = zkClient.get().create(path + "/" + nodeName, membershipData, acl, createMode);
      memberId = Group.this.getMemberId(nodePath);
      LOG.info("Set group member ID to " + memberId);
      this.membershipData = membershipData;

      // Re-join if our ephemeral node goes away due to maliciousness.
      zkClient.get().exists(nodePath, event -> {
        if (event.getType() == EventType.NodeDeleted) {
          tryJoin();
        }
      });

      return this;
    }

    private final ExceptionalSupplier<Boolean, InterruptedException> tryJoin =
        () -> {
          try {
            join();
            return true;
          } catch (CancelledException e) {
            // Lost a cancel race - that's ok.
            return true;
          } catch (ZooKeeperConnectionException e) {
            LOG.warn("Problem connecting to ZooKeeper, retrying", e);
            return false;
          } catch (KeeperException e) {
            if (zkClient.shouldRetry(e)) {
              LOG.warn("Temporary error re-joining group: " + path, e);
              return false;
            } else {
              throw new IllegalStateException("Permanent problem re-joining group: " + path, e);
            }
          }
        };

    private synchronized void tryJoin() {
      onLoseMembership.execute();
      try {
        backoffHelper.doUntilSuccess(tryJoin);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            String.format("Interrupted while trying to re-join group: %s, giving up", path), e);
      }
    }
  }

  /**
   * An interface to an object that listens for changes to a group's membership.
   */
  public interface GroupChangeListener {

    /**
     * Called whenever group membership changes with the new list of member ids.
     *
     * @param memberIds the current member ids
     */
    void onGroupChange(Iterable<String> memberIds);
  }

  /**
   * An interface that dictates the scheme to use for storing and filtering nodes that represent
   * members of a distributed group.
   */
  public interface NodeScheme {
    /**
     * Determines if a child node is a member of a group by examining the node's name.
     *
     * @param nodeName the name of a child node found in a group
     * @return {@code true} if {@code nodeName} identifies a group member in this scheme
     */
    boolean isMember(String nodeName);

    /**
     * Generates a node name for the node representing this process in the distributed group.
     *
     * @param membershipData the data that will be stored in this node
     * @return the name for the node that will represent this process in the group
     */
    String createName(byte[] membershipData);

    /**
     * Indicates whether this scheme needs ephemeral sequential nodes or just ephemeral nodes.
     *
     * @return {@code true} if this scheme requires sequential node names; {@code false} otherwise
     */
    boolean isSequential();
  }

  /**
   * Indicates an error watching a group.
   */
  public static class WatchException extends Exception {
    public WatchException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Watches this group for the lifetime of this jvm process.  This method will block until the
   * current group members are available, notify the {@code groupChangeListener} and then return.
   * All further changes to the group membership will cause notifications on a background thread.
   *
   * @param groupChangeListener the listener to notify of group membership change events
   * @return A command which, when executed, will stop watching the group.
   * @throws WatchException if there is a problem generating the 1st group membership list
   * @throws InterruptedException if interrupted waiting to gather the 1st group membership list
   */
  public final Command watch(final GroupChangeListener groupChangeListener)
      throws WatchException, InterruptedException {
    Preconditions.checkNotNull(groupChangeListener);

    try {
      ensurePersistentGroupPath();
    } catch (JoinException e) {
      throw new WatchException("Failed to create group path: " + path, e);
    }

    final GroupMonitor groupMonitor = new GroupMonitor(groupChangeListener);
    backoffHelper.doUntilSuccess(() -> {
      try {
        groupMonitor.watchGroup();
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WatchException("Interrupted trying to watch group at path: " + path, e);
      } catch (ZooKeeperConnectionException e) {
        LOG.warn("Temporary error trying to watch group at path: " + path, e);
        return null;
      } catch (KeeperException e) {
        if (zkClient.shouldRetry(e)) {
          LOG.warn("Temporary error trying to watch group at path: " + path, e);
          return null;
        } else {
          throw new WatchException("Problem trying to watch group at path: " + path, e);
        }
      }
    });
    return groupMonitor::stopWatching;
  }

  /**
   * Helps continuously monitor a group for membership changes.
   */
  private class GroupMonitor {
    private final GroupChangeListener groupChangeListener;
    private volatile boolean stopped = false;
    private Set<String> members;

    GroupMonitor(GroupChangeListener groupChangeListener) {
      this.groupChangeListener = groupChangeListener;
    }

    private final Watcher groupWatcher = event -> {
      if (event.getType() == EventType.NodeChildrenChanged) {
        tryWatchGroup();
      }
    };

    private final ExceptionalSupplier<Boolean, InterruptedException> tryWatchGroup =
        () -> {
          try {
            watchGroup();
            return true;
          } catch (ZooKeeperConnectionException e) {
            LOG.warn("Problem connecting to ZooKeeper, retrying", e);
            return false;
          } catch (KeeperException e) {
            if (zkClient.shouldRetry(e)) {
              LOG.warn("Temporary error re-watching group: " + path, e);
              return false;
            } else {
              throw new IllegalStateException("Permanent problem re-watching group: " + path, e);
            }
          }
        };

    private void tryWatchGroup() {
      if (stopped) {
        return;
      }

      try {
        backoffHelper.doUntilSuccess(tryWatchGroup);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            String.format("Interrupted while trying to re-watch group: %s, giving up", path), e);
      }
    }

    private void watchGroup()
        throws ZooKeeperConnectionException, InterruptedException, KeeperException {

      if (stopped) {
        return;
      }

      List<String> children = zkClient.get().getChildren(path, groupWatcher);
      setMembers(Iterables.filter(children, nodeNameFilter));
    }

    private void stopWatching() {
      // TODO(William Farner): Cancel the watch when
      // https://issues.apache.org/jira/browse/ZOOKEEPER-442 is resolved.
      LOG.info("Stopping watch on " + this);
      stopped = true;
    }

    synchronized void setMembers(Iterable<String> members) {
      if (stopped) {
        LOG.info("Suppressing membership update, no longer watching " + this);
        return;
      }

      if (this.members == null) {
        // Reset our watch on the group if session expires - only needs to be registered once.
        zkClient.registerExpirationHandler(this::tryWatchGroup);
      }

      Set<String> membership = ImmutableSet.copyOf(members);
      if (!membership.equals(this.members)) {
        groupChangeListener.onGroupChange(members);
        this.members = membership;
      }
    }
  }

  /**
   * Default naming scheme implementation. Stores nodes at [given path] + "/" + [given prefix] +
   * ZooKeeper-generated member ID. For example, if the path is "/discovery/servicename", and the
   * prefix is "member_", the node's full path will look something like
   * {@code /discovery/servicename/member_0000000007}.
   */
  public static class DefaultScheme implements NodeScheme {
    private final String namePrefix;
    private final Pattern namePattern;

    /**
     * Creates a sequential node scheme based on the given node name prefix.
     *
     * @param namePrefix the prefix for the names of the member nodes
     */
    public DefaultScheme(String namePrefix) {
      this.namePrefix = MorePreconditions.checkNotBlank(namePrefix);
      namePattern = Pattern.compile("^" + Pattern.quote(namePrefix) + "-?[0-9]+$");
    }

    @Override
    public boolean isMember(String nodeName) {
      return namePattern.matcher(nodeName).matches();
    }

    @Override
    public String createName(byte[] membershipData) {
      return namePrefix;
    }

    @Override
    public boolean isSequential() {
      return true;
    }
  }

  @Override
  public String toString() {
    return "Group " + path;
  }
}
