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

package org.apache.hadoop.fs.azure;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.junit.Test;

import static org.apache.hadoop.fs.azure.CachingAuthorizer.KEY_AUTH_SERVICE_CACHING_ENABLE;

/**
 * Test class to hold all WASB authorization caching related tests.
 */
public class TestNativeAzureFSAuthorizationCaching
    extends TestNativeAzureFileSystemAuthorization {

  private static final int DUMMY_TTL_VALUE = 5000;

  @Override
  public Configuration getConfiguration() {
    Configuration conf = super.getConfiguration();
    conf.set(KEY_AUTH_SERVICE_CACHING_ENABLE, "true");
    return conf;
  }

  @Override
  protected AzureBlobStorageTestAccount createTestAccount() throws Exception {
    Configuration conf = getConfiguration();
    return AzureBlobStorageTestAccount.create(conf);
  }

  /**
   * Test to verify cache behavior -- assert that PUT overwrites value if present
   */
  @Test
  public void testCachePut() throws Throwable {
    CachingAuthorizer<String, Integer> cache = new CachingAuthorizer<>(DUMMY_TTL_VALUE, "TEST");
    cache.init(getConfiguration());
    cache.put("TEST", 1);
    cache.put("TEST", 3);
    int result = cache.get("TEST");
    ContractTestUtils.assertTrue("Cache returned unexpected result", result == 3);
  }
}
