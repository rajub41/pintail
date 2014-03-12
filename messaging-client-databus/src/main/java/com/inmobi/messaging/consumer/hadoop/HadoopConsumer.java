package com.inmobi.messaging.consumer.hadoop;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.inmobi.databus.partition.PartitionCheckpoint;
import com.inmobi.databus.partition.PartitionCheckpointList;
import com.inmobi.databus.partition.PartitionId;
import com.inmobi.databus.partition.PartitionReader;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.consumer.databus.AbstractMessagingDatabusConsumer;
import com.inmobi.messaging.consumer.databus.CheckpointList;
import com.inmobi.messaging.metrics.PartitionReaderStatsExposer;

public class HadoopConsumer extends AbstractMessagingDatabusConsumer
    implements HadoopConsumerConfig {

  private String[] clusterNames;
  private Path[] rootDirs;
  private FileSystem[] fileSystems;
  private String inputFormatClassName;
  public static String clusterNamePrefix = "hadoopcluster";

  protected void initializeConfig(ClientConfig config) throws IOException {
    super.initializeConfig(config);

    String rootDirStr = config.getString(rootDirsConfig);
    String[] rootDirStrs;
    if (rootDirStr == null) {
      throw new IllegalArgumentException("Missing root dir configuration: "
          + rootDirsConfig);
    } else {
      rootDirStrs = rootDirStr.split(",");
    }
    rootDirs = new Path[rootDirStrs.length];
    clusterNames = new String[rootDirStrs.length];
    fileSystems = new FileSystem[rootDirStrs.length];
    for (int i = 0; i < rootDirStrs.length; i++) {
      LOG.debug("Looking at rootDirStr:" + rootDirStrs[i]);
      rootDirs[i] = new Path(rootDirStrs[i]);
      fileSystems[i] = rootDirs[i].getFileSystem(conf);
      clusterNames[i] = clusterNamePrefix + i;
    }

    String clusterNameStr = config.getString(clustersNameConfig);
    String [] clusterNameStrs;
    if (clusterNameStr != null) {
      clusterNameStrs = clusterNameStr.split(",");
      //clusterNames = new String[clusterNameStrs.length];
      for (int i = 0; i < clusterNameStrs.length; i++) {
        PartitionId oldPid = new PartitionId(clusterNames[i], null);
        clusterNames[i] = clusterNameStrs[i];
        PartitionId newPid = new PartitionId(clusterNames[i], null);
        partitionIdMap.put(oldPid, newPid);
      }
      assert clusterNameStrs.length == rootDirStrs.length;
    } else {
      LOG.info("using default cluster names as clustersName config is missing");
    }

    inputFormatClassName = config.getString(inputFormatClassNameConfig,
        DEFAULT_INPUT_FORMAT_CLASSNAME);
    if (!partitionIdMap.isEmpty()) {
      currentCheckpoint.migrateCheckpoint(partitionIdMap);
    }
  }

  /**
   * Creates one partition reader for one rootdir
   */
  protected void createPartitionReaders() throws IOException {
    for (int i = 0; i < clusterNames.length; i++) {
      String clusterName = clusterNames[i];
      String fsUri = fileSystems[i].getUri().toString();
      LOG.debug("Creating partition reader for cluster:" + clusterName);

      // create partition id
      PartitionId id = new PartitionId(clusterName, null);
      /*if (!clusterName.equals(clusterNamePrefix + i)) {
        PartitionId defaultPid = new PartitionId(clusterNamePrefix + i, null);
        partitionIdMap.put(defaultPid, id);
      }*/
      // Get the partition checkpoint list from consumer checkpoint
      PartitionCheckpointList partitionCheckpointList = 
          ((CheckpointList) currentCheckpoint).preaprePartitionCheckPointList(id);

      Date partitionTimestamp = getPartitionTimestamp(id,
          partitionCheckpointList);
      PartitionReaderStatsExposer clusterMetrics =
          new PartitionReaderStatsExposer(topicName, consumerName, id.toString(),
              consumerNumber, fsUri);
      addStatsExposer(clusterMetrics);
      PartitionReader reader = new PartitionReader(id,
          partitionCheckpointList, fileSystems[i], buffer, rootDirs[i],
          conf, inputFormatClassName, partitionTimestamp,
          waitTimeForFileCreate, false, clusterMetrics, partitionMinList,
          stopTime);
      LOG.debug("Created partition " + id);
      readers.put(id, reader);
      messageConsumedMap.put(id, false);
    }
  }

  Path[] getRootDirs() {
    return rootDirs;
  }

  @Override
  protected void createCheckpoint() {
    currentCheckpoint = new CheckpointList(partitionMinList);
  }

}
