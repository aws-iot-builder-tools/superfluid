package com.awslabs.superfluid.helpers;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.control.Try;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.services.iam.model.*;

import java.util.stream.Stream;

import static com.awslabs.superfluid.helpers.AwsSdkHelper.*;
import static com.awslabs.superfluid.helpers.Shared.iamClient;

public class IamHelper {
    public static boolean roleExists(String roleName) {
        GetRoleRequest getRoleRequest = GetRoleRequest.builder().roleName(roleName).build();

        return Try.of(() -> iamClient().getRole(getRoleRequest))
                // No exceptions, it exists
                .map(value -> true)
                // No such entity exception, it doesn't exist
                .recover(NoSuchEntityException.class, e -> false)
                // Some other exception, throw it
                .get();
    }

    public static Try<Stream<AttachedPolicy>> streamAttachedRolePolicies(String roleName) {
        ListAttachedRolePoliciesRequest listAttachedRolePoliciesRequest = ListAttachedRolePoliciesRequest.builder()
                .roleName(roleName)
                .build();

        return resultStream(() -> iamClient().listAttachedRolePoliciesPaginator(listAttachedRolePoliciesRequest), ListAttachedRolePoliciesResponse::attachedPolicies);
    }

    public static Try<List<AttachedPolicy>> listAttachedRolePolicies(String roleName) {
        return resultListWithSpinner(() -> streamAttachedRolePolicies(roleName), "policies attached to role " + roleName, LIST);
    }

    public static Try<Tuple2<AttachedPolicy, Integer>> getIamPolicyAttachmentCount(AttachedPolicy iamPolicy) {
        GetPolicyRequest getPolicyRequest = GetPolicyRequest.builder()
                .policyArn(iamPolicy.policyArn())
                .build();

        return resultWithSpinner(() -> iamClient().getPolicy(getPolicyRequest),
                result -> Tuple.of(iamPolicy, result.policy().attachmentCount()),
                "policy " + Arn.fromString(iamPolicy.policyArn()).resourceAsString(), DESCRIBE);
    }

    public static Try<Policy> attachedPolicyToPolicy(AttachedPolicy iamPolicy) {
        GetPolicyRequest getPolicyRequest = GetPolicyRequest.builder()
                .policyArn(iamPolicy.policyArn())
                .build();

        return resultWithSpinner(() -> iamClient().getPolicy(getPolicyRequest),
                GetPolicyResponse::policy,
                "policy " + Arn.fromString(iamPolicy.policyArn()).resourceAsString(), CONVERT);
    }

    public static Try<DetachRolePolicyResponse> detachPolicyFromRole(AttachedPolicy iamPolicy, String roleName) {
        DetachRolePolicyRequest detachRolePolicyRequest = DetachRolePolicyRequest.builder()
                .policyArn(iamPolicy.policyArn())
                .roleName(roleName)
                .build();

        return resultWithSpinner(() -> iamClient().detachRolePolicy(detachRolePolicyRequest),
                "policy " + iamPolicy.policyArn(), DETACH);
    }

    public static Try<DeletePolicyResponse> deletePolicy(Policy iamPolicy) {
        DeletePolicyRequest deletePolicyRequest = DeletePolicyRequest.builder()
                .policyArn(iamPolicy.arn())
                .build();

        return resultWithSpinner(() -> iamClient().deletePolicy(deletePolicyRequest),
                "policy " + iamPolicy.policyName(), DELETE);
    }

    public static Try<DeleteRoleResponse> deleteRole(String roleName) {
        DeleteRoleRequest deleteRoleRequest = DeleteRoleRequest.builder()
                .roleName(roleName)
                .build();

        return resultWithSpinner(() -> iamClient().deleteRole(deleteRoleRequest),
                "role " + roleName, DELETE);
    }
}
