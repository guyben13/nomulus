// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.google.gson.JsonArray;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.Address;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.rdap.RdapDataStructures.Event;
import google.registry.rdap.RdapDataStructures.EventAction;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.Vcard;
import google.registry.rdap.RdapObjectClasses.VcardArray;
import google.registry.request.FullServletPath;
import google.registry.util.Clock;
import java.net.URI;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Helper class to create RDAP JSON objects for various registry entities and objects.
 *
 * <p>The JSON format specifies that entities should be supplied with links indicating how to fetch
 * them via RDAP, which requires the URL to the RDAP server. The linkBase parameter, passed to many
 * of the methods, is used as the first part of the link URL. For instance, if linkBase is
 * "http://rdap.org/dir/", the link URLs will look like "http://rdap.org/dir/domain/XXXX", etc.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7483">
 *        RFC 7483: JSON Responses for the Registration Data Access Protocol (RDAP)</a>
 */
public class RdapJsonFormatter {

  private DateTime requestTime = null;

  @Inject @Config("rdapTos") ImmutableList<String> rdapTos;
  @Inject @Config("rdapTosStaticUrl") @Nullable String rdapTosStaticUrl;
  @Inject @FullServletPath String fullServletPath;
  @Inject RdapAuthorization rdapAuthorization;
  @Inject Clock clock;
  @Inject RdapJsonFormatter() {}

  /**
   * What type of data to generate.
   *
   * <p>Summary data includes only information about the object itself, while full data includes
   * associated items (e.g. for domains, full data includes the hosts, contacts and history entries
   * connected with the domain).
   *
   * <p>Summary data is appropriate for search queries which return many results, to avoid load on
   * the system. According to the ICANN operational profile, a remark must be attached to the
   * returned object indicating that it includes only summary data.
   */
  public enum OutputDataType {
    /**
     * The full information about an RDAP object.
     *
     * <p>Reserved to cases when this object is the only result of a query - either queried
     * directly, or the sole result of a search query.
     */
    FULL,
    /**
     * The minimal information about an RDAP object that is allowed as a reply.
     *
     * <p>Reserved to cases when this object is one of many results of a search query.
     *
     * <p>We want to minimize the size of the reply, and also minimize the Datastore queries needed
     * to generate these replies since we might have a lot of these objects to return.
     *
     * <p>Each object with a SUMMARY type will have a remark with a direct link to itself, which
     * will return the FULL result.
     */
    SUMMARY,
    /**
     * The object isn't the subject of the query, but is rather a sub-object of the actual reply.
     *
     * <p>These objects have less required fields in the RDAP spec, and hence can be even smaller
     * than the SUMMARY objects.
     *
     * <p>Like SUMMARY objects, these objects will also have a remark with a direct link to itself,
     * which will return the FULL result.
     */
    INTERNAL
  }

  /** Map of EPP status values to the RDAP equivalents. */
  private static final ImmutableMap<StatusValue, RdapStatus> STATUS_TO_RDAP_STATUS_MAP =
      new ImmutableMap.Builder<StatusValue, RdapStatus>()
          // RdapStatus.ADD_PERIOD not defined in our system
          // RdapStatus.AUTO_RENEW_PERIOD not defined in our system
          .put(StatusValue.CLIENT_DELETE_PROHIBITED, RdapStatus.CLIENT_DELETE_PROHIBITED)
          .put(StatusValue.CLIENT_HOLD, RdapStatus.CLIENT_HOLD)
          .put(StatusValue.CLIENT_RENEW_PROHIBITED, RdapStatus.CLIENT_RENEW_PROHIBITED)
          .put(StatusValue.CLIENT_TRANSFER_PROHIBITED, RdapStatus.CLIENT_TRANSFER_PROHIBITED)
          .put(StatusValue.CLIENT_UPDATE_PROHIBITED, RdapStatus.CLIENT_UPDATE_PROHIBITED)
          .put(StatusValue.INACTIVE, RdapStatus.INACTIVE)
          .put(StatusValue.LINKED, RdapStatus.ASSOCIATED)
          .put(StatusValue.OK, RdapStatus.ACTIVE)
          .put(StatusValue.PENDING_CREATE, RdapStatus.PENDING_CREATE)
          .put(StatusValue.PENDING_DELETE, RdapStatus.PENDING_DELETE)
          // RdapStatus.PENDING_RENEW not defined in our system
          // RdapStatus.PENDING_RESTORE not defined in our system
          .put(StatusValue.PENDING_TRANSFER, RdapStatus.PENDING_TRANSFER)
          .put(StatusValue.PENDING_UPDATE, RdapStatus.PENDING_UPDATE)
          // RdapStatus.REDEMPTION_PERIOD not defined in our system
          // RdapStatus.RENEW_PERIOD not defined in our system
          .put(StatusValue.SERVER_DELETE_PROHIBITED, RdapStatus.SERVER_DELETE_PROHIBITED)
          .put(StatusValue.SERVER_HOLD, RdapStatus.SERVER_HOLD)
          .put(StatusValue.SERVER_RENEW_PROHIBITED, RdapStatus.SERVER_RENEW_PROHIBITED)
          .put(StatusValue.SERVER_TRANSFER_PROHIBITED, RdapStatus.SERVER_TRANSFER_PROHIBITED)
          .put(StatusValue.SERVER_UPDATE_PROHIBITED, RdapStatus.SERVER_UPDATE_PROHIBITED)
          // RdapStatus.TRANSFER_PERIOD not defined in our system
          .build();

  /**
   * Map of EPP event values to the RDAP equivalents.
   *
   * <p>Only has entries for optional events, either stated as optional in the RDAP Response Profile
   * 15feb19, or not mentioned at all but thought to be useful anyway.
   *
   * <p>Any required event should be added elsewhere, preferably without using HistoryEntries (so
   * that we don't need to load HistoryEntries for "summary" responses).
   */
  private static final ImmutableMap<HistoryEntry.Type, EventAction>
      HISTORY_ENTRY_TYPE_TO_RDAP_EVENT_ACTION_MAP =
          new ImmutableMap.Builder<HistoryEntry.Type, EventAction>()
              .put(HistoryEntry.Type.CONTACT_CREATE, EventAction.REGISTRATION)
              .put(HistoryEntry.Type.CONTACT_DELETE, EventAction.DELETION)
              .put(HistoryEntry.Type.CONTACT_TRANSFER_APPROVE, EventAction.TRANSFER)

              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_AUTORENEW, EventAction.REREGISTRATION)
              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_DELETE, EventAction.DELETION)
              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_RENEW, EventAction.REREGISTRATION)
              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_RESTORE, EventAction.REINSTANTIATION)
              /** Section 2.3.2.3, optional. */
              .put(HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE, EventAction.TRANSFER)
              .put(HistoryEntry.Type.HOST_CREATE, EventAction.REGISTRATION)
              .put(HistoryEntry.Type.HOST_DELETE, EventAction.DELETION)
              .build();

  static final ImmutableSet<RdapStatus> STATUS_LIST_ACTIVE = ImmutableSet.of(RdapStatus.ACTIVE);
  static final ImmutableSet<RdapStatus> STATUS_LIST_INACTIVE = ImmutableSet.of(RdapStatus.INACTIVE);
  private static final ImmutableMap<String, ImmutableList<String>> PHONE_TYPE_VOICE =
      ImmutableMap.of("type", ImmutableList.of("voice"));
  private static final ImmutableMap<String, ImmutableList<String>> PHONE_TYPE_FAX =
      ImmutableMap.of("type", ImmutableList.of("fax"));

  /** Creates the TOS notice that is added to every reply. */
  Notice createTosNotice() {
    String linkValue = makeRdapServletRelativeUrl("help", RdapHelpAction.TOS_PATH);
    Link.Builder linkBuilder = Link.builder()
        .setValue(linkValue);
    if (rdapTosStaticUrl == null) {
      linkBuilder.setRel("self").setHref(linkValue).setType("application/rdap+json");
    } else {
      URI htmlBaseURI = URI.create(fullServletPath);
      URI htmlUri = htmlBaseURI.resolve(rdapTosStaticUrl);
      linkBuilder.setRel("alternate").setHref(htmlUri.toString()).setType("text/html");
    }
    return Notice.builder()
        .setTitle("RDAP Terms of Service")
        .setDescription(rdapTos)
        .addLink(linkBuilder.build())
        .build();
  }

  /**
   * Creates a JSON object for a {@link DomainBase} result.
   *
   * @param domainBase the domain resource object from which the JSON object should be created
   */
  RdapDomain createRdapDomainFull(DomainBase domainBase) {
    return RdapDomain.createFull(domainBase, this);
  }

  /**
   * Creates a JSON object for a {@link DomainBase} search result.
   *
   * <p>NOTE that domain searches aren't in the spec yet - they're in the RFC7482 that describes the
   * query format, but they aren't in the RDAP Technical Implementation Guide 15feb19, meaning we
   * don't have to implement them yet and the RDAP Response Profile doesn't apply to them.
   *
   * <p>We're implementing domain searches anyway, BUT we won't have the response for searches
   * conform to the RDAP Response Profile.
   *
   * @param domainBase the domain resource object from which the JSON object should be created
   */
  RdapDomain createRdapDomainSummary(DomainBase domainBase) {
    return RdapDomain.createSummary(domainBase, this);
  }

  /**
   * Creates a JSON object for a {@link HostResource}.
   *
   * @param hostResource the host resource object from which the JSON object should be created
   * @param outputDataType whether to generate full or summary data
   */
  RdapNameserver createRdapNameserver(HostResource hostResource, OutputDataType outputDataType) {
    return new RdapNameserver(hostResource, outputDataType, this);
  }

  /**
   * Creates a JSON object for a {@link ContactResource} and associated contact type.
   *
   * @param contactResource the contact resource object from which the JSON object should be created
   * @param roles the roles of this contact
   * @param outputDataType whether to generate full or summary data
   */
  RdapContactEntity createRdapContactEntity(
      ContactResource contactResource,
      Iterable<RdapEntity.Role> roles,
      OutputDataType outputDataType) {

    // RDAP Response Profile 2.7.1, 2.7.3 - we MUST have the contacts. 2.7.4 discusses censoring of
    // fields we don't want to show (as opposed to not having contacts at all) because of GDPR etc.
    //
    // 2.8 allows for unredacted output for authorized people.
    boolean isAuthorized =
        rdapAuthorization.isAuthorizedForClientId(contactResource.getCurrentSponsorClientId());

    if (isAuthorized) {
      return RdapContactEntity.createUnredacted(contactResource, roles, outputDataType, this);
    }
    return RdapContactEntity.createRedacted(roles, outputDataType, this);
  }

  /**
   * Creates a JSON object for a {@link Registrar}.
   *
   * <p>This object can be INTERNAL to the Domain and Nameserver responses, with requirements
   * discussed in the RDAP Response Profile 15feb19 sections 2.4 (internal to Domain) and 4.3
   * (internal to Namesever)
   *
   * @param registrar the registrar object from which the RDAP response
   * @param outputDataType whether to generate FULL, SUMMARY, or INTERNAL data.
   */
  RdapRegistrarEntity createRdapRegistrarEntity(
      Registrar registrar, OutputDataType outputDataType) {
    return new RdapRegistrarEntity(registrar, outputDataType, this);
  }

  /** Converts a domain registry contact type into a role as defined by RFC 7483. */
  static RdapEntity.Role convertContactTypeToRdapRole(DesignatedContact.Type contactType) {
    switch (contactType) {
      case REGISTRANT:
        return RdapEntity.Role.REGISTRANT;
      case TECH:
        return RdapEntity.Role.TECH;
      case BILLING:
        return RdapEntity.Role.BILLING;
      case ADMIN:
        return RdapEntity.Role.ADMIN;
    }
    throw new AssertionError();
  }

  /**
   * Creates the list of optional events to list in domain, nameserver, or contact replies.
   *
   * <p>Only has entries for optional events that won't be shown in "SUMMARY" versions of these
   * objects. These are either stated as optional in the RDAP Response Profile 15feb19, or not
   * mentioned at all but thought to be useful anyway.
   *
   * <p>Any required event should be added elsewhere, preferably without using HistoryEntries (so
   * that we don't need to load HistoryEntries for "summary" responses).
   */
  ImmutableSet<Event> makeOptionalEvents(EppResource resource) {
    HashMap<EventAction, HistoryEntry> lastEntryOfType = Maps.newHashMap();
    // Events (such as transfer, but also create) can appear multiple times. We only want the last
    // time they appeared.
    //
    // We can have multiple create historyEntries if a domain was deleted, and then someone new
    // bought it.
    //
    // From RDAP response profile
    // 2.3.2 The domain object in the RDAP response MAY contain the following events:
    // 2.3.2.3 An event of *eventAction* type *transfer*, with the last date and time that the
    // domain was transferred. The event of *eventAction* type *transfer* MUST be omitted if the
    // domain name has not been transferred since it was created.
    for (HistoryEntry historyEntry :
        ofy().load().type(HistoryEntry.class).ancestor(resource).order("modificationTime")) {
      EventAction rdapEventAction =
          HISTORY_ENTRY_TYPE_TO_RDAP_EVENT_ACTION_MAP.get(historyEntry.getType());
      // Only save the historyEntries if this is a type we care about.
      if (rdapEventAction == null) {
        continue;
      }
      lastEntryOfType.put(rdapEventAction, historyEntry);
    }
    ImmutableSet.Builder<Event> eventsBuilder = new ImmutableSet.Builder<>();
    DateTime creationTime = resource.getCreationTime();
    DateTime lastChangeTime =
        resource.getLastEppUpdateTime() == null ? creationTime : resource.getLastEppUpdateTime();
    // The order of the elements is stable - it's the order in which the enum elements are defined
    // in EventAction
    for (EventAction rdapEventAction : EventAction.values()) {
      HistoryEntry historyEntry = lastEntryOfType.get(rdapEventAction);
      // Check if there was any entry of this type
      if (historyEntry == null) {
        continue;
      }
      DateTime modificationTime = historyEntry.getModificationTime();
      // We will ignore all events that happened before the "creation time", since these events are
      // from a "previous incarnation of the domain" (for a domain that was owned by someone,
      // deleted, and then bought by someone else)
      if (modificationTime.isBefore(creationTime)) {
        continue;
      }
      eventsBuilder.add(
          Event.builder()
              .setEventAction(rdapEventAction)
              .setEventActor(historyEntry.getClientId())
              .setEventDate(modificationTime)
              .build());
      // The last change time might not be the lastEppUpdateTime, since some changes happen without
      // any EPP update (for example, by the passage of time).
      if (modificationTime.isAfter(lastChangeTime) && modificationTime.isBefore(getRequestTime())) {
        lastChangeTime = modificationTime;
      }
    }
    // RDAP Response Profile 15feb19 section 2.3.2.2:
    // The event of eventAction type last changed MUST be omitted if the domain name has not been
    // updated since it was created
    if (lastChangeTime.isAfter(creationTime)) {
      eventsBuilder.add(makeEvent(EventAction.LAST_CHANGED, null, lastChangeTime));
    }
    return eventsBuilder.build();
  }

  /**
   * Creates an RDAP event object as defined by RFC 7483.
   */
  private static Event makeEvent(
      EventAction eventAction,
      @Nullable String eventActor,
      DateTime eventDate) {
    Event.Builder builder = Event.builder()
        .setEventAction(eventAction)
        .setEventDate(eventDate);
    if (eventActor != null) {
      builder.setEventActor(eventActor);
    }
    return builder.build();
  }

  /**
   * Creates a vCard address entry: array of strings specifying the components of the address.
   *
   * <p>Rdap Response Profile 3.1.1: MUST contain the following fields: Street, City, Country Rdap
   * Response Profile 3.1.2: optional fields: State/Province, Postal Code, Fax Number
   *
   * @see <a href="https://tools.ietf.org/html/rfc7095">RFC 7095: jCard: The JSON Format for
   *     vCard</a>
   */
  static void addVCardAddressEntry(VcardArray.Builder vcardArrayBuilder, Address address) {
    if (address == null) {
      return;
    }
    JsonArray addressArray = new JsonArray();
    addressArray.add(""); // PO box
    addressArray.add(""); // extended address

    // The vCard spec allows several different ways to handle multiline street addresses. Per
    // Gustavo Lozano of ICANN, the one we should use is an embedded array of street address lines
    // if there is more than one line:
    //
    //   RFC7095 provides two examples of structured addresses, and one of the examples shows a
    //   street JSON element that contains several data elements. The example showing (see below)
    //   several data elements is the expected output when two or more <contact:street> elements
    //   exists in the contact object.
    //
    //   ["adr", {}, "text",
    //    [
    //    "", "",
    //    ["My Street", "Left Side", "Second Shack"],
    //    "Hometown", "PA", "18252", "U.S.A."
    //    ]
    //   ]
    //
    // Gustavo further clarified that the embedded array should only be used if there is more than
    // one line:
    //
    //   My reading of RFC 7095 is that if only one element is known, it must be a string. If
    //   multiple elements are known (e.g. two or three street elements were provided in the case of
    //   the EPP contact data model), an array must be used.
    //
    //   I don’t think that one street address line nested in a single-element array is valid
    //   according to RFC 7095.
    ImmutableList<String> street = address.getStreet();
    if (street.isEmpty()) {
      addressArray.add("");
    } else if (street.size() == 1) {
      addressArray.add(street.get(0));
    } else {
      JsonArray streetArray = new JsonArray();
      street.forEach(streetArray::add);
      addressArray.add(streetArray);
    }
    addressArray.add(nullToEmpty(address.getCity()));
    addressArray.add(nullToEmpty(address.getState()));
    addressArray.add(nullToEmpty(address.getZip()));
    addressArray.add(
        new Locale("en", nullToEmpty(address.getCountryCode()))
            .getDisplayCountry(new Locale("en")));
    vcardArrayBuilder.add(Vcard.create(
        "adr",
        "text",
        addressArray));
  }

  static Vcard makeVoicePhoneVcard(String phoneNumber, @Nullable String extension) {
    return makePhoneEntry(PHONE_TYPE_VOICE, makePhoneString(phoneNumber, extension));
  }

  static Vcard makeFaxPhoneVcard(String phoneNumber, @Nullable String extension) {
    return makePhoneEntry(PHONE_TYPE_FAX, makePhoneString(phoneNumber, extension));
  }

  /** Creates a vCard phone number entry. */
  private static Vcard makePhoneEntry(
      ImmutableMap<String, ImmutableList<String>> type, String phoneString) {

    return Vcard.create("tel", type, "uri", phoneString);
  }

  /** Creates a phone string in URI format, as per the vCard spec. */
  private static String makePhoneString(String phoneNumber, @Nullable String extension) {
    String phoneString = String.format("tel:%s", phoneNumber);
    if (!isNullOrEmpty(extension)) {
      phoneString = phoneString + ";ext=" + extension;
    }
    return phoneString;
  }

  /**
   * Creates a string array of status values.
   *
   * <p>The spec indicates that OK should be listed as "active". We use the "inactive" status to
   * indicate deleted objects, and as directed by the profile, the "removed" status to indicate
   * redacted objects.
   */
  static ImmutableSet<RdapStatus> makeStatusValueList(
      ImmutableSet<StatusValue> statusValues, boolean isRedacted, boolean isDeleted) {
    Stream<RdapStatus> stream =
        statusValues
            .stream()
            .map(status -> STATUS_TO_RDAP_STATUS_MAP.getOrDefault(status, RdapStatus.OBSCURED));
    if (isRedacted) {
      stream = Streams.concat(stream, Stream.of(RdapStatus.REMOVED));
    }
    if (isDeleted) {
      stream =
          Streams.concat(
              stream.filter(not(RdapStatus.ACTIVE::equals)),
              Stream.of(RdapStatus.INACTIVE));
    }
    return stream
        .sorted(Ordering.natural().onResultOf(RdapStatus::getDisplayName))
        .collect(toImmutableSet());
  }

  /**
   * Create a link relative to the RDAP server endpoint.
   */
  String makeRdapServletRelativeUrl(String part, String... moreParts) {
    return makeServerRelativeUrl(fullServletPath, part, moreParts);
  }

  /**
   * Create a link relative to some base server
   */
  static String makeServerRelativeUrl(String baseServer, String part, String... moreParts) {
    String relativePath = Paths.get(part, moreParts).toString();
    if (baseServer.endsWith("/")) {
      return baseServer + relativePath;
    }
    return baseServer + "/" + relativePath;
  }

  /**
   * Creates a self link as directed by the spec.
   *
   * @see <a href="https://tools.ietf.org/html/rfc7483">RFC 7483: JSON Responses for the
   *     Registration Data Access Protocol (RDAP)</a>
   */
  Link makeSelfLink(String type, String name) {
    String url = makeRdapServletRelativeUrl(type, name);
    return Link.builder()
        .setValue(url)
        .setRel("self")
        .setHref(url)
        .setType("application/rdap+json")
        .build();
  }

  /**
   * Returns the DateTime this request took place.
   *
   * <p>The RDAP reply is large with a lot of different object in them. We want to make sure that
   * all these objects are projected to the same "now".
   *
   * <p>This "now" will also be considered the time of the "last update of RDAP database" event that
   * RDAP sepc requires.
   *
   * <p>We would have set this during the constructor, but the clock is injected after construction.
   * So instead we set the time during the first call to this function.
   *
   * <p>We would like even more to just inject it in RequestModule and use it in many places in our
   * codebase that just need a general "now" of the request, but that's a lot of work.
   */
  DateTime getRequestTime() {
    if (requestTime == null) {
      requestTime = clock.nowUtc();
    }
    return requestTime;
  }
}
