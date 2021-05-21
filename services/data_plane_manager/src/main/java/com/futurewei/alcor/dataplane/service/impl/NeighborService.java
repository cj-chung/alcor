/*
MIT License
Copyright(c) 2020 Futurewei Cloud

    Permission is hereby granted,
    free of charge, to any person obtaining a copy of this software and associated documentation files(the "Software"), to deal in the Software without restriction,
    including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and / or sell copies of the Software, and to permit persons
    to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
    
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
    WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/
package com.futurewei.alcor.dataplane.service.impl;

import com.futurewei.alcor.common.db.CacheException;
import com.futurewei.alcor.dataplane.cache.SubnetPortsCache;
import com.futurewei.alcor.dataplane.entity.MulticastGoalState;
import com.futurewei.alcor.dataplane.entity.UnicastGoalState;
import com.futurewei.alcor.dataplane.exception.NeighborInfoNotFound;
import com.futurewei.alcor.dataplane.exception.PortFixedIpNotFound;
import com.futurewei.alcor.schema.*;
import com.futurewei.alcor.web.entity.dataplane.InternalPortEntity;
import com.futurewei.alcor.web.entity.dataplane.NeighborEntry;
import com.futurewei.alcor.web.entity.dataplane.NeighborInfo;
import com.futurewei.alcor.web.entity.dataplane.v2.NetworkConfiguration;
import com.futurewei.alcor.web.entity.port.PortEntity;
import com.futurewei.alcor.web.entity.subnet.InternalSubnetPorts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class NeighborService extends ResourceService {
    @Autowired
    private SubnetPortsCache subnetPortsCache;

    public Neighbor.NeighborState buildNeighborState(NeighborEntry.NeighborType type, NeighborInfo neighborInfo, Common.OperationType operationType) {
        Neighbor.NeighborConfiguration.Builder neighborConfigBuilder = Neighbor.NeighborConfiguration.newBuilder();
        neighborConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
        neighborConfigBuilder.setId(neighborInfo.getPortId()); // TODO: We are going to need this per latest ACA change
        neighborConfigBuilder.setVpcId(neighborInfo.getVpcId());
        //neighborConfigBuilder.setName();
        neighborConfigBuilder.setMacAddress(neighborInfo.getPortMac());
        neighborConfigBuilder.setHostIpAddress(neighborInfo.getHostIp());
        Neighbor.NeighborType neighborType = Neighbor.NeighborType.valueOf(type.getType());

        //TODO:setNeighborHostDvrMac
        //neighborConfigBuilder.setNeighborHostDvrMac();
        Neighbor.NeighborConfiguration.FixedIp.Builder fixedIpBuilder = Neighbor.NeighborConfiguration.FixedIp.newBuilder();
        fixedIpBuilder.setSubnetId(neighborInfo.getSubnetId());
        fixedIpBuilder.setIpAddress(neighborInfo.getPortIp());
        fixedIpBuilder.setNeighborType(neighborType);
        neighborConfigBuilder.addFixedIps(fixedIpBuilder.build());
        //TODO:setAllowAddressPairs
        //neighborConfigBuilder.setAllowAddressPairs();

        Neighbor.NeighborState.Builder neighborStateBuilder = Neighbor.NeighborState.newBuilder();
        neighborStateBuilder.setOperationType(operationType);
        neighborStateBuilder.setConfiguration(neighborConfigBuilder.build());

        return neighborStateBuilder.build();
    }

    private List<NeighborInfo> buildNeighborInfosByPortEntities(NetworkConfiguration networkConfig) {
        List<NeighborInfo> neighborInfos = new ArrayList<>();

        List<InternalPortEntity> internalPortEntities = networkConfig.getPortEntities();
        if (internalPortEntities != null) {
            for (InternalPortEntity internalPortEntity: internalPortEntities) {
                String bindingHostIP = internalPortEntity.getBindingHostIP();
                if (bindingHostIP == null) {
                    continue;
                }

                for (PortEntity.FixedIp fixedIp: internalPortEntity.getFixedIps()) {
                    NeighborInfo neighborInfo = new NeighborInfo(bindingHostIP,
                            internalPortEntity.getBindingHostId(),
                            internalPortEntity.getId(),
                            internalPortEntity.getMacAddress(),
                            fixedIp.getIpAddress(),
                            internalPortEntity.getVpcId(),
                            fixedIp.getSubnetId());
                    neighborInfos.add(neighborInfo);
                }
            }
        }

        return neighborInfos;
    }

    public void buildNeighborStates(NetworkConfiguration networkConfig, String hostIp,
                                     UnicastGoalState unicastGoalState,
                                     MulticastGoalState multicastGoalState) throws Exception {
        Map<String, NeighborInfo> neighborInfos = networkConfig.getNeighborInfos();
        if (neighborInfos == null || neighborInfos.size() == 0) {
            return;
        }

        /**
         * PortEntities themselves are not included in neighborInfos, build neighborInfos
         * for them and add them to neighborInfo map before building neighborStates
         */
        List<NeighborInfo> neighborInfoList = buildNeighborInfosByPortEntities(networkConfig);
        for (NeighborInfo neighborInfo: neighborInfoList) {
            if (!neighborInfos.containsKey(neighborInfo.getPortIp())) {
                neighborInfos.put(neighborInfo.getPortIp(), neighborInfo);
            }
        }

        Map<String, List<NeighborEntry>> neighborTable = networkConfig.getNeighborTable();
        if (neighborTable == null || neighborTable.size() == 0) {
            return;
        }

        List<Port.PortState> portStates = unicastGoalState.getGoalStateBuilder().getPortStatesList();
        if (portStates == null || portStates.size() == 0) {
            return;
        }

        List<NeighborEntry> multicastNeighborEntries = new ArrayList<>();
        for (Port.PortState portState: portStates) {
            List<Port.PortConfiguration.FixedIp> fixedIps = portState.getConfiguration().getFixedIpsList();
            if (fixedIps == null) {
                throw new PortFixedIpNotFound();
            }

            for (Port.PortConfiguration.FixedIp fixedIp: fixedIps) {
                List<NeighborEntry> neighborEntries = neighborTable.get(fixedIp.getIpAddress());
                if (neighborEntries == null) {
                    throw new NeighborInfoNotFound();
                }

                for (NeighborEntry neighborEntry: neighborEntries) {
                    NeighborInfo neighborInfo = neighborInfos.get(neighborEntry.getNeighborIp());
                    if (neighborInfo == null) {
                        throw new NeighborInfoNotFound();
                    }

                    if (hostIp.equals(neighborInfo.getHostIp())) {
                        continue;
                    }

                    unicastGoalState.getGoalStateBuilder().addNeighborStates(buildNeighborState(
                            neighborEntry.getNeighborType(), neighborInfo, networkConfig.getOpType()));

                    // add neighbor's subnet into goalstate
                    //buildSubnetStateforNeighbor(unicastGoalState, neighborInfo, multicastGoalState);
                    multicastNeighborEntries.add(neighborEntry);
                }
            }
        }

        Set<NeighborInfo> neighborInfoSet = new HashSet<>();
        for (NeighborEntry neighborEntry: multicastNeighborEntries) {
            String localIp = neighborEntry.getLocalIp();
            String neighborIp = neighborEntry.getNeighborIp();
            NeighborInfo neighborInfo1 = neighborInfos.get(localIp);
            NeighborInfo neighborInfo2 = neighborInfos.get(neighborIp);
            if (neighborInfo1 == null || neighborInfo2 == null) {
                throw new NeighborInfoNotFound();
            }

            if (!multicastGoalState.getHostIps().contains(neighborInfo2.getHostIp())) {
                multicastGoalState.getHostIps().add(neighborInfo2.getHostIp());
            }

            if (!neighborInfoSet.contains(neighborInfo1)) {
                multicastGoalState.getGoalStateBuilder().addNeighborStates(buildNeighborState(
                        neighborEntry.getNeighborType(), neighborInfo1, networkConfig.getOpType()));
                neighborInfoSet.add(neighborInfo1);
            }
        }
    }

    private void buildSubnetStateforNeighbor(UnicastGoalState unicastGoalState, NeighborInfo neighborInfo, MulticastGoalState multicastGoalState) throws CacheException {
        if (neighborInfo == null) {
            return;
        }

        InternalSubnetPorts subnetEntity = subnetPortsCache.getSubnetPorts(neighborInfo.getSubnetId());
        if (subnetEntity == null) {
            return;
        }

        if (unicastGoalState.getGoalStateBuilder().getSubnetStatesList().stream()
                .filter(e -> e.getConfiguration().getId().equals(subnetEntity.getSubnetId()))
                .findFirst().orElse(null) == null) {
            Subnet.SubnetConfiguration.Builder subnetConfigBuilder = Subnet.SubnetConfiguration.newBuilder();
            subnetConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
            subnetConfigBuilder.setId(subnetEntity.getSubnetId());
            subnetConfigBuilder.setVpcId(subnetEntity.getVpcId());
            subnetConfigBuilder.setName(subnetEntity.getName());
            subnetConfigBuilder.setCidr(subnetEntity.getCidr());
            subnetConfigBuilder.setTunnelId(subnetEntity.getTunnelId());

            Subnet.SubnetConfiguration.Gateway.Builder gatewayBuilder = Subnet.SubnetConfiguration.Gateway.newBuilder();
            gatewayBuilder.setIpAddress(subnetEntity.getGatewayPortIp());
            gatewayBuilder.setMacAddress(subnetEntity.getGatewayPortMac());
            subnetConfigBuilder.setGateway(gatewayBuilder.build());

            if (subnetEntity.getDhcpEnable() != null) {
                subnetConfigBuilder.setDhcpEnable(subnetEntity.getDhcpEnable());
            }

            // TODO: need to set DNS based on latest contract

            Subnet.SubnetState.Builder subnetStateBuilder = Subnet.SubnetState.newBuilder();
            subnetStateBuilder.setOperationType(Common.OperationType.INFO);
            subnetStateBuilder.setConfiguration(subnetConfigBuilder.build());
            unicastGoalState.getGoalStateBuilder().addSubnetStates(subnetStateBuilder.build());
            multicastGoalState.getGoalStateBuilder().addSubnetStates(subnetStateBuilder.build());
        }
        // Add subnet to router_state
        Router.RouterConfiguration.SubnetRoutingTable.Builder subnetRoutingTableBuilder = Router.RouterConfiguration.SubnetRoutingTable.newBuilder();
        String subnetId = subnetEntity.getSubnetId();
        subnetRoutingTableBuilder.setSubnetId(subnetId);

        List<Router.RouterConfiguration.SubnetRoutingTable> subnetRoutingTablesList = new ArrayList<>();
        subnetRoutingTablesList.add(subnetRoutingTableBuilder.build());

        Goalstate.GoalState.Builder goalStateBuilder = unicastGoalState.getGoalStateBuilder();
//        List<Router.RouterState.Builder> routerStatesBuilders = goalStateBuilder.getRouterStatesBuilderList();
//        if (routerStatesBuilders != null && routerStatesBuilders.size() > 0) {
//            subnetRoutingTablesList.addAll(goalStateBuilder.
//                    getRouterStatesBuilder(0).
//                    getConfiguration().
//                    getSubnetRoutingTablesList());
//            goalStateBuilder.removeRouterStates(0);
//        }

        Router.RouterConfiguration.Builder routerConfigBuilder = Router.RouterConfiguration.newBuilder();
        routerConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
        routerConfigBuilder.setHostDvrMacAddress(HOST_DVR_MAC);
        routerConfigBuilder.setId(subnetEntity.getRouterId());
        routerConfigBuilder.addAllSubnetRoutingTables(subnetRoutingTablesList);
        Router.RouterState.Builder routerStateBuilder = Router.RouterState.newBuilder();
        routerStateBuilder.setConfiguration(routerConfigBuilder.build());
        unicastGoalState.getGoalStateBuilder().addRouterStates(routerStateBuilder.build());
    }
}
