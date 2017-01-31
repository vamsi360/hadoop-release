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

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.security.PrivilegedExceptionAction;
import java.util.Iterator;

import org.apache.commons.lang.Validate;
import org.apache.hadoop.conf.Configuration;

import org.apache.hadoop.fs.azure.security.Constants;
import org.apache.hadoop.fs.azure.security.WasbDelegationTokenIdentifier;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.Authenticator;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.security.token.delegation.web.KerberosDelegationTokenAuthenticator;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;

import com.fasterxml.jackson.core.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.fs.azure.WasbRemoteCallHelper.REMOTE_CALL_SUCCESS_CODE;

/**
 * Class implementing WasbAuthorizerInterface using a remote
 * service that implements the authorization operation. This
 * class expects the url of the remote service to be passed
 * via config.
 */
public class RemoteWasbAuthorizerImpl implements WasbAuthorizerInterface {

  public static final Logger LOG =
      LoggerFactory.getLogger(RemoteWasbAuthorizerImpl.class);
  private String remoteAuthorizerServiceUrl = "";

  /**
   * Configuration parameter name expected in the Configuration object to
   * provide the url of the remote service. {@value}
   */
  public static final String KEY_REMOTE_AUTH_SERVICE_URL =
      "fs.azure.authorization.remote.service.url";

  /**
   * Authorization operation OP name in the remote service {@value}
   */
  private static final String CHECK_AUTHORIZATION_OP =
      "CHECK_AUTHORIZATION";

  /**
   * Query parameter specifying the access operation type. {@value}
   */
  private static final String ACCESS_OPERATION_QUERY_PARAM_NAME =
      "operation_type";

  /**
   * Query parameter specifying the wasb absolute path. {@value}
   */
  private static final String WASB_ABSOLUTE_PATH_QUERY_PARAM_NAME =
      "wasb_absolute_path";

  /**
   * Query parameter name for user info {@value}
   */
  private static final String DELEGATION_TOKEN_QUERY_PARAM_NAME =
      "delegation";

  private WasbRemoteCallHelper remoteCallHelper = null;
  private String delegationToken;
  private boolean isSecurityEnabled;
  private boolean isKerberosSupportEnabled;

  @Override
  public void init(Configuration conf)
      throws WasbAuthorizationException, IOException {
    LOG.debug("Initializing RemoteWasbAuthorizerImpl instance");
    Iterator<Token<? extends TokenIdentifier>> tokenIterator = null;
    try {
      tokenIterator = UserGroupInformation.getCurrentUser().getCredentials()
          .getAllTokens().iterator();
      while (tokenIterator.hasNext()) {
        Token<? extends TokenIdentifier> iteratedToken = tokenIterator.next();
        if (iteratedToken.getKind().equals(
            WasbDelegationTokenIdentifier.TOKEN_KIND)) {
          delegationToken = iteratedToken.encodeToUrlString();
        }
      }
    } catch (IOException e) {
      LOG.error("Error in fetching the WASB delegation token");
    }

    remoteAuthorizerServiceUrl = conf.get(KEY_REMOTE_AUTH_SERVICE_URL, String
        .format("http://%s:%s",
            InetAddress.getLocalHost().getCanonicalHostName(),
            Constants.DEFAULT_CRED_SERVICE_PORT));

    if (remoteAuthorizerServiceUrl == null
        || remoteAuthorizerServiceUrl.isEmpty()) {
      throw new WasbAuthorizationException(
          "fs.azure.authorization.remote.service.url config not set"
              + " in configuration.");
    }

    this.remoteCallHelper = new WasbRemoteCallHelper();
    this.isSecurityEnabled = UserGroupInformation.isSecurityEnabled();
    this.isKerberosSupportEnabled = conf.getBoolean(
        Constants.AZURE_KERBEROS_SUPPORT_PROPERTY_NAME, false);
  }

  @Override
  public boolean authorize(String wasbAbsolutePath, String accessType)
      throws WasbAuthorizationException, IOException {
    try {
      final URIBuilder uriBuilder = new URIBuilder(remoteAuthorizerServiceUrl);
      uriBuilder.setPath("/" + CHECK_AUTHORIZATION_OP);
      uriBuilder.addParameter(WASB_ABSOLUTE_PATH_QUERY_PARAM_NAME,
          wasbAbsolutePath);
      uriBuilder.addParameter(ACCESS_OPERATION_QUERY_PARAM_NAME,
          accessType);
      if (isSecurityEnabled && (delegationToken != null && !delegationToken
          .isEmpty())) {
        uriBuilder
            .addParameter(DELEGATION_TOKEN_QUERY_PARAM_NAME, delegationToken);
      }
      String responseBody = null;
      UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
      UserGroupInformation connectUgi = ugi.getRealUser();
      if (connectUgi == null) {
        connectUgi = ugi;
      } else{
        uriBuilder.addParameter(Constants.DOAS_PARAM, ugi.getShortUserName());
      }
      connectUgi.checkTGTAndReloginFromKeytab();
      try {
        responseBody = connectUgi.doAs(new PrivilegedExceptionAction<String>(){
          @Override
          public String run() throws Exception {
            AuthenticatedURL.Token token = null;
            HttpGet httpGet = new HttpGet(uriBuilder.build());
            if (isKerberosSupportEnabled && UserGroupInformation.isSecurityEnabled() && (
                delegationToken == null || delegationToken.isEmpty())) {
              token = new AuthenticatedURL.Token();
              final Authenticator kerberosAuthenticator = new KerberosDelegationTokenAuthenticator();
              kerberosAuthenticator
                  .authenticate(uriBuilder.build().toURL(), token);
              Validate.isTrue(token.isSet(),
                  "Authenticated Token is NOT present. The request cannot proceed.");
              if(token != null){
                httpGet.setHeader("Cookie", AuthenticatedURL.AUTH_COOKIE + "=" + token);
              }
            }
            return remoteCallHelper.makeRemoteGetRequest(httpGet);
          }});
      } catch (InterruptedException e) {
        LOG.error("Error in check authorization", e);
      }

      ObjectMapper objectMapper = new ObjectMapper();
      RemoteAuthorizerResponse authorizerResponse =
          objectMapper.readValue(responseBody, RemoteAuthorizerResponse.class);

      if (authorizerResponse == null) {
        throw new WasbAuthorizationException(
            "RemoteAuthorizerResponse object null from remote call");
      } else if (authorizerResponse.getResponseCode()
          == REMOTE_CALL_SUCCESS_CODE) {
        return authorizerResponse.getAuthorizationResult();
      } else {
        throw new WasbAuthorizationException("Remote authorization"
            + " serivce encountered an error "
            + authorizerResponse.getResponseMessage());
      }
    } catch (URISyntaxException | WasbRemoteCallException
        | JsonParseException | JsonMappingException ex) {
      throw new WasbAuthorizationException(ex);
    }
  }
}

/**
 * POJO representing the response expected from a remote
 * authorization service.
 * The remote service is expected to return the authorization
 * response in the following JSON format
 * {
 *    "responseCode" : 0 or non-zero <int>,
 *    "responseMessage" : relavant message of failure <String>
 *    "authorizationResult" : authorization result <boolean>
 *                            true - if auhorization allowed
 *                            false - otherwise.
 *
 * }
 */
class RemoteAuthorizerResponse {

  private int responseCode;
  private boolean authorizationResult;
  private String responseMessage;

  public RemoteAuthorizerResponse(){
  }

  public RemoteAuthorizerResponse(int responseCode,
      boolean authorizationResult, String message) {
    this.responseCode = responseCode;
    this.authorizationResult = authorizationResult;
    this.responseMessage = message;
  }

  public int getResponseCode() {
    return responseCode;
  }

  public void setResponseCode(int responseCode) {
    this.responseCode = responseCode;
  }

  public boolean getAuthorizationResult() {
    return authorizationResult;
  }

  public void setAuthorizationResult(boolean authorizationResult) {
    this.authorizationResult = authorizationResult;
  }

  public String getResponseMessage() {
    return responseMessage;
  }

  public void setResponseMessage(String message) {
    this.responseMessage = message;
  }
}