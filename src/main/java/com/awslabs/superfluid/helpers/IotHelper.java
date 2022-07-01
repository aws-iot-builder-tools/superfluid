package com.awslabs.superfluid.helpers;


import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.control.Try;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.services.iot.model.*;

import java.util.stream.Stream;

import static com.awslabs.superfluid.helpers.AwsSdkHelper.*;
import static com.awslabs.superfluid.helpers.Shared.iotClient;
import static java.text.MessageFormat.format;

public class IotHelper {

    public static final String IAM_CERT_IDENTIFIER = ":cert/";

    public static Try<Stream<String>> streamThingsInThingGroup(String thingGroupName) {
        ListThingsInThingGroupRequest listThingsInThingGroupRequest = ListThingsInThingGroupRequest.builder()
                .thingGroupName(thingGroupName)
                .build();

        return resultStream(() -> iotClient().listThingsInThingGroupPaginator(listThingsInThingGroupRequest), ListThingsInThingGroupResponse::things);
    }

    public static Try<List<String>> listThingsInThingGroup(String thingGroupName) {
        return resultListWithSpinner(() -> streamThingsInThingGroup(thingGroupName), "things in thing group " + thingGroupName, LIST);
    }

    public static Try<Stream<String>> streamPrincipalsAttachedToThing(String thingName) {
        ListThingPrincipalsRequest listThingPrincipalsRequest = ListThingPrincipalsRequest.builder()
                .thingName(thingName)
                .build();

        return resultStream(() -> iotClient().listThingPrincipalsPaginator(listThingPrincipalsRequest), ListThingPrincipalsResponse::principals);
    }

    public static Try<List<String>> listPrincipalsAttachedToThing(String thingName) {
        return resultListWithSpinner(() -> streamPrincipalsAttachedToThing(thingName), "principals attached to the thing " + thingName, LIST);
    }

    public static Try<Stream<Policy>> streamAttachedPolicies(Arn target) {
        ListAttachedPoliciesRequest listAttachedPoliciesRequest = ListAttachedPoliciesRequest.builder()
                .target(target.toString())
                .build();

        return resultStream(() -> iotClient().listAttachedPoliciesPaginator(listAttachedPoliciesRequest),
                ListAttachedPoliciesResponse::policies);
    }

    public static Try<Tuple2<Arn, List<Policy>>> listAttachedPolicies(Arn target) {
        return resultListWithSpinner(() -> streamAttachedPolicies(target), format("policies attached to target {0}", target.resourceAsString()), LIST)
                .map(value -> Tuple.of(target, value));
    }

    public static boolean containsNonCertificatePrincipals(List<String> principals) {
        return principals.filter(principal -> !principal.contains(IAM_CERT_IDENTIFIER))
                .nonEmpty();
    }

    public static List<Arn> getCertificatesFromPrincipalList(List<Arn> principals) {
        return principals.filter(principal -> principal.resource().resourceType().filter(type -> type.equals("cert")).isPresent());
    }

    public static Try<RoleAliasDescription> describeRoleAlias(String roleAlias) {
        DescribeRoleAliasRequest describeRoleAliasRequest = DescribeRoleAliasRequest.builder()
                .roleAlias(roleAlias)
                .build();

        return resultWithSpinner(() -> iotClient().describeRoleAlias(describeRoleAliasRequest),
                DescribeRoleAliasResponse::roleAliasDescription,
                "role alias " + roleAlias, DESCRIBE);
    }

    public static Try<DeleteRoleAliasResponse> deleteRoleAlias(String roleAlias) {
        DeleteRoleAliasRequest deleteRoleAliasRequest = DeleteRoleAliasRequest.builder()
                .roleAlias(roleAlias)
                .build();

        return resultWithSpinner(() -> iotClient().deleteRoleAlias(deleteRoleAliasRequest),
                "role alias " + roleAlias, DELETE);
    }

    public static Try<DeleteThingGroupResponse> deleteThingGroup(String thingGroupName) {
        DeleteThingGroupRequest deleteThingGroupRequest = DeleteThingGroupRequest.builder()
                .thingGroupName(thingGroupName)
                .build();

        return resultWithSpinner(() -> iotClient().deleteThingGroup(deleteThingGroupRequest),
                "thing group " + thingGroupName, DELETE);
    }

    public static Try<DeleteThingResponse> deleteThing(String thingName) {
        DeleteThingRequest deleteThingRequest = DeleteThingRequest.builder()
                .thingName(thingName)
                .build();

        return resultWithSpinner(() -> iotClient().deleteThing(deleteThingRequest),
                "thing " + thingName, DELETE);
    }

    public static Try<DetachThingPrincipalResponse> detachPrincipalFromThing(String thingName, Arn principal) {
        DetachThingPrincipalRequest detachThingPrincipalRequest = DetachThingPrincipalRequest.builder()
                .thingName(thingName)
                .principal(principal.toString())
                .build();

        return resultWithSpinner(() -> iotClient().detachThingPrincipal(detachThingPrincipalRequest),
                format("principal {0} from thing {1}", principal.resourceAsString(), thingName), DETACH);
    }

    public static Try<DetachPolicyResponse> detachPolicyFromTarget(Arn target, Policy policy) {
        DetachPolicyRequest detachPolicyRequest = DetachPolicyRequest.builder()
                .target(target.toString())
                .policyName(policy.policyName())
                .build();

        return resultWithSpinner(() -> iotClient().detachPolicy(detachPolicyRequest),
                format("policy {0} from target {1}", policy.policyName(), target.resourceAsString()), DETACH);
    }

    public static Try<DeletePolicyResponse> deletePolicy(Policy policy) {
        DeletePolicyRequest deletePolicyRequest = DeletePolicyRequest.builder()
                .policyName(policy.policyName())
                .build();

        return resultWithSpinner(() -> iotClient().deletePolicy(deletePolicyRequest),
                format("policy {0}", policy.policyName()), DELETE);
    }

    public static Try<DeleteCertificateResponse> purgeCertificate(Arn certificateArn) {
        UpdateCertificateRequest updateCertificateRequest = UpdateCertificateRequest.builder()
                .certificateId(certificateArn.resource().resource())
                .newStatus(CertificateStatus.INACTIVE)
                .build();

        Try<UpdateCertificateResponse> tryUpdateCertificate = resultWithSpinner(() -> iotClient().updateCertificate(updateCertificateRequest),
                format("certificate {0}", certificateArn.resourceAsString()), DEACTIVATE);

        DeleteCertificateRequest deleteCertificateRequest = DeleteCertificateRequest.builder()
                .certificateId(certificateArn.resource().resource())
                .build();

        // If the deactivation fails, this is a NOP but returns the expected Try type
        return tryUpdateCertificate.map(result -> deleteCertificate(certificateArn).get());
    }

    public static Try<DeleteCertificateResponse> deleteCertificate(Arn certificateArn) {
        DeleteCertificateRequest deleteCertificateRequest = DeleteCertificateRequest.builder()
                .certificateId(certificateArn.resource().resource())
                .build();

        // If the deactivation fails, this is a NOP but returns the expected Try type
        return resultWithSpinner(() -> iotClient().deleteCertificate(deleteCertificateRequest),
                format("certificate {0}", certificateArn.resourceAsString()), DELETE);
    }

    public static Try<Stream<String>> streamTargetsForIotPolicy(String iotPolicyName) {
        ListTargetsForPolicyRequest listTargetsForPolicyRequest = ListTargetsForPolicyRequest.builder()
                .policyName(iotPolicyName)
                .build();

        return resultStream(() -> iotClient().listTargetsForPolicyPaginator(listTargetsForPolicyRequest), ListTargetsForPolicyResponse::targets);
    }

    public static Try<List<String>> listTargetsForIotPolicy(String iotPolicyName) {
        return resultListWithSpinner(() -> streamTargetsForIotPolicy(iotPolicyName),
                "targets for IoT policy " + iotPolicyName, LIST);
    }
}
