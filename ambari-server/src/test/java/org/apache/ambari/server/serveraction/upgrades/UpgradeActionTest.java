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
package org.apache.ambari.server.serveraction.upgrades;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.actionmanager.ExecutionCommandWrapper;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.OrmTestHelper;
import org.apache.ambari.server.orm.dao.ClusterVersionDAO;
import org.apache.ambari.server.orm.dao.HostDAO;
import org.apache.ambari.server.orm.dao.HostVersionDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.entities.ClusterVersionEntity;
import org.apache.ambari.server.orm.entities.HostVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.StackId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.persist.PersistService;

/**
 * Tests upgrade-related server side actions
 */
public class UpgradeActionTest {
  private static final String UPGRADE_VERSION = "2.2.1.0-2270";
  private static final String DOWNGRADE_VERSION = "2.2.0.0-2041";

  private Injector m_injector;

  @Before
  public void setup() throws Exception {
    m_injector = Guice.createInjector(new InMemoryDefaultTestModule());
    m_injector.getInstance(GuiceJpaInitializer.class);

  }

  @After
  public void teardown() throws Exception {
    m_injector.getInstance(PersistService.class).stop();
  }

  private void makeDowngradeCluster() throws Exception {
    String clusterName = "c1";
    String hostName = "h1";

    Clusters clusters = m_injector.getInstance(Clusters.class);
    clusters.addCluster(clusterName);

    StackId stackId = new StackId("HDP-2.1.1");

    Cluster c = clusters.getCluster(clusterName);
    c.setDesiredStackVersion(stackId);

    // add a host component
    clusters.addHost(hostName);

    Host host = clusters.getHost(hostName);

    Map<String, String> hostAttributes = new HashMap<String, String>();
    hostAttributes.put("os_family", "redhat");
    hostAttributes.put("os_release_version", "6");
    host.setHostAttributes(hostAttributes);
    host.persist();


    OrmTestHelper helper = m_injector.getInstance(OrmTestHelper.class);

    helper.getOrCreateRepositoryVersion(stackId.getStackId(), DOWNGRADE_VERSION);
    helper.getOrCreateRepositoryVersion(stackId.getStackId(), UPGRADE_VERSION);

    RepositoryVersionDAO repoVersionDao = m_injector.getInstance(RepositoryVersionDAO.class);
    HostVersionDAO hostVersionDao = m_injector.getInstance(HostVersionDAO.class);

    c.createClusterVersion(stackId.getStackId(), DOWNGRADE_VERSION, "admin",
        RepositoryVersionState.UPGRADING);
    c.createClusterVersion(stackId.getStackId(), UPGRADE_VERSION, "admin",
        RepositoryVersionState.INSTALLING);

    c.transitionClusterVersion(stackId.getStackId(), DOWNGRADE_VERSION, RepositoryVersionState.CURRENT);
    c.transitionClusterVersion(stackId.getStackId(), UPGRADE_VERSION, RepositoryVersionState.INSTALLED);
    c.transitionClusterVersion(stackId.getStackId(), UPGRADE_VERSION, RepositoryVersionState.UPGRADING);

    c.mapHostVersions(Collections.singleton(hostName), c.getCurrentClusterVersion(),
        RepositoryVersionState.CURRENT);

    HostDAO hostDAO = m_injector.getInstance(HostDAO.class);

    HostVersionEntity entity = new HostVersionEntity();
    entity.setHostEntity(hostDAO.findByName(hostName));
    entity.setHostName(hostName);
    entity.setRepositoryVersion(
        repoVersionDao.findByStackAndVersion(stackId.getStackId(), UPGRADE_VERSION));
    entity.setState(RepositoryVersionState.UPGRADING);
    hostVersionDao.create(entity);
  }


  @Test
  public void testFinalizeDowngrade() throws Exception {
    makeDowngradeCluster();

    Map<String, String> commandParams = new HashMap<String, String>();
    commandParams.put("upgrade_direction", "downgrade");
    commandParams.put("version", DOWNGRADE_VERSION);

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setCommandParams(commandParams);
    executionCommand.setClusterName("c1");

    HostRoleCommand hostRoleCommand = new HostRoleCommand(null, null, null, null);
    hostRoleCommand.setExecutionCommandWrapper(new ExecutionCommandWrapper(executionCommand));

    FinalizeUpgradeAction action = m_injector.getInstance(FinalizeUpgradeAction.class);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);

    CommandReport report = action.execute(null);
    assertNotNull(report);
    assertEquals(HostRoleStatus.COMPLETED.name(), report.getStatus());

    HostVersionDAO hostVersionDao = m_injector.getInstance(HostVersionDAO.class);

    for (HostVersionEntity entity : hostVersionDao.findByClusterAndHost("c1", "h1")) {
      if (entity.getRepositoryVersion().getVersion().equals(DOWNGRADE_VERSION)) {
        assertEquals(RepositoryVersionState.CURRENT, entity.getState());
      } else if (entity.getRepositoryVersion().getVersion().equals(UPGRADE_VERSION)) {
        assertEquals(RepositoryVersionState.INSTALLED, entity.getState());
      }
    }

    ClusterVersionDAO clusterVersionDao = m_injector.getInstance(ClusterVersionDAO.class);
    for (ClusterVersionEntity entity : clusterVersionDao.findByCluster("c1")) {
      if (entity.getRepositoryVersion().getVersion().equals(DOWNGRADE_VERSION)) {
        assertEquals(RepositoryVersionState.CURRENT, entity.getState());
      } else if (entity.getRepositoryVersion().getVersion().equals(UPGRADE_VERSION)) {
        assertEquals(RepositoryVersionState.INSTALLED, entity.getState());
      }
    }

  }


}
