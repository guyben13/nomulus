// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.rdap;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.googlecode.objectify.Key;
import google.registry.keyring.api.KeyModule;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpCookie;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Loads the current list of RDAP Base URLs from the ICANN servers.
 *
 * <p>This will update ALL the REAL registrars. If a REAL registrar doesn't have an RDAP entry in
 * MoSAPI, we'll delete any BaseUrls it has.
 *
 * <p>The ICANN endpoint is described in the MoSAPI specifications, part 11:
 * https://www.icann.org/en/system/files/files/mosapi-specification-30may19-en.pdf
 *
 * <p>It is a "login/query/logout" system where you login using the ICANN Reporting credentials, get
 * a cookie you then send to get the list and finally logout.
 *
 * <p>The username is [TLD]_ry. It could be any "real" TLD.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/updateRegistrarRdapBaseUrls",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class UpdateRegistrarRdapBaseUrlsAction implements Runnable {

  private static final String MOSAPI_BASE_URL = "https://mosapi.icann.org/mosapi/v1/%s/";
  private static final String LOGIN_URL = MOSAPI_BASE_URL + "login";
  private static final String LIST_URL = MOSAPI_BASE_URL + "registrarRdapBaseUrl/list";
  private static final String LOGOUT_URL = MOSAPI_BASE_URL + "logout";
  private static final String COOKIE_ID = "id";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject HttpTransport httpTransport;
  @Inject @KeyModule.Key("icannReportingPassword") String password;

  /**
   * The TLD for which we make the request.
   *
   * <p>The actual value doesn't matter, as long as it's a TLD that has access to the ICANN
   * Reporter. It's just used to login.
   */
  @Inject @Parameter("tld") String tld;

  @Inject
  UpdateRegistrarRdapBaseUrlsAction() {}

  private String loginAndGetId(HttpRequestFactory requestFactory) {
    try {
      logger.atInfo().log("Logging in to MoSAPI");
      HttpRequest request =
          requestFactory.buildGetRequest(new GenericUrl(String.format(LOGIN_URL, tld)));
      request.getHeaders().setBasicAuthentication(String.format("%s_ry", tld), password);
      HttpResponse response = request.execute();

      Optional<HttpCookie> idCookie =
          HttpCookie.parse(response.getHeaders().getFirstHeaderStringValue("Set-Cookie")).stream()
              .filter(cookie -> cookie.getName().equals(COOKIE_ID))
              .findAny();
      checkState(
          idCookie.isPresent(),
          "Didn't get the ID cookie from the login response. Code: %s, headers: %s",
          response.getStatusCode(),
          response.getHeaders());
      return idCookie.get().getValue();
    } catch (IOException e) {
      throw new UncheckedIOException("Error logging in to MoSAPI server: " + e.getMessage(), e);
    }
  }

  private void logout(HttpRequestFactory requestFactory, String id) {
    try {
      HttpRequest request =
          requestFactory.buildGetRequest(new GenericUrl(String.format(LOGOUT_URL, tld)));
      request.getHeaders().setCookie(String.format("%s=%s", COOKIE_ID, id));
      request.execute();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to log out of MoSAPI server. Continuing.");
      // No need for the whole Action to fail if only the logout failed. We can just continue with
      // the data we got.
    }
  }

  private ImmutableSetMultimap<String, String> getRdapBaseUrlsPerIanaId() {
    HttpRequestFactory requestFactory = httpTransport.createRequestFactory();
    String id = loginAndGetId(requestFactory);
    String content;
    try {
      HttpRequest request =
          requestFactory.buildGetRequest(new GenericUrl(String.format(LIST_URL, tld)));
      request.getHeaders().setCookie(String.format("%s=%s", COOKIE_ID, id));
      HttpResponse response = request.execute();

      try (InputStream input = response.getContent()) {
        content = new String(ByteStreams.toByteArray(input), UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Error reading RDAP list from MoSAPI server: " + e.getMessage(), e);
    } finally {
      logout(requestFactory, id);
    }

    logger.atInfo().log("list reply: '%s'", content);
    JsonObject listReply = new Gson().fromJson(content, JsonObject.class);
    JsonArray services = listReply.getAsJsonArray("services");
    // The format of the response "services" is an array of "ianaIDs to baseUrls", where "ianaIDs
    // to baseUrls" is an array of size 2 where the first item is all the "iana IDs" and the
    // second item all the "baseUrls".
    ImmutableSetMultimap.Builder<String, String> builder = new ImmutableSetMultimap.Builder<>();
    for (JsonElement service : services) {
      for (JsonElement ianaId : service.getAsJsonArray().get(0).getAsJsonArray()) {
        for (JsonElement baseUrl : service.getAsJsonArray().get(1).getAsJsonArray()) {
          builder.put(ianaId.getAsString(), baseUrl.getAsString());
        }
      }
    }

    return builder.build();
  }

  @Override
  public void run() {
    ImmutableSetMultimap<String, String> ianaToBaseUrls = getRdapBaseUrlsPerIanaId();

    for (Key<Registrar> registrarKey : ofy().load().type(Registrar.class).keys()) {
      ofy()
          .transact(
              () -> {
                Registrar registrar = ofy().load().key(registrarKey).now();
                // Has the registrar been deleted since we loaded the key? (unlikly, especially
                // given we don't delete registrars...)
                if (registrar == null) {
                  return;
                }
                // Only update REAL registrars
                if (registrar.getType() != Registrar.Type.REAL) {
                  return;
                }
                String ianaId = String.valueOf(registrar.getIanaIdentifier());
                ImmutableSet<String> baseUrls = ianaToBaseUrls.get(ianaId);
                // If this registrar already has these values, skip it
                if (registrar.getRdapBaseUrls().equals(baseUrls)) {
                  logger.atInfo().log(
                      "No change in RdapBaseUrls for registrar %s (ianaId %s)",
                      registrar.getClientId(), ianaId);
                  return;
                }
                logger.atInfo().log(
                    "Updating RdapBaseUrls for registrar %s (ianaId %s) from %s to %s",
                    registrar.getClientId(), ianaId, registrar.getRdapBaseUrls(), baseUrls);
                ofy()
                    .save()
                    .entity(registrar.asBuilder().setRdapBaseUrls(baseUrls).build());
              });
    }
  }
}
