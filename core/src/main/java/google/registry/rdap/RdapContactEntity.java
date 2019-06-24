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

import static google.registry.model.EppResourceUtils.isLinked;
import static google.registry.rdap.RdapIcannStandardInformation.CONTACT_REDACTED_VALUE;
import static google.registry.util.CollectionUtils.union;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.rdap.RdapDataStructures.Event;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapDataStructures.Remark;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.Vcard;
import google.registry.rdap.RdapObjectClasses.VcardArray;
import java.util.Optional;

/**
 * Contact version of the Entity Object Class defined in 5.1 of RFC7483.
 *
 * <p>Entities are used for Registrars and for Contacts. For Contacts, it's used for "registrant
 * contacts" and "registrar contacts", which in our code are different entities (and in the RDAP
 * spec have different requirements, for example GDPR doesn't apply to registrar contacts). We
 * create a different subobject for each one.
 *
 * <p>RDAP Response Profile 2.7.1, 2.7.3 - we MUST have the contacts. 2.7.4 discusses censoring of
 * fields we don't want to show (as opposed to not having contacts at all) because of GDPR etc.
 *
 * <p>2.8 allows for unredacted output for authorized people.
 *
 * <p>So we create a Redacted and an Unredacted version, which are slightly different.
 */
final class RdapContactEntity extends RdapEntity {

  private static final ContactResource REDACTED_CONTACT_RESOURCE =
      new ContactResource.Builder()
          .setRepoId(CONTACT_REDACTED_VALUE)
          .setVoiceNumber(
              new ContactPhoneNumber.Builder().setPhoneNumber(CONTACT_REDACTED_VALUE).build())
          .setFaxNumber(
              new ContactPhoneNumber.Builder().setPhoneNumber(CONTACT_REDACTED_VALUE).build())
          .setInternationalizedPostalInfo(
              new PostalInfo.Builder()
                  .setName(CONTACT_REDACTED_VALUE)
                  .setOrg(CONTACT_REDACTED_VALUE)
                  .setType(PostalInfo.Type.INTERNATIONALIZED)
                  .setAddress(
                      new ContactAddress.Builder()
                          .setStreet(ImmutableList.of(CONTACT_REDACTED_VALUE))
                          .setCity(CONTACT_REDACTED_VALUE)
                          .setState(CONTACT_REDACTED_VALUE)
                          .setZip(CONTACT_REDACTED_VALUE)
                          .setCountryCode("XX")
                          .build())
                  .build())
          .build();

  private final ImmutableSet<Role> roles;

  /**
   * RDAP Response Profile 2.7.1, 2.7.3 - we MUST have the contacts. 2.7.4 discusses censoring of
   * fields we don't want to show (as opposed to not having contacts at all) because of GDPR etc.
   *
   * <p>2.8 allows for unredacted output for authorized people.
   *
   * <p>This boolean tells us whether the contact is redacted or not. NOTE - it doesn't change the
   * actual data that's posted. The contactResource member will be used as-is even if isRedacted is
   * set to true. contactResource needs to have all sensitive fields redacted as well.
   */
  private final boolean isRedacted;

  /**
   * The contact resource we're outputting.
   *
   * <p>If we want to redact any information, we should use a ContactResource with the desired
   * fields already redacted.
   */
  private final ContactResource contactResource;

  /**
   * RDAP Response Profile 2.7.5.1, 2.7.5.3: email MUST be omitted, and we MUST have a Remark saying
   * so.
   */
  @JsonableElement("remarks[]")
  Remark zEmailRedactedRemark() {
    return RdapIcannStandardInformation.CONTACT_EMAIL_REDACTED_FOR_DOMAIN;
  }

  /** The unredacted contact version. */
  private RdapContactEntity(
      ContactResource contactResource,
      Iterable<Role> roles,
      OutputDataType outputDataType,
      RdapJsonFormatter rdapJsonFormatter) {
    super(outputDataType, rdapJsonFormatter);
    this.isRedacted = false;
    this.roles = ImmutableSet.copyOf(roles);
    this.contactResource = contactResource;
  }

  /** The redacted contact version. Note that it doesn't even get the ContactResource */
  private RdapContactEntity(
      Iterable<Role> roles, OutputDataType outputDataType, RdapJsonFormatter rdapJsonFormatter) {
    super(outputDataType, rdapJsonFormatter);
    this.isRedacted = true;
    this.roles = ImmutableSet.copyOf(roles);
    this.contactResource = REDACTED_CONTACT_RESOURCE;
  }

  @JsonableElement
  ImmutableSet<Role> roles() {
    return roles;
  }

  /**
   * RDAP Response Profile 2.7.3 - we MUST provide a handle set with the ROID.
   *
   * <p>Any redaction is done when setting the ContactResource.
   */
  @JsonableElement
  String handle() {
    return contactResource.getRepoId();
  }

  /**
   * Adding the VCard members.
   *
   * <p>Any redaction is done when setting the ContactResource.
   *
   * <p>RDAP Response Profile 2.7.3 - we MUST have FN, ADR, TEL, EMAIL.
   *
   * <p>Note that 2.7.5 also says the EMAIL must be omitted, so don't actually set it.
   */
  @JsonableElement
  VcardArray vcardArray() {
    VcardArray.Builder builder = VcardArray.builder();
    PostalInfo postalInfo = contactResource.getInternationalizedPostalInfo();
    if (postalInfo == null) {
      postalInfo = contactResource.getLocalizedPostalInfo();
    }
    if (postalInfo != null) {
      if (postalInfo.getName() != null) {
        builder.add(Vcard.create("fn", "text", postalInfo.getName()));
      }
      if (postalInfo.getOrg() != null) {
        builder.add(Vcard.create("org", "text", postalInfo.getOrg()));
      }
      rdapJsonFormatter.addVCardAddressEntry(builder, postalInfo.getAddress());
    }
    ContactPhoneNumber voicePhoneNumber = contactResource.getVoiceNumber();
    if (voicePhoneNumber != null) {
      builder.add(
          RdapJsonFormatter.makeVoicePhoneVcard(
              voicePhoneNumber.getPhoneNumber(), voicePhoneNumber.getExtension()));
    }
    ContactPhoneNumber faxPhoneNumber = contactResource.getFaxNumber();
    if (faxPhoneNumber != null) {
      builder.add(
          RdapJsonFormatter.makeFaxPhoneVcard(
             faxPhoneNumber.getPhoneNumber(), faxPhoneNumber.getExtension()));
    }
    return builder.build();
  }

  /**
   * Not required by the spec, but we like it.
   *
   * <p>There's no self-link in the redacted version because the ROID needs to be redacted.
   */
  @JsonableElement("links[]")
  Optional<Link> selfLink() {
    if (isRedacted) {
      return Optional.empty();
    }
    return Optional.of(rdapJsonFormatter.makeSelfLink("entity", contactResource.getRepoId()));
  }

    /**
     * For redacted contacts we have no self link, so no point in having the summary remark.
     */
    @Override
    Optional<Remark> summaryRemark() {
      if (isRedacted) {
        return Optional.empty();
      }
      return super.summaryRemark();
    }

  /**
   * RDAP Response Profile doesn't mention status for contacts.
   *
   * <p>We choose to show it anyway for FULL unredacted contacts for our own convenience.
   */
  @JsonableElement
  ImmutableSet<RdapStatus> status() {
    if (isRedacted || outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    return rdapJsonFormatter.makeStatusValueList(
        isLinked(Key.create(contactResource), rdapJsonFormatter.getRequestTime())
            ? union(contactResource.getStatusValues(), StatusValue.LINKED)
            : contactResource.getStatusValues(),
        false,
        contactResource.getDeletionTime().isBefore(rdapJsonFormatter.getRequestTime()));
  }

  /**
   * Rdap Response Profile doesn't mention events for contacts.
   *
   * <p>We choose to show it anyway for FULL unredacted contacts for our own convenience.
   *
   * <p>We only show it in the unredacted version because millisecond times can fingerprint a
   * contact.
   */
  @JsonableElement
  ImmutableSet<Event> events() {
    if (isRedacted || outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    return rdapJsonFormatter.makeOptionalEvents(contactResource);
  }

  /**
   * RDAP Response Profile 2.7.4.3: if we redact values from the contact, we MUST include a remark.
   */
  @JsonableElement("remarks[]")
  Optional<Remark> dataHiddenRemark() {
    return isRedacted
        ? Optional.of(RdapIcannStandardInformation.CONTACT_PERSONAL_DATA_HIDDEN_DATA_REMARK)
        : Optional.empty();
  }

  /**
   * If the user is authorized to see this contact (is owner or admin), we return an unredacted
   * version that has all the contact information.
   */
  static RdapContactEntity createUnredacted(
      ContactResource contactResource,
      Iterable<Role> roles,
      OutputDataType outputDataType,
      RdapJsonFormatter rdapJsonFormatter) {
    return new RdapContactEntity(contactResource, roles, outputDataType, rdapJsonFormatter);
  }

  /**
   * If the user isn't authorized to see this contact (isn't owner or admin), we return a redacted
   * version that has no information about the contact.
   */
  static RdapContactEntity createRedacted(
      Iterable<Role> roles,
      OutputDataType outputDataType,
      RdapJsonFormatter rdapJsonFormatter) {
    return new RdapContactEntity(roles, outputDataType, rdapJsonFormatter);
  }
}
