/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationResourceUsageReport;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeLabel;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger.AuditConstants;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerFinishedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerReservedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ActiveUsersManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Allocation;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.NodeType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Queue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceLimits;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.AbstractCSQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CSAMContainerLaunchDiagnosticsConstants;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CSAssignment;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityHeadroomProvider;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerContext;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.LeafQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.QueueCapacities;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.SchedulingMode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.allocator.ContainerAllocator;
import org.apache.hadoop.yarn.util.resource.DefaultResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents an application attempt from the viewpoint of the FIFO or Capacity
 * scheduler.
 */
@Private
@Unstable
public class FiCaSchedulerApp extends SchedulerApplicationAttempt {
  private static final Log LOG = LogFactory.getLog(FiCaSchedulerApp.class);

  private final Set<ContainerId> containersToPreempt =
    new HashSet<ContainerId>();

  private CapacityHeadroomProvider headroomProvider;

  private ResourceCalculator rc = new DefaultResourceCalculator();

  private ResourceScheduler scheduler;
  
  private ContainerAllocator containerAllocator;

  /**
   * to hold the message if its app doesn't not get container from a node
   */
  private String appSkipNodeDiagnostics;
  private CapacitySchedulerContext capacitySchedulerContext;

  public FiCaSchedulerApp(ApplicationAttemptId applicationAttemptId,
      String user, Queue queue, ActiveUsersManager activeUsersManager,
      RMContext rmContext) {
    this(applicationAttemptId, user, queue, activeUsersManager, rmContext,
        false);
  }

  public FiCaSchedulerApp(ApplicationAttemptId applicationAttemptId,
      String user, Queue queue, ActiveUsersManager activeUsersManager,
      RMContext rmContext, boolean isAttemptRecovering) {
    super(applicationAttemptId, user, queue, activeUsersManager, rmContext);
    
    RMApp rmApp = rmContext.getRMApps().get(getApplicationId());

    Resource amResource;
    String partition;

    if (rmApp == null || rmApp.getAMResourceRequest() == null) {
      // the rmApp may be undefined (the resource manager checks for this too)
      // and unmanaged applications do not provide an amResource request
      // in these cases, provide a default using the scheduler
      amResource = rmContext.getScheduler().getMinimumResourceCapability();
      partition = CommonNodeLabelsManager.NO_LABEL;
    } else {
      amResource = rmApp.getAMResourceRequest().getCapability();
      partition =
          (rmApp.getAMResourceRequest().getNodeLabelExpression() == null)
          ? CommonNodeLabelsManager.NO_LABEL
          : rmApp.getAMResourceRequest().getNodeLabelExpression();
    }

    setAppAMNodePartitionName(partition);
    setAMResource(partition, amResource);
    setAttemptRecovering(isAttemptRecovering);

    scheduler = rmContext.getScheduler();

    if (scheduler.getResourceCalculator() != null) {
      rc = scheduler.getResourceCalculator();
    }
    
    containerAllocator = new ContainerAllocator(this, rc, rmContext);

    if (scheduler instanceof CapacityScheduler) {
      capacitySchedulerContext = (CapacitySchedulerContext) scheduler;
    }
  }

  public synchronized boolean containerCompleted(RMContainer rmContainer,
      ContainerStatus containerStatus, RMContainerEventType event,
      String partition) {
    ContainerId containerId = rmContainer.getContainerId();

    // Remove from the list of containers
    if (null == liveContainers.remove(containerId)) {
      return false;
    }

    // Remove from the list of newly allocated containers if found
    newlyAllocatedContainers.remove(rmContainer);

    // Inform the container
    rmContainer.handle(
        new RMContainerFinishedEvent(containerId, containerStatus, event));

    containersToPreempt.remove(containerId);

    RMAuditLogger.logSuccess(getUser(),
        AuditConstants.RELEASE_CONTAINER, "SchedulerApp",
        getApplicationId(), containerId);
    
    // Update usage metrics 
    Resource containerResource = rmContainer.getContainer().getResource();
    queue.getMetrics().releaseResources(getUser(), 1, containerResource);
    attemptResourceUsage.decUsed(partition, containerResource);

    // Clear resource utilization metrics cache.
    lastMemoryAggregateAllocationUpdateTime = -1;

    return true;
  }

  public synchronized RMContainer allocate(NodeType type, FiCaSchedulerNode node,
      Priority priority, ResourceRequest request, 
      Container container) {

    if (isStopped) {
      return null;
    }
    
    // Required sanity check - AM can call 'allocate' to update resource 
    // request without locking the scheduler, hence we need to check
    if (getTotalRequiredResources(priority) <= 0) {
      return null;
    }
    
    // Create RMContainer
    RMContainer rmContainer =
        new RMContainerImpl(container, this.getApplicationAttemptId(),
            node.getNodeID(), appSchedulingInfo.getUser(), this.rmContext,
            request.getNodeLabelExpression());
    ((RMContainerImpl)rmContainer).setQueueName(this.getQueueName());

    updateAMContainerDiagnostics(AMState.ASSIGNED, null);

    // Add it to allContainers list.
    newlyAllocatedContainers.add(rmContainer);

    ContainerId containerId = container.getId();
    liveContainers.put(containerId, rmContainer);

    // Update consumption and track allocations
    List<ResourceRequest> resourceRequestList = appSchedulingInfo.allocate(
        type, node, priority, request, container);

    attemptResourceUsage.incUsed(node.getPartition(), container.getResource());

    // Update resource requests related to "request" and store in RMContainer
    ((RMContainerImpl)rmContainer).setResourceRequests(resourceRequestList);

    // Inform the container
    rmContainer.handle(
        new RMContainerEvent(containerId, RMContainerEventType.START));

    if (LOG.isDebugEnabled()) {
      LOG.debug("allocate: applicationAttemptId=" 
          + containerId.getApplicationAttemptId()
          + " container=" + containerId + " host="
          + container.getNodeId().getHost() + " type=" + type);
    }
    RMAuditLogger.logSuccess(getUser(),
        AuditConstants.ALLOC_CONTAINER, "SchedulerApp",
        getApplicationId(), containerId);
    
    return rmContainer;
  }

  public boolean unreserve(Priority priority,
      FiCaSchedulerNode node, RMContainer rmContainer) {
    // Done with the reservation?
    if (unreserve(node, priority)) {
      node.unreserveResource(this);

      // Update reserved metrics
      queue.getMetrics().unreserveResource(getUser(),
          rmContainer.getReservedResource());
      queue.decReservedResource(node.getPartition(),
          rmContainer.getReservedResource());
      return true;
    }
    return false;
  }

  @VisibleForTesting
  public synchronized boolean unreserve(FiCaSchedulerNode node, Priority priority) {
    Map<NodeId, RMContainer> reservedContainers =
      this.reservedContainers.get(priority);

    if (reservedContainers != null) {
      RMContainer reservedContainer = reservedContainers.remove(node.getNodeID());

      // unreserve is now triggered in new scenarios (preemption)
      // as a consequence reservedcontainer might be null, adding NP-checks
      if (reservedContainer != null
          && reservedContainer.getContainer() != null
          && reservedContainer.getContainer().getResource() != null) {

        if (reservedContainers.isEmpty()) {
          this.reservedContainers.remove(priority);
        }
        // Reset the re-reservation count
        resetReReservations(priority);

        Resource resource = reservedContainer.getContainer().getResource();
        this.attemptResourceUsage.decReserved(node.getPartition(), resource);

        LOG.info("Application " + getApplicationId() + " unreserved "
            + " on node " + node + ", currently has "
            + reservedContainers.size() + " at priority " + priority
            + "; currentReservation " + this.attemptResourceUsage.getReserved()
            + " on node-label=" + node.getPartition());
        return true;
      }
    }
    return false;
  }

  public synchronized float getLocalityWaitFactor(
      Priority priority, int clusterNodes) {
    // Estimate: Required unique resources (i.e. hosts + racks)
    int requiredResources = 
        Math.max(this.getResourceRequests(priority).size() - 1, 0);
    
    // waitFactor can't be more than '1' 
    // i.e. no point skipping more than clustersize opportunities
    return Math.min(((float)requiredResources / clusterNodes), 1.0f);
  }

  public synchronized Resource getTotalPendingRequests() {
    Resource ret = Resource.newInstance(0, 0);
    for (ResourceRequest rr : appSchedulingInfo.getAllResourceRequests()) {
      // to avoid double counting we count only "ANY" resource requests
      if (ResourceRequest.isAnyLocation(rr.getResourceName())){
        Resources.addTo(ret,
            Resources.multiply(rr.getCapability(), rr.getNumContainers()));
      }
    }
    return ret;
  }

  public synchronized Map<String, Resource> getTotalPendingRequestsPerPartition() {

    Map<String, Resource> ret = new HashMap<String, Resource>();
    Resource res = null;
    for (Priority  priority : appSchedulingInfo.getPriorities()) {
      ResourceRequest rr = appSchedulingInfo.getResourceRequest(priority, "*");
      if ((res = ret.get(rr.getNodeLabelExpression())) == null) {
        res = Resources.createResource(0, 0);
        ret.put(rr.getNodeLabelExpression(), res);
      }

      Resources.addTo(res,
          Resources.multiply(rr.getCapability(), rr.getNumContainers()));
    }
    return ret;
  }

  public synchronized void markContainerForPreemption(ContainerId cont) {
    // ignore already completed containers
    if (liveContainers.containsKey(cont)) {
      containersToPreempt.add(cont);
    }
  }

  /**
   * This method produces an Allocation that includes the current view
   * of the resources that will be allocated to and preempted from this
   * application.
   *
   * @param rc
   * @param clusterResource
   * @param minimumAllocation
   * @return an allocation
   */
  public synchronized Allocation getAllocation(ResourceCalculator rc,
      Resource clusterResource, Resource minimumAllocation) {

    Set<ContainerId> currentContPreemption = Collections.unmodifiableSet(
        new HashSet<ContainerId>(containersToPreempt));
    containersToPreempt.clear();
    Resource tot = Resource.newInstance(0, 0);
    for(ContainerId c : currentContPreemption){
      Resources.addTo(tot,
          liveContainers.get(c).getContainer().getResource());
    }
    int numCont = (int) Math.ceil(
        Resources.divide(rc, clusterResource, tot, minimumAllocation));
    ResourceRequest rr = ResourceRequest.newInstance(
        Priority.UNDEFINED, ResourceRequest.ANY,
        minimumAllocation, numCont);
    ContainersAndNMTokensAllocation allocation =
        pullNewlyAllocatedContainersAndNMTokens();
    Resource headroom = getHeadroom();
    setApplicationHeadroomForMetrics(headroom);
    return new Allocation(allocation.getContainerList(), headroom, null,
      currentContPreemption, Collections.singletonList(rr),
      allocation.getNMTokenList());
  }
  
  synchronized public NodeId getNodeIdToUnreserve(Priority priority,
      Resource resourceNeedUnreserve, ResourceCalculator rc,
      Resource clusterResource) {

    // first go around make this algorithm simple and just grab first
    // reservation that has enough resources
    Map<NodeId, RMContainer> reservedContainers = this.reservedContainers
        .get(priority);

    if ((reservedContainers != null) && (!reservedContainers.isEmpty())) {
      for (Map.Entry<NodeId, RMContainer> entry : reservedContainers.entrySet()) {
        NodeId nodeId = entry.getKey();
        Resource containerResource = entry.getValue().getContainer().getResource();
        
        // make sure we unreserve one with at least the same amount of
        // resources, otherwise could affect capacity limits
        if (Resources.lessThanOrEqual(rc, clusterResource,
            resourceNeedUnreserve, containerResource)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("unreserving node with reservation size: "
                + containerResource
                + " in order to allocate container with size: " + resourceNeedUnreserve);
          }
          return nodeId;
        }
      }
    }
    return null;
  }
  public synchronized void setHeadroomProvider(
    CapacityHeadroomProvider headroomProvider) {
    this.headroomProvider = headroomProvider;
  }

  public synchronized CapacityHeadroomProvider getHeadroomProvider() {
    return headroomProvider;
  }

  @Override
  public synchronized Resource getHeadroom() {
    if (headroomProvider != null) {
      return headroomProvider.getHeadroom();
    }
    return super.getHeadroom();
  }
  
  @Override
  public synchronized void transferStateFromPreviousAttempt(
      SchedulerApplicationAttempt appAttempt) {
    super.transferStateFromPreviousAttempt(appAttempt);
    this.headroomProvider = 
      ((FiCaSchedulerApp) appAttempt).getHeadroomProvider();
  }

  public void reserve(Priority priority,
      FiCaSchedulerNode node, RMContainer rmContainer, Container container) {
    // Update reserved metrics if this is the first reservation
    if (rmContainer == null) {
      queue.getMetrics().reserveResource(
          getUser(), container.getResource());
    }

    // Inform the application
    rmContainer = super.reserve(node, priority, rmContainer, container);

    // Update the node
    node.reserveResource(this, priority, rmContainer);
  }

  @VisibleForTesting
  public RMContainer findNodeToUnreserve(Resource clusterResource,
      FiCaSchedulerNode node, Priority priority,
      Resource minimumUnreservedResource) {
    // need to unreserve some other container first
    NodeId idToUnreserve =
        getNodeIdToUnreserve(priority, minimumUnreservedResource,
            rc, clusterResource);
    if (idToUnreserve == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("checked to see if could unreserve for app but nothing "
            + "reserved that matches for this app");
      }
      return null;
    }
    FiCaSchedulerNode nodeToUnreserve =
        ((CapacityScheduler) scheduler).getNode(idToUnreserve);
    if (nodeToUnreserve == null) {
      LOG.error("node to unreserve doesn't exist, nodeid: " + idToUnreserve);
      return null;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("unreserving for app: " + getApplicationId()
        + " on nodeId: " + idToUnreserve
        + " in order to replace reserved application and place it on node: "
        + node.getNodeID() + " needing: " + minimumUnreservedResource);
    }

    // headroom
    Resources.addTo(getHeadroom(), nodeToUnreserve
        .getReservedContainer().getReservedResource());

    return nodeToUnreserve.getReservedContainer();
  }

  public LeafQueue getCSLeafQueue() {
    return (LeafQueue)queue;
  }
  
  public CSAssignment assignContainers(Resource clusterResource,
      FiCaSchedulerNode node, ResourceLimits currentResourceLimits,
      SchedulingMode schedulingMode, RMContainer reservedContainer) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("pre-assignContainers for application "
          + getApplicationId());
      showRequests();
    }

    synchronized (this) {
      return containerAllocator.assignContainers(clusterResource, node,
          schedulingMode, currentResourceLimits, reservedContainer);
    }
  }
 
  public void nodePartitionUpdated(RMContainer rmContainer, String oldPartition,
      String newPartition) {
    Resource containerResource = rmContainer.getAllocatedResource();
    this.attemptResourceUsage.decUsed(oldPartition, containerResource);
    this.attemptResourceUsage.incUsed(newPartition, containerResource);
    getCSLeafQueue().decUsedResource(oldPartition, containerResource, this);
    getCSLeafQueue().incUsedResource(newPartition, containerResource, this);

    // Update new partition name if container is AM and also update AM resource
    if (rmContainer.isAMContainer()) {
      setAppAMNodePartitionName(newPartition);
      this.attemptResourceUsage.decAMUsed(oldPartition, containerResource);
      this.attemptResourceUsage.incAMUsed(newPartition, containerResource);
      getCSLeafQueue().decAMUsedResource(oldPartition, containerResource, this);
      getCSLeafQueue().incAMUsedResource(newPartition, containerResource, this);
    }
  }

  protected void getPendingAppDiagnosticMessage(
      StringBuilder diagnosticMessage) {
    LeafQueue queue = getCSLeafQueue();
    diagnosticMessage.append(" Details : AM Partition = ");
    diagnosticMessage.append(appAMNodePartitionName.isEmpty()
        ? NodeLabel.DEFAULT_NODE_LABEL_PARTITION : appAMNodePartitionName);
    diagnosticMessage.append("; ");
    diagnosticMessage.append("AM Resource Request = ");
    diagnosticMessage.append(getAMResource(appAMNodePartitionName));
    diagnosticMessage.append("; ");
    diagnosticMessage.append("Queue Resource Limit for AM = ");
    diagnosticMessage
        .append(queue.getAMResourceLimitPerPartition(appAMNodePartitionName));
    diagnosticMessage.append("; ");
    diagnosticMessage.append("User AM Resource Limit of the queue = ");
    diagnosticMessage.append(
        queue.getUserAMResourceLimitPerPartition(appAMNodePartitionName));
    diagnosticMessage.append("; ");
    diagnosticMessage.append("Queue AM Resource Usage = ");
    diagnosticMessage.append(
        queue.getQueueResourceUsage().getAMUsed(appAMNodePartitionName));
    diagnosticMessage.append("; ");
  }

  protected void getActivedAppDiagnosticMessage(
      StringBuilder diagnosticMessage) {
    LeafQueue queue = getCSLeafQueue();
    QueueCapacities queueCapacities = queue.getQueueCapacities();
    diagnosticMessage.append(" Details : AM Partition = ");
    diagnosticMessage.append(appAMNodePartitionName.isEmpty()
        ? NodeLabel.DEFAULT_NODE_LABEL_PARTITION : appAMNodePartitionName);
    diagnosticMessage.append(" ; ");
    diagnosticMessage.append("Partition Resource = ");
    diagnosticMessage.append(rmContext.getNodeLabelManager()
        .getResourceByLabel(appAMNodePartitionName, Resources.none()));
    diagnosticMessage.append(" ; ");
    diagnosticMessage.append("Queue's Absolute capacity = ");
    diagnosticMessage.append(
        queueCapacities.getAbsoluteCapacity(appAMNodePartitionName) * 100);
    diagnosticMessage.append(" % ; ");
    diagnosticMessage.append("Queue's Absolute used capacity = ");
    diagnosticMessage.append(
        queueCapacities.getAbsoluteUsedCapacity(appAMNodePartitionName) * 100);
    diagnosticMessage.append(" % ; ");
    diagnosticMessage.append("Queue's Absolute max capacity = ");
    diagnosticMessage.append(
        queueCapacities.getAbsoluteMaximumCapacity(appAMNodePartitionName)
            * 100);
    diagnosticMessage.append(" % ; ");
  }

  /**
   * Set the message temporarily if the reason is known for why scheduling did
   * not happen for a given node, if not message will be over written
   * @param message
   */
  public void updateAppSkipNodeDiagnostics(String message) {
    this.appSkipNodeDiagnostics = message;
  }

  public void updateNodeInfoForAMDiagnostics(FiCaSchedulerNode node) {
    if (isWaitingForAMContainer()) {
      StringBuilder diagnosticMessageBldr = new StringBuilder();
      if (appSkipNodeDiagnostics != null) {
        diagnosticMessageBldr.append(appSkipNodeDiagnostics);
        appSkipNodeDiagnostics = null;
      }
      diagnosticMessageBldr.append(
          CSAMContainerLaunchDiagnosticsConstants.LAST_NODE_PROCESSED_MSG);
      diagnosticMessageBldr.append(node.getNodeID());
      diagnosticMessageBldr.append(" ( Partition : ");
      diagnosticMessageBldr.append(node.getLabels());
      diagnosticMessageBldr.append(", Total resource : ");
      diagnosticMessageBldr.append(node.getTotalResource());
      diagnosticMessageBldr.append(", Available resource : ");
      diagnosticMessageBldr.append(node.getAvailableResource());
      diagnosticMessageBldr.append(" ).");
      updateAMContainerDiagnostics(AMState.ACTIVATED, diagnosticMessageBldr.toString());
    }
  }

  /**
   * Recalculates the per-app, percent of queue metric, specific to the Capacity
   * Scheduler.
   */
  @Override
  public synchronized ApplicationResourceUsageReport getResourceUsageReport() {
    ApplicationResourceUsageReport report = super.getResourceUsageReport();
    Resource cluster = rmContext.getScheduler().getClusterResource();
    Resource totalPartitionRes = rmContext.getNodeLabelManager()
        .getResourceByLabel(getAppAMNodePartitionName(), cluster);
    ResourceCalculator calc = rmContext.getScheduler().getResourceCalculator();
    float queueUsagePerc = 0.0f;
    if (!calc.isInvalidDivisor(totalPartitionRes)) {
      float queueAbsMaxCapPerPartition = ((AbstractCSQueue) getQueue())
          .getQueueCapacities()
          .getAbsoluteCapacity(getAppAMNodePartitionName());
      if (queueAbsMaxCapPerPartition != 0) {
        queueUsagePerc = calc.divide(totalPartitionRes,
            report.getUsedResources(),
            Resources.multiply(totalPartitionRes, queueAbsMaxCapPerPartition))
            * 100;
      }
      report.setQueueUsagePercentage(queueUsagePerc);
    }
    return report;
  }

  /**
   * Move reservation from one node to another
   * Comparing to unreserve container on source node and reserve a new
   * container on target node. This method will not create new RMContainer
   * instance. And this operation is atomic.
   *
   * @param reservedContainer to be moved reserved container
   * @param sourceNode source node
   * @param targetNode target node
   *
   * @return succeeded or not
   */
  public synchronized boolean moveReservation(RMContainer reservedContainer,
      FiCaSchedulerNode sourceNode, FiCaSchedulerNode targetNode) {
    if (!sourceNode.getPartition().equals(targetNode.getPartition())) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Failed to move reservation, two nodes are in different partition");
      }
      return false;
    }

    // Update reserved container to node map
    Map<NodeId, RMContainer> map = reservedContainers.get(
        reservedContainer.getReservedPriority());
    if (null == map) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Cannot find reserved container map.");
      }
      return false;
    }

    // Check if reserved container changed
    if (sourceNode.getReservedContainer() != reservedContainer) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("To-be-moved container already updated.");
      }
      return false;
    }

    // Check if target node is empty, acquires lock of target node to make sure
    // reservation happens transactional
    synchronized (targetNode){
      if (targetNode.getReservedContainer() != null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Target node is already occupied before moving");
        }
      }

      try {
        targetNode.reserveResource(this,
            reservedContainer.getReservedPriority(), reservedContainer);
      } catch (IllegalStateException e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Reserve on target node failed, e=", e);
        }
        return false;
      }

      // Set source node's reserved container to null
      sourceNode.setReservedContainer(null);
      map.remove(sourceNode.getNodeID());

      // Update reserved container
      reservedContainer.handle(
          new RMContainerReservedEvent(reservedContainer.getContainerId(),
              reservedContainer.getReservedResource(), targetNode.getNodeID(),
              reservedContainer.getReservedPriority()));

      // Add to target node
      map.put(targetNode.getNodeID(), reservedContainer);

      return true;
    }
  }
}
