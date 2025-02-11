/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.server

import java.util.Properties

import kafka.server.KafkaConfig.fromProps
import kafka.utils.CoreUtils._
import kafka.utils.TestUtils
import kafka.utils.TestUtils._
import kafka.zk.ZooKeeperTestHarness
import org.apache.kafka.clients.admin._
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.junit.jupiter.api.Assertions.{assertTrue, fail}
import org.junit.jupiter.api.{AfterEach, Test}

import scala.collection.JavaConverters._
import scala.collection.Map

class PreferredControllerTest extends ZooKeeperTestHarness {

  var brokers: Seq[KafkaServer] = null

  @AfterEach
  override def tearDown() {
    shutdownServers(brokers)
    super.tearDown()
  }

  @Test
  def testPartitionCreatedByAdminClientShouldNotBeAssignedToPreferredControllers(): Unit = {
    val brokerConfigs = Seq((0, false), (1, true), (2, false))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = true)

    val brokerList = TestUtils.bootstrapServers(brokers, ListenerName.forSecurityProtocol(SecurityProtocol.PLAINTEXT))
    val adminClientConfig = new Properties
    adminClientConfig.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList)
    val client = AdminClient.create(adminClientConfig)

    TestUtils.waitUntilControllerElected(zkClient)
    // create topic using admin client
    val future1 = client.createTopics(Seq("topic1").map(new NewTopic(_, 3, 2.toShort)).asJava,
      new CreateTopicsOptions()).all()
    future1.get()

    assertTrue(ensureTopicNotInBrokers("topic1", Set(1)), "topic1 should not be in broker 1")

    val future2 = client.createPartitions(Map("topic1" -> NewPartitions.increaseTo(5)).asJava).all()
    future2.get()

    assertTrue(ensureTopicNotInBrokers("topic1", Set(1)),
      "topic1 should not be in broker 1 after increasing partition count")

    client.close()
  }

  @Test
  def testElectionWithoutPreferredControllersAndNoFallback(): Unit = {
    val brokerConfigs = Seq((0, false), (1, false), (2, false))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = false)
    // no broker can be elected as controller
    ensureControllersInBrokers(Seq.empty, 5000L)
  }

  @Test
  def testPreferredControllerElection(): Unit = {
    val brokerConfigs = Seq((0, false), (1, true), (2, false))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = false)
    // only broker 1 can be elected since it is the only preferred controller node
    ensureControllersInBrokers(Seq(1))
  }


  @Test
  def testNonPreferredControllerResignation(): Unit = {
    val brokerConfigs = Seq((0, false), (1, true), (2, false))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = true)

    // broker 1 should be elected since it is the only preferred controller node
    ensureControllersInBrokers(Seq(1))
    brokers(1).shutdown()

    // broker 0 and broker 2 can become controller when broker 1 is offline
    ensureControllersInBrokers(Seq(0, 2))
    brokers(1).startup()
    // broker 1 regains controllership
    ensureControllersInBrokers(Seq(1))
  }

  @Test
  def testDynamicAllowPreferredControllerFallback(): Unit = {
    val brokerConfigs = Seq((0, false), (1, false), (2, false))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = false)

    // non preferred controller nodes cannot be elected as the controller if fallback is not allowed
    ensureControllersInBrokers(Seq.empty, 5000L)
    setAllowPreferredControllerFallback(true)
    // controller can be now elected among non preferred controller nodes
    TestUtils.waitUntilControllerElected(zkClient)
  }

  @Test
  def testCurrentControllerDoesNotResignWithoutPreferredControllersAndNoFallback(): Unit = {
    val brokerConfigs = Seq((0, false), (1, false), (2, false))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = true)

    val controllerId = TestUtils.waitUntilControllerElected(zkClient)

    setAllowPreferredControllerFallback(false)

    // current controller does not move
    ensureControllersInBrokers(Seq(controllerId))
  }

  @Test
  def testAllPreferredControllerDownWithPreferredControllersAndNoFallback(): Unit = {
    // create 5 brokers, 3 of the them are preferred controllers.
    val brokerConfigs = Seq((0, false), (1, false), (2, true), (3, true), (4, true))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = true)

    // the controller would be one of the preferred controllers.
    ensureControllersInBrokers(Seq(2, 3, 4))

    // shut down all preferred controllers
    brokers(2).shutdown()
    brokers(3).shutdown()
    brokers(4).shutdown()

    // verify the controller is now on non-preferred controller
    ensureControllersInBrokers(Seq(0, 1))

    // bring back one preferred controller
    brokers(2).startup()

    // the controller would be moved to the preferred controllers. (PreferredControllerChangeHandler)
    ensureControllersInBrokers(Seq(2))
  }

  @Test
  def testAllPreferredControllerDownWithoutPreferredControllersAndNoFallback(): Unit = {
    // create 5 brokers, 3 of the them are preferred controllers.
    val brokerConfigs = Seq((0, false), (1, false), (2, true), (3, true), (4, true))
    createBrokersWithPreferredControllers(brokerConfigs, allowFallback = false)

    // the controller would be one of the preferred controllers.
    ensureControllersInBrokers(Seq(2, 3, 4))

    // shut down all preferred controllers
    brokers(2).shutdown()
    brokers(3).shutdown()
    brokers(4).shutdown()

    // verify no controller
    ensureControllersInBrokers(Seq.empty, 5000L)

    // bring back preferred controllers
    brokers(3).startup()

    // the controller would be one of the preferred controllers.
    ensureControllersInBrokers(Seq(3))
  }

  private def ensureControllersInBrokers(brokerIds: Seq[Int], timeout: Long = 15000L): Unit = {
    val (controllerId, _) = TestUtils.computeUntilTrue(zkClient.getControllerId, waitTime = timeout) (
      _.exists(controllerId => brokerIds.isEmpty || brokerIds.contains(controllerId))
    )
    if (brokerIds.isEmpty) {
      assertTrue(controllerId.isEmpty, "there should not be any controller")
    } else {
      assertTrue(brokerIds.contains(controllerId.getOrElse(fail(s"Controller not elected after $timeout ms"))),
        s"Controller should be elected in $brokerIds")
    }
  }

  private def ensureTopicNotInBrokers(topic: String, brokerIds: Set[Int]): Boolean = {
    val topicAssignment = zkClient.getReplicaAssignmentForTopics(Set(topic))
    topicAssignment.flatMap(_._2).toSet.intersect(brokerIds).isEmpty
  }

  /**
    * @param brokerConfigs: a list of (brokerid, preferredController) configs
    * @param allowFallback: "allow.preferred.controller.fallback" config
    */
  private def createBrokersWithPreferredControllers(brokerConfigs: Seq[(Int, Boolean)],  allowFallback: Boolean): Unit = {
    brokers = brokerConfigs.map {
      case (id, preferredController) =>
        val props: Properties = createBrokerConfig(id, zkConnect)
        props.put(KafkaConfig.PreferredControllerProp, preferredController.toString)
        props.put(KafkaConfig.AllowPreferredControllerFallbackProp, allowFallback.toString)
        createServer(fromProps(props))
    }
  }

  private def setAllowPreferredControllerFallback(allowFallback: Boolean): Unit = {
    adminZkClient.changeBrokerConfig(None,
      propsWith((KafkaConfig.AllowPreferredControllerFallbackProp, allowFallback.toString)))

    TestUtils.waitUntilTrue(() => {
        brokers.forall(_.config.allowPreferredControllerFallback == allowFallback)
      },
      s"fail to set ${KafkaConfig.AllowPreferredControllerFallbackProp} to ${allowFallback}", 5000)
  }
}
