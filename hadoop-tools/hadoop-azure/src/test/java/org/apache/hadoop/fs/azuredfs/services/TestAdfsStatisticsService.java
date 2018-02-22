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

import org.junit.Test;
import org.mockito.Mockito;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.azuredfs.AzureDistributedFileSystem;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsStatisticsService;

import static org.junit.Assert.assertEquals;

public class TestAdfsStatisticsService {
  public TestAdfsStatisticsService() throws Exception {
    super();
  }

  @Test
  public void testEnsureSubscription() throws Exception {
    final AdfsStatisticsServiceImpl adfsStatisticsService = new AdfsStatisticsServiceImpl();
    final AzureDistributedFileSystem azureDistributedFileSystem = new AzureDistributedFileSystem();

    adfsStatisticsService.subscribe(azureDistributedFileSystem, new FileSystem.Statistics("test"));
    assertEquals(1, adfsStatisticsService.getSubscribers().size());
    assertEquals(azureDistributedFileSystem, adfsStatisticsService.getSubscribers().keys().nextElement());

    adfsStatisticsService.unsubscribe(azureDistributedFileSystem);
    assertEquals(0, adfsStatisticsService.getSubscribers().size());
  }

  @Test
  public void testEnsureIncrementReadOps() throws Exception {
    final AdfsStatisticsService adfsStatisticsService = new AdfsStatisticsServiceImpl();
    final AzureDistributedFileSystem azureDistributedFileSystem = new AzureDistributedFileSystem();
    final FileSystem.Statistics statistics = new FileSystem.Statistics("test");

    adfsStatisticsService.subscribe(azureDistributedFileSystem, statistics);

    adfsStatisticsService.incrementReadOps(azureDistributedFileSystem, 100);
    assertEquals(100, statistics.getReadOps());
  }

  @Test
  public void testEnsureIncrementBytesRead() throws Exception {
    final AdfsStatisticsService adfsStatisticsService = new AdfsStatisticsServiceImpl();
    final AzureDistributedFileSystem azureDistributedFileSystem = new AzureDistributedFileSystem();
    final FileSystem.Statistics statistics = new FileSystem.Statistics("test");

    adfsStatisticsService.subscribe(azureDistributedFileSystem, statistics);

    adfsStatisticsService.incrementBytesRead(azureDistributedFileSystem, 200);
    assertEquals(200, statistics.getBytesRead());
  }

  @Test
  public void testEnsureIncrementWriteOps() throws Exception {
    final AdfsStatisticsService adfsStatisticsService = new AdfsStatisticsServiceImpl();
    final AzureDistributedFileSystem azureDistributedFileSystem = new AzureDistributedFileSystem();
    final FileSystem.Statistics statistics = new FileSystem.Statistics("test");

    adfsStatisticsService.subscribe(azureDistributedFileSystem, statistics);

    adfsStatisticsService.incrementWriteOps(azureDistributedFileSystem, 300);
    assertEquals(300, statistics.getWriteOps());
  }

  @Test
  public void testEnsureIncrementBytesWritten() throws Exception {
    final AdfsStatisticsService adfsStatisticsService = new AdfsStatisticsServiceImpl();
    final AzureDistributedFileSystem azureDistributedFileSystem = new AzureDistributedFileSystem();
    final FileSystem.Statistics statistics = new FileSystem.Statistics("test");

    adfsStatisticsService.subscribe(azureDistributedFileSystem, statistics);

    adfsStatisticsService.incrementBytesWritten(azureDistributedFileSystem, 400);
    assertEquals(400, statistics.getBytesWritten());
  }
}
