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

package org.apache.hadoop.hdds.scm.pipeline;

import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.utils.MetadataStore;
import org.apache.hadoop.utils.MetadataStoreBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.apache.hadoop.hdds.scm
    .ScmConfigKeys.OZONE_SCM_DB_CACHE_SIZE_DEFAULT;
import static org.apache.hadoop.hdds.scm
    .ScmConfigKeys.OZONE_SCM_DB_CACHE_SIZE_MB;
import static org.apache.hadoop.hdds.server.ServerUtils.getOzoneMetaDirPath;
import static org.apache.hadoop.ozone.OzoneConsts.SCM_PIPELINE_DB;

/**
 * Implements api needed for management of pipelines. All the write operations
 * for pipelines must come via PipelineManager. It synchronises all write
 * and read operations via a ReadWriteLock.
 */
public class SCMPipelineManager implements PipelineManager {

  private static final Logger LOG =
      LoggerFactory.getLogger(SCMPipelineManager.class);

  private final ReadWriteLock lock;
  private final PipelineFactory pipelineFactory;
  private final PipelineStateManager stateManager;
  private final MetadataStore pipelineStore;

  public SCMPipelineManager(Configuration conf, NodeManager nodeManager)
      throws IOException {
    this.lock = new ReentrantReadWriteLock();
    this.stateManager = new PipelineStateManager(conf);
    this.pipelineFactory = new PipelineFactory(nodeManager, stateManager);
    int cacheSize = conf.getInt(OZONE_SCM_DB_CACHE_SIZE_MB,
        OZONE_SCM_DB_CACHE_SIZE_DEFAULT);
    File metaDir = getOzoneMetaDirPath(conf);
    File pipelineDBPath = new File(metaDir, SCM_PIPELINE_DB);
    this.pipelineStore =
        MetadataStoreBuilder.newBuilder()
            .setConf(conf)
            .setDbFile(pipelineDBPath)
            .setCacheSize(cacheSize * OzoneConsts.MB)
            .build();

    initializePipelineState();
  }

  private void initializePipelineState() throws IOException {
    if (pipelineStore.isEmpty()) {
      LOG.info("No pipeline exists in current db");
      return;
    }
    List<Map.Entry<byte[], byte[]>> pipelines =
        pipelineStore.getSequentialRangeKVs(null, Integer.MAX_VALUE, null);

    for (Map.Entry<byte[], byte[]> entry : pipelines) {
      Pipeline pipeline = Pipeline
          .fromProtobuf(HddsProtos.Pipeline.PARSER.parseFrom(entry.getValue()));
      Preconditions.checkNotNull(pipeline);
      stateManager.addPipeline(pipeline);
    }
  }

  @Override
  public synchronized Pipeline createPipeline(
      ReplicationType type, ReplicationFactor factor) throws IOException {
    lock.writeLock().lock();
    try {
      Pipeline pipeline =  pipelineFactory.create(type, factor);
      stateManager.addPipeline(pipeline);
      try {
        pipelineStore.put(pipeline.getID().getProtobuf().toByteArray(),
            pipeline.getProtobufMessage().toByteArray());
      } catch (IOException ioe) {
        // if db operation fails we need to revert the pipeline creation in
        // state manager.
        stateManager.removePipeline(pipeline.getID());
        throw ioe;
      }
      return pipeline;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Pipeline createPipeline(ReplicationType type,
                                 List<DatanodeDetails> nodes)
      throws IOException {
    // This will mostly be used to create dummy pipeline for SimplePipelines.
    lock.writeLock().lock();
    try {
      return pipelineFactory.create(type, nodes);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Pipeline getPipeline(PipelineID pipelineID) throws IOException {
    lock.readLock().lock();
    try {
      return stateManager.getPipeline(pipelineID);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void addContainerToPipeline(PipelineID pipelineID,
      ContainerID containerID) throws IOException {
    lock.writeLock().lock();
    try {
      stateManager.addContainerToPipeline(pipelineID, containerID);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void removeContainerFromPipeline(PipelineID pipelineID,
      ContainerID containerID) throws IOException {
    lock.writeLock().lock();
    try {
      stateManager.removeContainerFromPipeline(pipelineID, containerID);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Set<ContainerID> getContainersInPipeline(PipelineID pipelineID)
      throws IOException {
    lock.readLock().lock();
    try {
      return stateManager.getContainers(pipelineID);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void finalizePipeline(PipelineID pipelineId) throws IOException {
    lock.writeLock().lock();
    try {
      //TODO: close all containers in this pipeline
      Pipeline pipeline =
          stateManager.updatePipelineState(pipelineId, LifeCycleEvent.FINALIZE);
      pipelineStore.put(pipeline.getID().getProtobuf().toByteArray(),
          pipeline.getProtobufMessage().toByteArray());
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void closePipeline(PipelineID pipelineId) throws IOException {
    lock.writeLock().lock();
    try {
      Pipeline pipeline =
          stateManager.updatePipelineState(pipelineId, LifeCycleEvent.CLOSE);
      pipelineStore.put(pipeline.getID().getProtobuf().toByteArray(),
          pipeline.getProtobufMessage().toByteArray());
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void removePipeline(PipelineID pipelineID) throws IOException {
    lock.writeLock().lock();
    try {
      stateManager.removePipeline(pipelineID);
      pipelineStore.delete(pipelineID.getProtobuf().toByteArray());
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void close() throws IOException {
    lock.writeLock().lock();
    try {
      stateManager.close();
    } finally {
      lock.writeLock().unlock();
    }
  }
}
