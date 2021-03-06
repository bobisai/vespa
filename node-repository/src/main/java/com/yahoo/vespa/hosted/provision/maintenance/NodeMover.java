// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.NodeType;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.provisioning.HostCapacity;

import java.time.Duration;
import java.time.Instant;

/**
 * Base class for maintainers that move nodes.
 *
 * @author mpolden
 */
public abstract class NodeMover<MOVE> extends NodeRepositoryMaintainer {

    static final Duration waitTimeAfterPreviousDeployment = Duration.ofMinutes(10);

    private final Deployer deployer;
    private final MOVE emptyMove;

    public NodeMover(Deployer deployer, NodeRepository nodeRepository, Duration interval, Metric metric, MOVE emptyMove) {
        super(nodeRepository, interval, metric);
        this.deployer = deployer;
        this.emptyMove = emptyMove;
    }

    /** Returns a suggested move for given node */
    protected abstract MOVE suggestedMove(Node node, Node fromHost, Node toHost, NodeList allNodes);

    /** Find the best possible move */
    protected final MOVE findBestMove(NodeList allNodes) {
        HostCapacity capacity = new HostCapacity(allNodes, nodeRepository().resourcesCalculator());
        MOVE bestMove = emptyMove;
        NodeList activeNodes = allNodes.nodeType(NodeType.tenant).state(Node.State.active);
        for (Node node : activeNodes) {
            if (node.parentHostname().isEmpty()) continue;
            ApplicationId applicationId = node.allocation().get().owner();
            if (applicationId.instance().isTester()) continue;
            if (deployedRecently(applicationId)) continue;
            for (Node toHost : allNodes.matching(nodeRepository()::canAllocateTenantNodeTo)) {
                if (toHost.hostname().equals(node.parentHostname().get())) continue;
                if ( ! capacity.freeCapacityOf(toHost).satisfies(node.resources())) continue;

                MOVE suggestedMove = suggestedMove(node, allNodes.parentOf(node).get(), toHost, allNodes);
                bestMove = bestMoveOf(bestMove, suggestedMove);
            }
        }
        return bestMove;
    }

    /** Returns the best move of given moves */
    protected abstract MOVE bestMoveOf(MOVE a, MOVE b);

    private boolean deployedRecently(ApplicationId application) {
        Instant now = nodeRepository().clock().instant();
        return deployer.lastDeployTime(application)
                       .map(lastDeployTime -> lastDeployTime.isAfter(now.minus(waitTimeAfterPreviousDeployment)))
                       // We only know last deploy time for applications that were deployed on this config server,
                       // the rest will be deployed on another config server
                       .orElse(true);
    }

    /** Returns true if no active nodes are retiring or about to be retired */
    static boolean zoneIsStable(NodeList allNodes) {
        NodeList active = allNodes.state(Node.State.active);
        if (active.stream().anyMatch(node -> node.allocation().get().membership().retired())) return false;
        if (active.stream().anyMatch(node -> node.status().wantToRetire())) return false;
        return true;
    }

}
