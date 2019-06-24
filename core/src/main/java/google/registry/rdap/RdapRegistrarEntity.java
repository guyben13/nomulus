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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Remark;
import google.registry.rdap.RdapDataStructures.PublicId;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.Vcard;
import google.registry.rdap.RdapObjectClasses.VcardArray;
import java.util.Optional;

/**
 * Registrar version of the Entity Object Class defined in 5.1 of RFC7483.
 *
 * <p>Entities are used for Registrars and for Contacts. We create a different subobject for each
 * one.
 */
final class RdapRegistrarEntity extends RdapEntity {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Registrar registrar;

  RdapRegistrarEntity(
      Registrar registrar, OutputDataType outputDataType, RdapJsonFormatter rdapJsonFormatter) {
    super(outputDataType, rdapJsonFormatter);
    this.registrar = registrar;
  }

  /**
   * RDAP Response Profile 2.4.3 and 4.3: MUST contain a publicId member with the IANA ID.
   *
   * <p>4.3 also says that if no IANA ID exists, the response MUST NOT contain the publicId member.
   * 2.4 doesn't discuss this possibility.
   */
  @JsonableElement("publicIds[]")
  Optional<PublicId> publicIds() {
    Long ianaIdentifier = registrar.getIanaIdentifier();
    if (ianaIdentifier == null) {
      return Optional.empty();
    }
    return Optional.of(PublicId.create(PublicId.Type.IANA_REGISTRAR_ID, ianaIdentifier.toString()));
  }

  /**
   * Not required by the spec, but we like it.
   *
   * <p>There's no self-link if there's no IANA ID, since the IANA ID is the handle.
   */
  @JsonableElement("links[]")
  Optional<Link> selfLink() {
    Long ianaIdentifier = registrar.getIanaIdentifier();
    if (ianaIdentifier == null) {
      return Optional.empty();
    }
    return Optional.of(rdapJsonFormatter.makeSelfLink("entity", ianaIdentifier.toString()));
  }

  /** Rdap Response Profile 2.4.1 - The role must me "registrar" */
  @JsonableElement("roles[]")
  RdapEntity.Role role() {
    return RdapEntity.Role.REGISTRAR;
  }

  /**
   * Rdap Response Profile 2.4.1, 3.1 - We must have fn, adr, tel, email VCards.
   *
   * <p>fn is required always (in 2.4.1), but the others are only required in Registrar queries
   * (3.1).
   */
  @JsonableElement("vcardArray")
  VcardArray vcardArray() {
    VcardArray.Builder builder = VcardArray.builder();
    String registrarName = registrar.getRegistrarName();
    // FN is always required
    builder.add(Vcard.create("fn", "text", registrarName == null ? "(none)" : registrarName));

    // ADR, TEL, EMAIL aren't required in INTERNAL responses.
    if (outputDataType != OutputDataType.INTERNAL) {
      // Rdap Response Profile 3.1.1 and 3.1.2 discuss the ADR field. See {@link
      // addVcardAddressEntry}
      RegistrarAddress address = registrar.getInternationalizedAddress();
      if (address == null) {
        address = registrar.getLocalizedAddress();
      }
      rdapJsonFormatter.addVCardAddressEntry(builder, address);
      // TEL fields can be phone or fax
      String voicePhoneNumber = registrar.getPhoneNumber();
      if (voicePhoneNumber != null) {
        builder.add(RdapJsonFormatter.makeVoicePhoneVcard(voicePhoneNumber, ""));
      }
      String faxPhoneNumber = registrar.getFaxNumber();
      if (faxPhoneNumber != null) {
        builder.add(RdapJsonFormatter.makeFaxPhoneVcard(voicePhoneNumber, ""));
      }
      // EMAIL field
      String emailAddress = registrar.getEmailAddress();
      if (emailAddress != null) {
        builder.add(Vcard.create("email", "text", emailAddress));
      }
    }
    return builder.build();
  }

  /**
   * RDAP Response Profile 2.4.2 and 4.3: The handle MUST be the IANA ID.
   *
   * <p>4.3 also says that if no IANA ID exists (which should never be the case for a valid
   * registrar), the value must be "not applicable". 2.4 doesn't discuss this possibility.
   */
  @JsonableElement
  String handle() {
    Long ianaIdentifier = registrar.getIanaIdentifier();
    return (ianaIdentifier == null) ? "not applicable" : ianaIdentifier.toString();
  }

  /**
   * Rdap Response Profile 2.4.5, 3.2, 4.3 discuss Registrar contacts.
   *
   * <p>Which contact to show in which case is a bit complicated.
   *
   * <p>Rdap Response Profile 3.2, we SHOULD have at least ADMIN and TECH contacts. It says nothing
   * about ABUSE at all.
   *
   * <p>Rdap Response Profile 4.3 doesn't mention contacts at all, meaning probably we don't have to
   * have any contacts there. But the Registrar itself is Optional in that case, so we will just
   * skip it completely.
   *
   * <p>Rdap Response Profile 2.4.5 says the Registrar inside a Domain response MUST include the
   * ABUSE contact, but doesn't require any other contact.
   *
   * <p>In our current Datastore schema, to get the ABUSE contact we must go over all contacts.
   * However, there's something to be said about returning smaller JSON, especially for INTERNAL
   * objects that aren't the focus of the query.
   *
   * <p>So we decided to write the minimum, meaning only ABUSE for INTERNAL registrars, nothing for
   * SUMMARY (also saves resources for the RegistrarContact Datastore query!) and everything for
   * FULL.
   */
  @JsonableElement("entities")
  ImmutableList<RdapRegistrarContactEntity> contacts() {
    if (outputDataType == OutputDataType.SUMMARY) {
      return ImmutableList.of();
    }
    ImmutableList<RdapRegistrarContactEntity> contacts =
        registrar.getContacts().stream()
            .map(
                registrarContact ->
                    new RdapRegistrarContactEntity(registrarContact, rdapJsonFormatter))
            // for FULL Registrar show contacts with ANY role, otherwise only show if it's the ABUSE
            // role
            .filter(
                contact ->
                    outputDataType == OutputDataType.FULL
                        ? !contact.roles().isEmpty()
                        : contact.roles().contains(RdapEntity.Role.ABUSE))
            .collect(toImmutableList());
    if (contacts.stream().noneMatch(contact -> contact.roles().contains(RdapEntity.Role.ABUSE))) {
      logger.atWarning().log(
          "Registrar '%s' (IANA ID %s) is missing ABUSE contact",
          registrar.getClientId(), registrar.getIanaIdentifier());
    }
    return contacts;
  }

  /**
   * (Registrar) Contact version of the Entity Object Class defined in 5.1 of RFC7483.
   *
   * <p>Entities are used for Registrars and for Contacts. For Contacts, it's used for "registrant
   * contacts" and "registrar contacts", which in our code are different entities (and in the RDAP
   * spec have different requirements, for example GDPR doesn't apply to registrar contacts). We
   * create a different subobject for each one.
   *
   * <p>RegistrarContact isn't a contact we can query individually (using, e.g., the "entity" RDAP
   * query) - it's only used internally in the Registrar. It has no handle, and no link etc.
   */
  static final class RdapRegistrarContactEntity extends RdapEntity {

    RegistrarContact registrarContact;

    /**
     * The roles of this contact.
     *
     * <p>Saving the roles separately rather than calculating it "on demand" since we will need it
     * multiple times - both to output the JSON and to check whether we want to show this contact in
     * the first place.
     */
    private final ImmutableSet<Role> roles;

    /**
     * Since this isn't a contact we can query individually, no point in sending out the "summary
     * remark"
     */
    @Override
    Optional<Remark> summaryRemark() {
      return Optional.empty();
    }

    RdapRegistrarContactEntity(
        RegistrarContact registrarContact, RdapJsonFormatter rdapJsonFormatter) {
      // RegistrarContact isn't a contact we can actually "query" individually. It's only used
      // internally in a registrar - so the OutputDataType is always "INTENRAL".
      super(OutputDataType.INTERNAL, rdapJsonFormatter);
      this.registrarContact = registrarContact;
      this.roles = makeRdapRoleList(registrarContact);
    }

    @JsonableElement ImmutableSet<Role> roles() {
      return roles;
    }

    @JsonableElement ImmutableSet<RdapStatus> status() {
      return RdapJsonFormatter.STATUS_LIST_ACTIVE;
    }

    /** Rdap Response Profile 2.4.5, 3.2, Contact VCards MUST include FN, TEL, EMAIL. */
    @JsonableElement VcardArray vcardArray() {
      // Create the vCard.
      VcardArray.Builder builder = VcardArray.builder();
      // MUST include FN member: RDAP Response Profile 3.2
      String name = registrarContact.getName();
      if (name != null) {
        builder.add(Vcard.create("fn", "text", name));
      }
      // MUST include TEL and EMAIL members: RDAP Response Profile 2.4.5, 3.2
      String voicePhoneNumber = registrarContact.getPhoneNumber();
      if (voicePhoneNumber != null) {
        builder.add(RdapJsonFormatter.makeVoicePhoneVcard(voicePhoneNumber, ""));
      }
      String faxPhoneNumber = registrarContact.getFaxNumber();
      if (faxPhoneNumber != null) {
        builder.add(RdapJsonFormatter.makeFaxPhoneVcard(faxPhoneNumber, ""));
      }
      String emailAddress = registrarContact.getEmailAddress();
      if (emailAddress != null) {
        builder.add(Vcard.create("email", "text", emailAddress));
      }
      return builder.build();
    }
  }

  /**
   * Creates the list of RDAP roles for a registrar contact, using the visibleInWhoisAs* flags.
   *
   * <p>Only contacts with a non-empty role list should be visible.
   *
   * <p>The RDAP response profile only mandates the "abuse" entity:
   *
   * <p>2.4.5. Abuse Contact (email, phone) - an RDAP server MUST include an *entity* with the
   * *abuse* role within the registrar *entity* which MUST include *tel* and *email*, and MAY
   * include other members
   *
   * <p>3.2. For direct Registrar queries, we SHOULD have at least "admin" and "tech".
   */
  private static ImmutableSet<RdapEntity.Role> makeRdapRoleList(
      RegistrarContact registrarContact) {
    ImmutableSet.Builder<RdapEntity.Role> rolesBuilder = new ImmutableSet.Builder<>();
    if (registrarContact.getVisibleInWhoisAsAdmin()) {
      rolesBuilder.add(RdapEntity.Role.ADMIN);
    }
    if (registrarContact.getVisibleInWhoisAsTech()) {
      rolesBuilder.add(RdapEntity.Role.TECH);
    }
    if (registrarContact.getVisibleInDomainWhoisAsAbuse()) {
      rolesBuilder.add(RdapEntity.Role.ABUSE);
    }
    return rolesBuilder.build();
  }
}
