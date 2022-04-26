// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/proc/FrontendsProcNode.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.proc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.service.FrontendOptions;
import com.starrocks.system.Frontend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/*
 * Show current added frontends
 * SHOW PROC /frontends/
 */
public class FrontendsProcNode implements ProcNodeInterface {
    private static final Logger LOG = LogManager.getLogger(FrontendsProcNode.class);

    public static final ImmutableList<String> TITLE_NAMES = new ImmutableList.Builder<String>()
            .add("Name").add("IP").add("HostName").add("EditLogPort").add("HttpPort").add("QueryPort").add("RpcPort")
            .add("Role").add("IsMaster").add("ClusterId").add("Join").add("Alive").add("ReplayedJournalId")
            .add("LastHeartbeat").add("IsHelper").add("ErrMsg").add("StartTime").add("Version")
            .build();

    public static final int HOSTNAME_INDEX = 2;

    private GlobalStateMgr globalStateMgr;

    public FrontendsProcNode(GlobalStateMgr globalStateMgr) {
        this.globalStateMgr = globalStateMgr;
    }

    @Override
    public ProcResult fetchResult() {
        BaseProcResult result = new BaseProcResult();
        result.setNames(TITLE_NAMES);

        List<List<String>> infos = Lists.newArrayList();

        getFrontendsInfo(globalStateMgr, infos);

        for (List<String> info : infos) {
            result.addRow(info);
        }

        return result;
    }

    public static void getFrontendsInfo(GlobalStateMgr globalStateMgr, List<List<String>> infos) {
        String masterIp = GlobalStateMgr.getCurrentState().getMasterIp();
        if (masterIp == null) {
            masterIp = "";
        }

        // get all node which are joined in bdb group
        List<InetSocketAddress> allFe = globalStateMgr.getHaProtocol().getElectableNodes(true /* include leader */);
        allFe.addAll(globalStateMgr.getHaProtocol().getObserverNodes());
        List<Pair<String, Integer>> allFeHosts = convertToHostPortPair(allFe);
        List<Pair<String, Integer>> helperNodes = globalStateMgr.getHelperNodes();

        for (Frontend fe : globalStateMgr.getFrontends(null /* all */)) {

            List<String> info = new ArrayList<String>();
            info.add(fe.getNodeName());
            info.add(fe.getHost());

            info.add(FrontendOptions.getHostnameByIp(fe.getHost()));
            info.add(Integer.toString(fe.getEditLogPort()));
            info.add(Integer.toString(Config.http_port));

            if (fe.getHost().equals(globalStateMgr.getSelfNode().first)) {
                info.add(Integer.toString(Config.query_port));
                info.add(Integer.toString(Config.rpc_port));
            } else {
                info.add(Integer.toString(fe.getQueryPort()));
                info.add(Integer.toString(fe.getRpcPort()));
            }

            info.add(fe.getRole().name());
            info.add(String.valueOf(fe.getHost().equals(masterIp)));

            info.add(Integer.toString(globalStateMgr.getClusterId()));
            info.add(String.valueOf(isJoin(allFeHosts, fe)));

            if (fe.getHost().equals(globalStateMgr.getSelfNode().first)) {
                info.add("true");
                info.add(Long.toString(globalStateMgr.getEditLog().getMaxJournalId()));
            } else {
                info.add(String.valueOf(fe.isAlive()));
                info.add(Long.toString(fe.getReplayedJournalId()));
            }
            info.add(TimeUtils.longToTimeString(fe.getLastUpdateTime()));
            info.add(String.valueOf(isHelperNode(helperNodes, fe)));
            info.add(fe.getHeartbeatErrMsg());

            if (fe.isAlive()) {
                info.add(TimeUtils.longToTimeString(fe.getStartTime()));
            } else {
                info.add("NULL");
            }

            if (fe.getFeVersion() == null) {
                info.add("NULL");
            } else {
                info.add(fe.getFeVersion());
            }

            infos.add(info);
        }
    }

    private static boolean isHelperNode(List<Pair<String, Integer>> helperNodes, Frontend fe) {
        return helperNodes.stream().anyMatch(p -> p.first.equals(fe.getHost()) && p.second == fe.getEditLogPort());
    }

    private static boolean isJoin(List<Pair<String, Integer>> allFeHosts, Frontend fe) {
        for (Pair<String, Integer> pair : allFeHosts) {
            if (fe.getHost().equals(pair.first) && fe.getEditLogPort() == pair.second) {
                return true;
            }
        }
        return false;
    }

    private static List<Pair<String, Integer>> convertToHostPortPair(List<InetSocketAddress> addrs) {
        List<Pair<String, Integer>> hostPortPair = Lists.newArrayList();
        for (InetSocketAddress addr : addrs) {
            hostPortPair.add(Pair.create(addr.getAddress().getHostAddress(), addr.getPort()));
        }
        return hostPortPair;
    }
}

