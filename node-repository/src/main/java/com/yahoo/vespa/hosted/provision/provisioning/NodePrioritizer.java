// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds up data structures necessary for node prioritization. It wraps each node
 * up in a {@link NodeCandidate} object with attributes used in sorting.
 *
 * The prioritization logic is implemented by {@link NodeCandidate}.
 *
 * @author smorgrav
 */
public class NodePrioritizer {

    private final List<NodeCandidate> nodes = new ArrayList<>();
    private final LockedNodeList allNodes;
    private final HostCapacity capacity;
    private final NodeSpec requestedNodes;
    private final ApplicationId application;
    private final ClusterSpec clusterSpec;
    private final NodeRepository nodeRepository;
    /** Whether node specification allows new nodes to be allocated. */
    private final boolean canAllocateNew;
    private final boolean canAllocateToSpaceHosts;
    private final boolean topologyChange;
    private final int currentClusterSize;
    private final Set<Node> spareHosts;

    NodePrioritizer(LockedNodeList allNodes, ApplicationId application, ClusterSpec clusterSpec, NodeSpec nodeSpec,
                    int wantedGroups, NodeRepository nodeRepository) {
        boolean dynamicProvisioning = nodeRepository.zone().getCloud().dynamicProvisioning();

        this.allNodes = allNodes;
        this.capacity = new HostCapacity(allNodes, nodeRepository.resourcesCalculator());
        this.requestedNodes = nodeSpec;
        this.clusterSpec = clusterSpec;
        this.application = application;
        this.spareHosts = dynamicProvisioning ?
                capacity.findSpareHostsInDynamicallyProvisionedZones(allNodes.asList()) :
                capacity.findSpareHosts(allNodes.asList(), nodeRepository.spareCount());
        this.nodeRepository = nodeRepository;

        NodeList nodesInCluster = allNodes.owner(application).type(clusterSpec.type()).cluster(clusterSpec.id());
        NodeList nonRetiredNodesInCluster = nodesInCluster.not().retired();
        long currentGroups = nonRetiredNodesInCluster.state(Node.State.active).stream()
                .flatMap(node -> node.allocation()
                        .flatMap(alloc -> alloc.membership().cluster().group().map(ClusterSpec.Group::index))
                        .stream())
                .distinct()
                .count();
        this.topologyChange = currentGroups != wantedGroups;

        this.currentClusterSize = (int) nonRetiredNodesInCluster.state(Node.State.active).stream()
                .map(node -> node.allocation().flatMap(alloc -> alloc.membership().cluster().group()))
                .filter(clusterSpec.group()::equals)
                .count();

        // In dynamically provisioned zones, we can always take spare hosts since we can provision new on-demand,
        // NodeCandidate::compareTo will ensure that they will not be used until there is no room elsewhere.
        // In non-dynamically provisioned zones, we only allow allocating to spare hosts to replace failed nodes.
        this.canAllocateToSpaceHosts = isReplacement(
                nodesInCluster.size(), nodesInCluster.state(Node.State.failed).size()) || dynamicProvisioning;
        // Do not allocate new nodes for exclusive deployments in dynamically provisioned zones: provision new host instead.
        this.canAllocateNew = requestedNodes instanceof NodeSpec.CountNodeSpec
                && (!dynamicProvisioning || !requestedNodes.isExclusive());
    }

    /** Collects all node candidates for this application and returns them in the most-to-least preferred order */
    List<NodeCandidate> collect(List<Node> surplusActiveNodes) {
        addApplicationNodes();
        addSurplusNodes(surplusActiveNodes);
        addReadyNodes();
        addNewNodes();
        return prioritize();
    }

    /** Returns the list of nodes sorted by {@link NodeCandidate#compareTo(NodeCandidate)} */
    private List<NodeCandidate> prioritize() {
        // Group candidates by their switch hostname
        Map<Optional<String>, List<NodeCandidate>> candidatesBySwitch = this.nodes.stream()
                .collect(Collectors.groupingBy(candidate -> candidate.parent.orElseGet(candidate::toNode).switchHostname()));
        // Mark lower priority nodes on shared switch as non-exclusive
        List<NodeCandidate> nodes = new ArrayList<>(this.nodes.size());
        for (var clusterSwitch : candidatesBySwitch.keySet()) {
            List<NodeCandidate> switchCandidates = candidatesBySwitch.get(clusterSwitch);
            if (clusterSwitch.isEmpty()) {
                nodes.addAll(switchCandidates); // Nodes are on exclusive switch by default
            } else {
                Collections.sort(switchCandidates);
                NodeCandidate bestNode = switchCandidates.get(0);
                nodes.add(bestNode);
                for (var node : switchCandidates.subList(1, switchCandidates.size())) {
                    nodes.add(node.withExclusiveSwitch(false));
                }
            }
        }
        Collections.sort(nodes);
        return nodes;
    }

    /**
     * Add nodes that have been previously reserved to the same application from
     * an earlier downsizing of a cluster
     */
    private void addSurplusNodes(List<Node> surplusNodes) {
        for (Node node : surplusNodes) {
            NodeCandidate candidate = candidateFrom(node, true);
            if (!candidate.violatesSpares || canAllocateToSpaceHosts) {
                nodes.add(candidate);
            }
        }
    }

    /** Add a node on each host with enough capacity for the requested flavor  */
    private void addNewNodes() {
        if ( !canAllocateNew) return;

        for (Node host : allNodes) {
            if (host.type() == NodeType.host && !nodeRepository.canAllocateTenantNodeTo(host)) continue;
            if (host.reservedTo().isPresent() && !host.reservedTo().get().equals(application.tenant())) continue;
            if (host.exclusiveTo().isPresent()) continue; // Never allocate new nodes to exclusive hosts
            if ( spareHosts.contains(host) && !canAllocateToSpaceHosts) continue;
            if ( ! capacity.hasCapacity(host, requestedNodes.resources().get())) continue;
            if ( ! allNodes.childrenOf(host).owner(application).cluster(clusterSpec.id()).isEmpty()) continue;
            nodes.add(NodeCandidate.createNewChild(requestedNodes.resources().get(),
                                                   capacity.freeCapacityOf(host, false),
                                                   host,
                                                   spareHosts.contains(host),
                                                   allNodes,
                                                   nodeRepository));
        }
    }

    /** Add existing nodes allocated to the application */
    private void addApplicationNodes() {
        EnumSet<Node.State> legalStates = EnumSet.of(Node.State.active, Node.State.inactive, Node.State.reserved);
        allNodes.asList().stream()
                .filter(node -> node.type() == requestedNodes.type())
                .filter(node -> legalStates.contains(node.state()))
                .filter(node -> node.allocation().isPresent())
                .filter(node -> node.allocation().get().owner().equals(application))
                .filter(node -> node.allocation().get().membership().cluster().id().equals(clusterSpec.id()))
                .filter(node -> node.state() == Node.State.active || canStillAllocateToParentOf(node))
                .map(node -> candidateFrom(node, false))
                .forEach(nodes::add);
    }

    /** Add nodes already provisioned, but not allocated to any application */
    private void addReadyNodes() {
        allNodes.asList().stream()
                .filter(node -> node.type() == requestedNodes.type())
                .filter(node -> node.state() == Node.State.ready)
                .map(node -> candidateFrom(node, false))
                .filter(n -> !n.violatesSpares || canAllocateToSpaceHosts)
                .forEach(nodes::add);
    }

    /** Create a candidate from given pre-existing node */
    private NodeCandidate candidateFrom(Node node, boolean isSurplus) {
        Optional<Node> parent = allNodes.parentOf(node);
        if (parent.isPresent()) {
            return NodeCandidate.createChild(node,
                                             capacity.freeCapacityOf(parent.get(), false),
                                             parent.get(),
                                             spareHosts.contains(parent.get()),
                                             isSurplus,
                                             false,
                                             parent.get().exclusiveTo().isEmpty()
                                             && requestedNodes.canResize(node.resources(),
                                                                         capacity.freeCapacityOf(parent.get(), false),
                                                                         topologyChange,
                                                                         currentClusterSize));
        }
        else {
            return NodeCandidate.createStandalone(node, isSurplus, false);
        }
    }

    /** Returns whether we are allocating to replace a failed node */
    private boolean isReplacement(int nodesInCluster, int failedNodesInCluster) {
        if (failedNodesInCluster == 0) return false;
        return ! requestedNodes.fulfilledBy(nodesInCluster - failedNodesInCluster);
    }

    /**
     * We may regret that a non-active node is allocated to a host and not offer it to the application
     * now, e.g if we want to retire the host.
     *
     * @return true if we still want to allocate the given node to its parent
     */
    private boolean canStillAllocateToParentOf(Node node) {
        if (node.parentHostname().isEmpty()) return true;
        Optional<Node> parent = allNodes.parentOf(node);
        if (parent.isEmpty()) return false;
        return nodeRepository.canAllocateTenantNodeTo(parent.get());
    }

}
