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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.AbstractJsonableObject.RestrictJsonNames;
import google.registry.rdap.RdapDataStructures.Event;
import google.registry.rdap.RdapDataStructures.EventAction;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.ObjectClassName;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.RdapNamedObjectBase;
import google.registry.rdap.RdapObjectClasses.SecureDns;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Domain Object Class defined in 5.3 of RFC7483.
 *
 * <p>We're missing the "variants", "network" fields
 *
 * <p>Since RdapDomain is never INTERNAL (never referenced inside another object), and since the
 * spec doesn't require domain searches at all (so it doesn't define the minimum spec we would
 * usually use for SUMMARY) - we can do whatever we want for non-FULL RdapDomains.
 *
 * <p>We just put the minimum info (basically name and self-link to the full result) in the non-FULL
 * versions.
 */
@RestrictJsonNames("domainSearchResults[]")
class RdapDomain extends RdapNamedObjectBase {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Sets the ordering for hosts; just use the fully qualified host name. */
  private static final Ordering<HostResource> HOST_RESOURCE_ORDERING =
      Ordering.natural().onResultOf(HostResource::getFullyQualifiedHostName);

  protected final DomainBase domainBase;

  private RdapDomain(
      DomainBase domainBase, OutputDataType outputDataType, RdapJsonFormatter rdapJsonFormatter) {
    super(BoilerplateType.DOMAIN, ObjectClassName.DOMAIN, outputDataType, rdapJsonFormatter);
    this.domainBase = domainBase;
  }

  static RdapDomain createSummary(DomainBase domainBase, RdapJsonFormatter rdapJsonFormatter) {
    return new RdapDomain(domainBase, OutputDataType.SUMMARY, rdapJsonFormatter);
  }

  static RdapDomain createFull(DomainBase domainBase, RdapJsonFormatter rdapJsonFormatter) {
    return new RdapDomain(domainBase, OutputDataType.FULL, rdapJsonFormatter);
  }

  /** RDAP Response Profile 2.2: The domain handle MUST be the ROID */
  @JsonableElement
  String handle() {
    return domainBase.getRepoId();
  }

  /** Not required by the spec, but we like it. */
  @JsonableElement("links[]")
  Link selfLink() {
    return rdapJsonFormatter.makeSelfLink("domain", domainBase.getFullyQualifiedDomainName());
  }

  /**
   * RDAP Response Profile 2.1 discusses the domain name.
   *
   * <p>TODO(guyben): into super()
   */
  @Override
  String ldhName() {
    return domainBase.getFullyQualifiedDomainName();
  }

  /** Sets the ordering for designated contacts; order them in a fixed order by contact type. */
  private static final Ordering<DesignatedContact> DESIGNATED_CONTACT_ORDERING =
      Ordering.natural().onResultOf(DesignatedContact::getType);

  /** RDAP Response Profile 2.3.1 lists the required events. */
  @JsonableElement("events")
  ImmutableSet<Event> requiredEvents() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    // 2.3.1: The domain object in the RDAP response MUST contain the following events:
    // [registration, expiration, last update of RDAP database].
    //
    // We already do the "last update of RDAP database in RdapObjectBase.
    return ImmutableSet.of(
        Event.builder()
            .setEventAction(EventAction.REGISTRATION)
            .setEventActor(Optional.ofNullable(domainBase.getCreationClientId()).orElse("(none)"))
            .setEventDate(domainBase.getCreationTime())
            .build(),
        Event.builder()
            .setEventAction(EventAction.EXPIRATION)
            .setEventDate(domainBase.getRegistrationExpirationTime())
            .build());
  }

  /** RDAP Response Profile 2.3.2 lists the optional events. */
  @JsonableElement("events")
  ImmutableSet<Event> zzOptionalEvents() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    // RDAP Response Profile 2.3.2 discusses optional events. We add some of those here. We also
    // add
    // a few others we find interesting.
    return rdapJsonFormatter.makeOptionalEvents(domainBase);
  }

    @JsonableElement("events[]")
    Optional<Event> zLastUpdateOfRdapDatabaseEvent(){
      if (outputDataType != OutputDataType.INTERNAL) {
        return Optional.empty();
      }
      return super.zLastUpdateOfRdapDatabaseEvent();
    }

  /**
   * RDAP Response Profile 2.4.1: The domain object in the RDAP response MUST contain an entity with
   * the Registrar role.
   *
   * <p>See {@link RdapRegistrarEntity} for details of section 2.4 conformance
   */
  @JsonableElement("entities[]")
  Optional<RdapRegistrarEntity> aRegistar() {
    if (outputDataType != OutputDataType.FULL) {
      return Optional.empty();
    }
    Registrar registrar =
        Registrar.loadRequiredRegistrarCached(domainBase.getCurrentSponsorClientId());
    return Optional.of(
        rdapJsonFormatter.createRdapRegistrarEntity(registrar, OutputDataType.INTERNAL));
  }

  /**
   * RDAP Technical Implementation Guide 3.2: must have link to the registrar's RDAP URL for this
   * domain, with rel=related.
   */
  @JsonableElement("links")
  ImmutableSet<Link> zRegistrarRdapLinks() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<Link> builder = new ImmutableSet.Builder<>();
    Registrar registrar =
        Registrar.loadRequiredRegistrarCached(domainBase.getCurrentSponsorClientId());
    for (String registrarRdapBase : registrar.getRdapBaseUrls()) {
      String href =
          rdapJsonFormatter.makeServerRelativeUrl(
              registrarRdapBase, "domain", domainBase.getFullyQualifiedDomainName());
      builder.add(
          Link.builder()
              .setHref(href)
              .setValue(href)
              .setRel("related")
              .setType("application/rdap+json")
              .build());
    }
    return builder.build();
  }

  /**
   * RDAP Response Profile 2.6.1: must have at least one status member.
   *
   * <p>makeStatusValueList should in theory always contain one of either "active" or "inactive".
   */
  @JsonableElement
  ImmutableSet<RdapStatus> status() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    ImmutableSet<RdapStatus> status =
        rdapJsonFormatter.makeStatusValueList(
            domainBase.getStatusValues(),
            false, // isRedacted
            domainBase.getDeletionTime().isBefore(rdapJsonFormatter.getRequestTime()));
    if (status.isEmpty()) {
      logger.atWarning().log(
          "Domain %s (ROID %s) doesn't have any status",
          domainBase.getFullyQualifiedDomainName(), domainBase.getRepoId());
    }
    return status;
  }

  /**
   * RDAP Response Profile 2.7.3, A domain MUST have the REGISTRANT, ADMIN, TECH roles and MAY have
   * others.
   *
   * <p>We also add the BILLING.
   *
   * <p>RDAP Response Profile 2.7.1, 2.7.3 - we MUST have the contacts. 2.7.4 discusses redaction of
   * fields we don't want to show (as opposed to not having contacts at all) because of GDPR etc.
   *
   * <p>the GDPR redaction is handled in createRdapContactEntity
   */
  @JsonableElement("entities")
  ImmutableSet<RdapContactEntity> contacts() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    Map<Key<ContactResource>, ContactResource> loadedContacts =
        ofy().load().keys(domainBase.getReferencedContacts());

    ImmutableSet.Builder<RdapContactEntity> builder = new ImmutableSet.Builder<>();
    ImmutableSetMultimap<Key<ContactResource>, Type> contactsToRoles =
        Streams.concat(
                domainBase.getContacts().stream(),
                Stream.of(DesignatedContact.create(Type.REGISTRANT, domainBase.getRegistrant())))
            .sorted(DESIGNATED_CONTACT_ORDERING)
            .collect(
                toImmutableSetMultimap(
                    DesignatedContact::getContactKey, DesignatedContact::getType));

    for (Key<ContactResource> contactKey : contactsToRoles.keySet()) {
      Set<RdapEntity.Role> roles =
          contactsToRoles.get(contactKey).stream()
              .map(RdapJsonFormatter::convertContactTypeToRdapRole)
              .collect(toImmutableSet());
      if (roles.isEmpty()) {
        continue;
      }
      builder.add(
          rdapJsonFormatter.createRdapContactEntity(
              loadedContacts.get(contactKey), roles, OutputDataType.INTERNAL));
    }
    return builder.build();
  }

  /** RDAP Response Profile 2.9: we MUST have the nameservers. */
  @JsonableElement
  ImmutableSet<RdapNameserver> nameservers() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    Map<Key<HostResource>, HostResource> loadedHosts =
        ofy().load().keys(domainBase.getNameservers());
    return HOST_RESOURCE_ORDERING.immutableSortedCopy(loadedHosts.values()).stream()
        .map(
            hostResource ->
                rdapJsonFormatter.createRdapNameserver(hostResource, OutputDataType.INTERNAL))
        .collect(toImmutableSet());
  }

  /**
   * RDAP Response Profile 2.10 - MUST contain a secureDns member including at least a
   * delegationSigned element.
   *
   * <p>Other elements (e.g. dsData) MUST be included if the domain name is signed and the elements
   * are stored in the Registry
   *
   * <p>TODO(b/133310221): get the zoneSigned value from the config files.
   */
  @JsonableElement("secureDNS")
  Optional<SecureDns> secureDns() {
    if (outputDataType != OutputDataType.FULL) {
      return Optional.empty();
    }
    SecureDns.Builder builder = SecureDns.builder().setZoneSigned(true);
    domainBase.getDsData().forEach(builder::addDsData);
    return Optional.of(builder.build());
  }
}
