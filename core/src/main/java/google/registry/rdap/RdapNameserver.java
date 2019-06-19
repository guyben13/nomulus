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
import static google.registry.model.EppResourceUtils.isLinked;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.AbstractJsonableObject.RestrictJsonNames;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.ObjectClassName;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.RdapNamedObjectBase;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Optional;

/**
 * The Nameserver Object Class defined in 5.2 of RFC7483.
 */
@RestrictJsonNames({"nameservers[]", "nameserverSearchResults[]"})
final class RdapNameserver extends RdapNamedObjectBase {

  private final HostResource hostResource;

  RdapNameserver(
      HostResource hostResource,
      OutputDataType outputDataType,
      RdapJsonFormatter rdapJsonFormatter) {
    super(
        BoilerplateType.NAMESERVER, ObjectClassName.NAMESERVER, outputDataType, rdapJsonFormatter);
    this.hostResource = hostResource;
  }

  /**
   * We need the ldhName: RDAP Response Profile 2.9.1, 4.1.
   *
   * <p>TODO(guyben): into super()
   */
  @Override
  String ldhName() {
    return hostResource.getFullyQualifiedHostName();
  }

  /**
   * Handle is optional, but if given it MUST be the ROID.
   *
   * <p>We will set it always as it's important as a "self link".
   */
  @JsonableElement
  String handle() {
    return hostResource.getRepoId();
  }

  /** Not required by the spec, but we like it. */
  @JsonableElement("links[]")
  Link selfLink() {
    return rdapJsonFormatter.makeSelfLink("nameserver", hostResource.getFullyQualifiedHostName());
  }

  /**
   * Status is optional for internal Nameservers - RDAP Response Profile 2.9.2.
   *
   * <p>It isn't mentioned at all anywhere else. So we can just not put it at all?
   *
   * <p>To be safe, we'll put it on the "FULL" version anyway
   *
   * TODO(guyben): keep only in RdapNameserverFull
   */
  @JsonableElement ImmutableSet<RdapStatus> status() {
    if (outputDataType != OutputDataType.FULL) {
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<StatusValue> statuses = new ImmutableSet.Builder<>();
    statuses.addAll(hostResource.getStatusValues());
    if (isLinked(Key.create(hostResource), rdapJsonFormatter.getRequestTime())) {
      statuses.add(StatusValue.LINKED);
    }
    if (hostResource.isSubordinate()
        && ofy()
            .load()
            .key(hostResource.getSuperordinateDomain())
            .now()
            .cloneProjectedAtTime(rdapJsonFormatter.getRequestTime())
            .getStatusValues()
            .contains(StatusValue.PENDING_TRANSFER)) {
      statuses.add(StatusValue.PENDING_TRANSFER);
    }
    return RdapJsonFormatter.makeStatusValueList(
        statuses.build(),
        false, // isRedacted
        hostResource.getDeletionTime().isBefore(rdapJsonFormatter.getRequestTime()));
  }

  /**
   * For query responses - we MUST have all the ip addresses: RDAP Response Profile 4.2.
   *
   * <p>However, it is optional for internal responses: RDAP Response Profile 2.9.2
   */
  @JsonableElement Optional<IpAddresses> ipAddresses() {
    if (outputDataType == OutputDataType.INTERNAL) {
      return Optional.empty();
    }

    ImmutableSet<String> ipv4 =
        hostResource.getInetAddresses().stream()
            .filter(inetAddress -> inetAddress instanceof Inet4Address)
            .map(InetAddresses::toAddrString)
            .sorted(Ordering.natural())
            .collect(toImmutableSet());
    ImmutableSet<String> ipv6 =
        hostResource.getInetAddresses().stream()
            .filter(inetAddress -> inetAddress instanceof Inet6Address)
            .map(InetAddresses::toAddrString)
            .sorted(Ordering.natural())
            .collect(toImmutableSet());
    if (ipv6.isEmpty() && ipv4.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new IpAddresses(ipv4, ipv6));
  }

  @RestrictJsonNames("ipAddresses")
  static class IpAddresses extends AbstractJsonableObject {
    @JsonableElement final ImmutableSet<String> v4;
    @JsonableElement final ImmutableSet<String> v6;

    IpAddresses(ImmutableSet<String> v4, ImmutableSet<String> v6) {
      this.v4 = v4;
      this.v6 = v6;
    }
  }

  /** RDAP Response Profile 4.3 - Registrar member is optional, so we only set it for FULL. */
  @JsonableElement("entities[]")
  Optional<RdapRegistrarEntity> registrar() {
    if (outputDataType != OutputDataType.FULL) {
      return Optional.empty();
    }
    Registrar registrar = Registrar.loadRequiredRegistrarCached(hostResource.getPersistedCurrentSponsorClientId());
    return Optional.of(
        rdapJsonFormatter.createRdapRegistrarEntity(
            registrar, OutputDataType.INTERNAL));
  }
}
