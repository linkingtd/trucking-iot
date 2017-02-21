package com.orendainx.hortonworks.trucking.topology

import better.files.File
import com.hortonworks.registries.schemaregistry.client.SchemaRegistryClient
import com.orendainx.hortonworks.trucking.topology.bolts.{DataWindowingBolt, DeserializerBolt, TruckAndTrafficJoinBolt}
import com.orendainx.hortonworks.trucking.topology.nifi.DataPacketBuilder
import com.typesafe.config.{ConfigFactory, Config => TypeConfig}
import com.typesafe.scalalogging.Logger
import org.apache.nifi.remote.client.SiteToSiteClient
import org.apache.nifi.storm.{NiFiBolt, NiFiSpout}
import org.apache.storm.generated.StormTopology
import org.apache.storm.topology.TopologyBuilder
import org.apache.storm.topology.base.BaseWindowedBolt
import org.apache.storm.{Config, StormSubmitter}

import scala.concurrent.duration._

/**
  * Companion object to [[TruckingTopology]] class.
  * Provides an entry point for passing in a custom configuration.
  *
  * @author Edgar Orendain <edgar@orendainx.com>
  */
object TruckingTopology3 {

  def main(args: Array[String]): Unit = {
    // Build and submit the Storm config and topology
    val (stormConfig, topology) = buildDefaultStormConfigAndTopology()
    StormSubmitter.submitTopology("truckingTopology", stormConfig, topology)
  }

  /**
    * Build a Storm Config and Topology with the default configuration.
    *
    * @return A 2-tuple ([[Config]], [[StormTopology]])
    */
  def buildDefaultStormConfigAndTopology(): (Config, StormTopology) = {
    val config = ConfigFactory.load()

    // Set up configuration for the Storm Topology
    val stormConfig = new Config()
    stormConfig.setDebug(config.getBoolean(Config.TOPOLOGY_DEBUG))
    stormConfig.setMessageTimeoutSecs(config.getInt(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS))
    stormConfig.setNumWorkers(config.getInt(Config.TOPOLOGY_WORKERS))
    stormConfig.put(SchemaRegistryClient.Configuration.SCHEMA_REGISTRY_URL.name(), config.getString("schema-registry.url"))
    stormConfig.put("emptyConfig", new java.util.HashMap[String, String])
    // TODO: would be nice if storm.Config had "setProperty" to hide hashmap implementation

    (stormConfig, new TruckingTopology3(config).buildTopology())
  }
}

/**
  * Create a topology with the following components.
  *
  * Spouts:
  *   - NiFiSpout (for injesting EnrichedTruckData from NiFi)
  *   - NiFiSpout (for injesting TrafficData from NiFi)
  * Bolt:
  *   - DeserializerBolt (for deserializing using Schema Registry)
  *   - TruckAndTrafficStreamJoinBolt (for joining EnrichedTruckData and TrafficData streams into one)
  *   - DataWindowingBolt (generating driver stats from trucking data)
  *   - SerializingBolt (for serializing using Schema Registry)
  *   - NiFiBolt (for sending data back out to NiFi)
  *
  * @author Edgar Orendain <edgar@orendainx.com>
  */
class TruckingTopology3(config: TypeConfig) {

  private lazy val logger = Logger(classOf[TruckingTopology])
  private lazy val NiFiUrl: String = config.getString("nifi.url")

  /**
    *
    * @return a built StormTopology
    */
  def buildTopology(): StormTopology = {

    // Builder to perform the construction of the topology.
    implicit val builder = new TopologyBuilder()

    // Default number of tasks (instances) of components to spawn
    val taskCount = config.getInt(Config.TOPOLOGY_TASKS)

    // Build Nifi Spouts to ingest trucking data
    // Extract values from config
    val batchDuration = config.getLong(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS)
    val truckNifiPort = config.getString("nifi.truck-data.port-name")

    // This assumes that the data is text data, as it will map the byte array received from NiFi to a UTF-8 Encoded string.
    // Attempt to sync up with the join bolt, keeping back pressure in NiFi
    val truckSpoutConfig = new SiteToSiteClient.Builder().url(NiFiUrl).portName(truckNifiPort)
      .requestBatchDuration(batchDuration, MILLISECONDS).buildConfig()

    // Create a spout with the specified configuration, and place it in the topology blueprint
    builder.setSpout("enrichedTruckData", new NiFiSpout(truckSpoutConfig), taskCount)



    // Extract values from config
    val trafficNifiPort = config.getString("nifi.traffic-data.port-name")

    // This assumes that the data is text data, as it will map the byte array received from NiFi to a UTF-8 Encoded string.
    // Attempt to sync up with the join bolt, keeping back pressure in NiFi
    val trafficSpoutConfig = new SiteToSiteClient.Builder().url(NiFiUrl).portName(trafficNifiPort)
      .requestBatchDuration(batchDuration, MILLISECONDS).buildConfig()

    // Create a spout with the specified configuration, and place it in the topology blueprint
    builder.setSpout("trafficData", new NiFiSpout(trafficSpoutConfig), taskCount)






    // Build a DeserializerBolt to deserialize data from NiFi
    // Extract values from config

    // Create bolt and place it into the topology
    builder.setBolt("deserializedData", new DeserializerBolt(), taskCount).shuffleGrouping("enrichedTruckData").shuffleGrouping("trafficData")






    // Build Bolt to merge windowed data streams, and then generate sliding windowed driving stats
    // Extract values from config
    val duration = config.getInt(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS)

    // Create a bolt with a tumbling window
    val bolt = new TruckAndTrafficJoinBolt().withTumblingWindow(new BaseWindowedBolt.Duration(duration, MILLISECONDS))

    // Place the bolt in the topology blueprint
    builder.setBolt("joinedData", bolt, taskCount).globalGrouping("deserializedData")




    buildWindowedDriverStatsBolt()

    // Build a SerializerBolt to serialize data back out
    //buildSerializerBolt()

    // Two bolts to push back to NiFi
    buildJoinedDataToNifiBolt()
    buildDriverStatsNiFiBolt()

    logger.info("Storm topology finished building.")

    // Finally, create the topology
    builder.createTopology()
  }

  def buildJoinBolt()(implicit builder: TopologyBuilder): Unit = {
    // Extract values from config
    val taskCount = config.getInt(Config.TOPOLOGY_TASKS)
    val duration = config.getInt(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS)

    // Create a bolt with a tumbling window
    val bolt = new TruckAndTrafficJoinBolt().withTumblingWindow(new BaseWindowedBolt.Duration(duration, MILLISECONDS))

    // Place the bolt in the topology blueprint
    builder.setBolt("joinedData", bolt, taskCount).globalGrouping("deserializedData")
  }

  def buildWindowedDriverStatsBolt()(implicit builder: TopologyBuilder): Unit = {
    // Extract values from config
    val taskCount = config.getInt(Config.TOPOLOGY_TASKS)
    val windowLength = new BaseWindowedBolt.Duration(10, SECONDS)
    val slidingInterval = new BaseWindowedBolt.Duration(1, SECONDS)

    // Create a bolt with a sliding window
    val bolt = new DataWindowingBolt().withWindow(windowLength, slidingInterval)

    // Place the bolt in the topology blueprint
    builder.setBolt("windowedDriverStats", bolt, taskCount).shuffleGrouping("joinedData")
  }

  def buildJoinedDataToNifiBolt()(implicit builder: TopologyBuilder): Unit = {
    // Extract values from config
    val taskCount = config.getInt(Config.TOPOLOGY_TASKS)
    val nifiPort = config.getString("nifi.truck-and-traffic-data.port-name")
    val frequency = config.getInt("nifi.truck-and-traffic-data.tick-frequency")
    val batchSize = config.getInt("nifi.truck-and-traffic-data.batch-size")

    // Construct a clientConfig and a NiFi bolt
    val clientConfig = new SiteToSiteClient.Builder().url(NiFiUrl).portName(nifiPort).buildConfig()
    val nifiBolt = new NiFiBolt(clientConfig, new DataPacketBuilder(), frequency).withBatchSize(batchSize)

    builder.setBolt("joinedDataToNiFi", nifiBolt, taskCount).shuffleGrouping("joinedData")
  }

  def buildDriverStatsNiFiBolt()(implicit builder: TopologyBuilder): Unit = {
    // Extract values from config
    val taskCount = config.getInt(Config.TOPOLOGY_TASKS)
    val nifiPort = config.getString("nifi.driver-stats.port-name")
    val frequency = config.getInt("nifi.driver-stats.tick-frequency")
    val batchSize = config.getInt("nifi.driver-stats.batch-size")

    // Construct a clientConfig and a NiFi bolt
    val clientConfig = new SiteToSiteClient.Builder().url(NiFiUrl).portName(nifiPort).buildConfig()
    val nifiBolt = new NiFiBolt(clientConfig, new DataPacketBuilder(), frequency).withBatchSize(batchSize)

    builder.setBolt("driverStatsToNifi", nifiBolt, taskCount).shuffleGrouping("windowedDriverStats")
  }
}
