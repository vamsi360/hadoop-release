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

package org.apache.hadoop.fs.azuredfs.services;

import java.util.concurrent.ConcurrentHashMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.azuredfs.AzureDistributedFileSystem;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsStatisticsService;

@Singleton
@InterfaceAudience.Private
@InterfaceStability.Evolving
class AdfsStatisticsServiceImpl implements AdfsStatisticsService {
  private final ConcurrentHashMap<AzureDistributedFileSystem, FileSystem.Statistics> fileSystemStatisticsCache;

  @Inject
  AdfsStatisticsServiceImpl() {
    this.fileSystemStatisticsCache = new ConcurrentHashMap<>();
  }

  @Override
  public synchronized void subscribe(final AzureDistributedFileSystem azureDistributedFileSystem, final FileSystem.Statistics statistics) {
    this.fileSystemStatisticsCache.putIfAbsent(azureDistributedFileSystem, statistics);
  }

  @Override
  public synchronized void unsubscribe(final AzureDistributedFileSystem azureDistributedFileSystem) {
    this.fileSystemStatisticsCache.remove(azureDistributedFileSystem);
  }

  @Override
  public void incrementBytesRead(final AzureDistributedFileSystem azureDistributedFileSystem, final long newBytes) {
    FileSystem.Statistics statistics = this.fileSystemStatisticsCache.get(azureDistributedFileSystem);
    if (statistics == null) {
      return;
    }

    statistics.incrementBytesRead(newBytes);
  }

  @Override
  public void incrementReadOps(final AzureDistributedFileSystem azureDistributedFileSystem, final int count) {
    FileSystem.Statistics statistics = this.fileSystemStatisticsCache.get(azureDistributedFileSystem);
    if (statistics == null) {
      return;
    }

    statistics.incrementReadOps(count);
  }

  @Override
  public void incrementBytesWritten(final AzureDistributedFileSystem azureDistributedFileSystem, final long newBytes) {
    FileSystem.Statistics statistics = this.fileSystemStatisticsCache.get(azureDistributedFileSystem);
    if (statistics == null) {
      return;
    }

    statistics.incrementBytesWritten(newBytes);
  }

  @Override
  public void incrementWriteOps(final AzureDistributedFileSystem azureDistributedFileSystem, final int count) {
    FileSystem.Statistics statistics = this.fileSystemStatisticsCache.get(azureDistributedFileSystem);
    if (statistics == null) {
      return;
    }

    statistics.incrementWriteOps(count);
  }

  @VisibleForTesting
  ConcurrentHashMap<AzureDistributedFileSystem, FileSystem.Statistics> getSubscribers() {
    return this.fileSystemStatisticsCache;
  }
}