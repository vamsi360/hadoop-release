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

import java.io.IOException;

import com.google.common.base.Preconditions;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsHttpClientSession;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsNetworkThroughputMetrics;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsNetworkTrafficAnalysisService;
import org.apache.hadoop.fs.azuredfs.utils.NetworkUtils;

@InterfaceAudience.Private
@InterfaceStability.Evolving
final class NetworkThroughputMonitorImpl implements Interceptor {
  private final AdfsNetworkTrafficAnalysisService adfsNetworkTrafficAnalysisService;
  private final AdfsHttpClientSession adfsHttpClientSession;

  NetworkThroughputMonitorImpl(
      final AdfsHttpClientSession adfsHttpClientSession,
      final AdfsNetworkTrafficAnalysisService adfsNetworkTrafficAnalysisService) {
    Preconditions.checkNotNull(adfsHttpClientSession, "adfsHttpClientSession");
    Preconditions.checkNotNull(adfsNetworkTrafficAnalysisService, "adfsNetworkTrafficeAnalysisService");

    this.adfsNetworkTrafficAnalysisService = adfsNetworkTrafficAnalysisService;
    this.adfsHttpClientSession = adfsHttpClientSession;
  }

  @Override
  public Response intercept(final Chain chain)
      throws IOException {

    final Request request = chain.request();
    final Response response = chain.proceed(request);

    final boolean isFailed = !response.isSuccessful();
    final long totalBytes = (request.body() == null ? 0 : request.body().contentLength()) + (response.body() == null ? 0 : response.body().contentLength());

    final AdfsNetworkThroughputMetrics networkThroughputMetrics;

    if (NetworkUtils.isWriteRequest(request)) {
      networkThroughputMetrics = this.adfsNetworkTrafficAnalysisService
          .getAdfsNetworkThroughputMetrics(adfsHttpClientSession)
          .getWriteMetrics();
    }
    else {
      networkThroughputMetrics = this.adfsNetworkTrafficAnalysisService
          .getAdfsNetworkThroughputMetrics(adfsHttpClientSession)
          .getReadMetrics();
    }

    networkThroughputMetrics.addBytesTransferred(totalBytes, isFailed);
    return response;
  }
}