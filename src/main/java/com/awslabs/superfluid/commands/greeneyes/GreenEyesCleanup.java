package com.awslabs.superfluid.commands.greeneyes;

import com.awslabs.superfluid.helpers.*;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.control.Try;
import picocli.CommandLine;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.services.greengrassv2.model.DeleteCoreDeviceResponse;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.DeleteRoleResponse;
import software.amazon.awssdk.services.iam.model.DetachRolePolicyResponse;
import software.amazon.awssdk.services.iot.model.*;
import software.amazon.awssdk.services.s3.model.DeleteBucketResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Scanner;

import static com.awslabs.superfluid.commands.greeneyes.Data.*;
import static com.awslabs.superfluid.helpers.Shared.print;
import static com.awslabs.superfluid.helpers.Shared.println;
import static java.text.MessageFormat.format;

@CommandLine.Command(name = "cleanup", mixinStandardHelpOptions = true)
public class GreenEyesCleanup implements Runnable {
    @CommandLine.Parameters(description = "The thing name of the Greengrass system", paramLabel = "thing-name")
    private String thingName;

    public static void tempRun() {
        new GreenEyesCleanup().run();
    }

    @Override
    public void run() {
        println("Analyzing Greengrass resources...");

        // List everything
        String bucketName = s3BucketName(thingName);
        String thingGroupName = thingGroupName(thingName);

        // List all the S3 objects we need to clean up
        Try<List<S3Object>> tryObjectList = S3Helper.listObjectsInBucket(bucketName);

        List<String> errorsToLog = List.empty();
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryObjectList), format("Failed to list the objects in the S3 bucket {0}", bucketName)));

        // List all the things in the thing group so we can sanity check that we're not deleting a group that is still in use
        Try<List<String>> tryThingsInThingGroupList = IotHelper.listThingsInThingGroup(thingGroupName);
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryThingsInThingGroupList), format("Failed to list the things in the thing group {0}", thingGroupName)));

        // List all the principals attached to the thing (we only handle certificates)
        Try<List<Arn>> tryListAttachedPrincipals = IotHelper.listPrincipalsAttachedToThing(thingName).map(list -> list.map(Arn::fromString));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryThingsInThingGroupList), format("Failed to list the principals attached to the thing {0}", thingName)));

        Try<List<Arn>> tryListAttachedCertificates = tryListAttachedPrincipals.map(IotHelper::getCertificatesFromPrincipalList);

        // List all the policies attached to the principals
        Try<List<Tuple2<Arn, List<Policy>>>> tryListAttachedIotPolicies = tryListAttachedPrincipals
                .map(principals -> principals.map(principal -> IotHelper.listAttachedPolicies(principal).get()));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryListAttachedIotPolicies), format("Failed to list the attached IoT policies")));

        Try<List<Policy>> tryListAbandonedIotPolicies = tryListAttachedIotPolicies
                // Just get the policies
                .map(list -> list.flatMap(tuple -> tuple._2))
                // Get only distinct policies by their names
                .map(list -> list.distinctBy(Policy::policyName))
                // Get the targets for each policy and catch failures
                .map(list -> list.map(policy -> Tuple.of(policy, IotHelper.listTargetsForIotPolicy(policy.policyName()).get())))
                // Any policy with one attachment will be abandoned
                .map(list -> list.filter(tuple -> tuple._2.size() == 1))
                // Just get the policies
                .map(list -> list.map(tuple -> tuple._1));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryListAbandonedIotPolicies), format("Failed to list the abandoned IoT policies")));

        // List all the IAM policies attached to the TES role
        Try<List<AttachedPolicy>> tryListAttachedRolePolicies = IamHelper.listAttachedRolePolicies(tesRoleName(thingName));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryListAttachedRolePolicies), format("Failed to list the IAM policies attached to the TES role {0}", tesRoleName(thingName))));

        // List all the IAM policies that will be abandoned after they're detached
        Try<List<software.amazon.awssdk.services.iam.model.Policy>> tryListAbandonedRolePolicies = tryListAttachedRolePolicies
                // Get the attachment count
                .map(rolePolicies -> rolePolicies.map(rolePolicy -> IamHelper.getIamPolicyAttachmentCount(rolePolicy).get()))
                // Any policy with one attachment will be abandoned
                .map(list -> list.filter(tuple -> tuple._2 == 1))
                // Just get the policies
                .map(list -> list.map(tuple -> tuple._1))
                .map(list -> list.map(attachedPolicy -> IamHelper.attachedPolicyToPolicy(attachedPolicy).get()));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryListAbandonedRolePolicies), format("Failed to list the abandoned IAM policies")));

        Try<RoleAliasDescription> tryRoleAliasDescription = IotHelper.describeRoleAlias(tesRoleAliasName(thingName));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryRoleAliasDescription), format("Failed to describe the TES role alias {0}", tesRoleAliasName(thingName))));

        // Log any errors and bail out if there are any
        if (logErrors(errorsToLog)) {
            println("Cannot continue when errors are present");
            println();
            System.exit(1);
        }

        displaySummaryOfOperations(bucketName,
                thingGroupName,
                tryObjectList,
                tryThingsInThingGroupList,
                tryListAttachedPrincipals,
                tryListAttachedCertificates,
                tryListAttachedIotPolicies,
                tryListAbandonedIotPolicies,
                tryListAttachedRolePolicies,
                tryListAbandonedRolePolicies,
                tryRoleAliasDescription);

        askToProceed();

        // Delete everything
        deleteEverything(bucketName, thingGroupName, tryObjectList, tryThingsInThingGroupList, tryListAttachedPrincipals, tryListAttachedCertificates, tryListAttachedIotPolicies, tryListAbandonedIotPolicies, tryListAttachedRolePolicies, tryListAbandonedRolePolicies);
    }

    private void deleteEverything(String bucketName,
                                  String thingGroupName,
                                  Try<List<S3Object>> tryObjectList,
                                  Try<List<String>> tryThingsInThingGroupList,
                                  Try<List<Arn>> tryListAttachedPrincipals,
                                  Try<List<Arn>> tryListAttachedCertificates,
                                  Try<List<Tuple2<Arn, List<Policy>>>> tryListAttachedIotPolicies,
                                  Try<List<Policy>> tryListAbandonedIotPolicies,
                                  Try<List<AttachedPolicy>> tryListAttachedRolePolicies,
                                  Try<List<software.amazon.awssdk.services.iam.model.Policy>> tryListAbandonedRolePolicies) {
        List<String> errorsToLog = List.empty();

        // Delete S3 objects
        if (isSuccessfulAndNonEmpty(tryObjectList)) {
            List<Try<DeleteObjectsResponse>> s3DeleteObjectsResults = S3Helper.deleteObjectsFromBucket(bucketName, tryObjectList.get());

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(s3DeleteObjectsResults), format("Failed to delete some objects in the S3 bucket {0}", bucketName)));
        }

        // Delete S3 bucket
        Try<DeleteBucketResponse> tryDeleteBucket = S3Helper.deleteBucket(bucketName);
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryDeleteBucket), format("Failed to delete the S3 bucket {0}", bucketName)));

        // Delete thing group
        if (willDeleteThingGroup(tryThingsInThingGroupList)) {
            Try<DeleteThingGroupResponse> tryDeleteThingGroup = IotHelper.deleteThingGroup(thingGroupName);

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryDeleteThingGroup), format("Failed to delete the thing group {0}", thingGroupName)));
        }

        // Detach principals from thing
        if (isSuccessfulAndNonEmpty(tryListAttachedPrincipals)) {
            List<Try<DetachThingPrincipalResponse>> tryDetachPrincipalFromThing = tryListAttachedPrincipals.get()
                    .map(principal -> IotHelper.detachPrincipalFromThing(thingName, principal));

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(tryDetachPrincipalFromThing), format("Failed to detach principal from thing {0}", thingName)));
        }

        // Detach policies from targets
        if (isSuccessfulAndNonEmpty(tryListAttachedIotPolicies)) {
            List<Try<DetachPolicyResponse>> tryDetachPolicyFromTarget = tryListAttachedIotPolicies.get()
                    .flatMap(tuple -> tuple._2.map(policy -> IotHelper.detachPolicyFromTarget(tuple._1, policy)));

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(tryDetachPolicyFromTarget), "Failed to detach policy from target"));
        }

        // Delete the certificates
        if (isSuccessfulAndNonEmpty(tryListAttachedCertificates)) {
            List<Try<DeleteCertificateResponse>> tryDeleteCertificate = tryListAttachedCertificates.get()
                    .map(IotHelper::purgeCertificate);

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(tryDeleteCertificate), "Failed to delete the certificates"));
        }

        // Delete the abandoned IoT policies
        if (isSuccessfulAndNonEmpty(tryListAbandonedIotPolicies)) {
            List<Try<DeletePolicyResponse>> tryDeletePolicy = tryListAbandonedIotPolicies.get()
                    .map(IotHelper::deletePolicy);

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(tryDeletePolicy), "Failed to delete the policies"));
        }

        // Detach the IAM policies from the TES role
        if (isSuccessfulAndNonEmpty(tryListAttachedRolePolicies)) {
            List<Try<DetachRolePolicyResponse>> tryDetachRolePolicy = tryListAttachedRolePolicies.get()
                    .map(attachedPolicy -> IamHelper.detachPolicyFromRole(attachedPolicy, tesRoleName(thingName)));

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(tryDetachRolePolicy), "Failed to detach the IAM role policies"));
        }

        // Delete the abandoned IAM policies
        if (isSuccessfulAndNonEmpty(tryListAbandonedRolePolicies)) {
            List<Try<software.amazon.awssdk.services.iam.model.DeletePolicyResponse>> tryDeletePolicies = tryListAbandonedRolePolicies.get()
                    .map(IamHelper::deletePolicy);

            errorsToLog = errorsToLog.appendAll(createErrorLogs(List.ofAll(tryDeletePolicies), "Failed to delete the IAM policies"));
        }

        // Delete the TES IAM role
        Try<DeleteRoleResponse> tryDeleteRole = IamHelper.deleteRole(tesRoleName(thingName));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryDeleteRole), format("Failed to delete the TES IAM role {0}", tesRoleName(thingName))));

        // Delete the role alias
        Try<DeleteRoleAliasResponse> tryDeleteRoleAlias = IotHelper.deleteRoleAlias(tesRoleAliasName(thingName));
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryDeleteRoleAlias), format("Failed to delete the TES IoT role alias {0}", tesRoleAliasName(thingName))));

        // Delete the Greengrass core device
        Try<DeleteCoreDeviceResponse> tryDeleteCoreDevice = GreengrassHelper.deleteCoreDevice(thingName);
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryDeleteCoreDevice), format("Failed to delete the Greengrass core device {0}", thingName)));

        // Delete the IoT thing
        Try<DeleteThingResponse> tryDeleteThing = IotHelper.deleteThing(thingName);
        errorsToLog = errorsToLog.appendAll(createErrorLogs(List.of(tryDeleteThing), format("Failed to delete the thing {0}", thingName)));

        // Log any errors
        logErrors(errorsToLog);
    }

    private boolean logErrors(List<String> errorsToLog) {
        if (errorsToLog.isEmpty()) {
            return false;
        }

        println();
        println("The following errors occurred");
        println();
        errorsToLog.forEach(Shared::println);

        return true;
    }

    private <T> boolean isSuccessfulAndNonEmpty(Try<List<T>> tryList) {
        return tryList.isSuccess() && tryList.get().size() > 0;
    }

    private boolean containsFailures(List<Try> tryList) {
        return tryList.count(Try::isFailure) != 0;
    }

    private void askToProceed() {
        print("Would you like to proceed? (y/n) ");
        Scanner scanner = new Scanner(System.in);

        String line = scanner.nextLine();

        if (!line.equals("y")) {
            println("User response was not 'y', exiting");
            System.exit(0);
        }
    }

    private void displaySummaryOfOperations(String bucketName,
                                            String thingGroupName,
                                            Try<List<S3Object>> tryObjectList,
                                            Try<List<String>> tryThingsInThingGroupList,
                                            Try<List<Arn>> tryListAttachedPrincipals,
                                            Try<List<Arn>> tryListAttachedCertificates,
                                            Try<List<Tuple2<Arn, List<Policy>>>> tryListAttachedIotPolicies,
                                            Try<List<Policy>> tryListAbandonedIotPolicies,
                                            Try<List<AttachedPolicy>> tryListAttachedRolePolicies,
                                            Try<List<software.amazon.awssdk.services.iam.model.Policy>> tryListAbandonedRolePolicies,
                                            Try<RoleAliasDescription> tryRoleAliasDescription) {
        println();
        println("This process will do the following operations");
        println();

        if (tryObjectList.isSuccess() && tryObjectList.get().size() > 0) {
            println("- Delete {} object(s) in the S3 bucket {}", tryObjectList.get().size(), bucketName);
            println();
        }

        println("- Delete the S3 bucket {}", bucketName);
        println();

        if (willDeleteThingGroup(tryThingsInThingGroupList)) {
            // We will not delete the thing group if there are other things in it
            println("- Delete the thing group {}", thingGroupName);
            println();
        }

        if (isSuccessfulAndNonEmpty(tryListAttachedPrincipals)) {
            println("- Detach the following principals from the thing {}", thingName);
            tryListAttachedPrincipals.get().forEach(principal -> println("  - {}", principal.resourceAsString()));
            println();
        }

        if (tryListAttachedIotPolicies.isSuccess()) {
            tryListAttachedIotPolicies.get().forEach(this::printTargetAndPolicies);
        }

        if (isSuccessfulAndNonEmpty(tryListAttachedCertificates)) {
            println("- Delete the following certificates");
            tryListAttachedCertificates.get().forEach(certificate -> println("  - {}", certificate.resourceAsString()));
            println();
        }

        if (tryListAbandonedIotPolicies.isSuccess() && tryListAbandonedIotPolicies.get().size() > 0) {
            println("- Delete the following IoT policies");
            tryListAbandonedIotPolicies.get().forEach(policy -> println("  - {}", policy.policyName()));
            println();
        }

        if (tryListAttachedRolePolicies.isSuccess() && tryListAttachedRolePolicies.get().size() > 0) {
            println("- Detach the following IAM policies from the TES role {}", tesRoleName(thingName));
            tryListAttachedRolePolicies.get().forEach(policy -> println("  - {}", policy.policyName()));
            println();
        }

        if (tryListAbandonedRolePolicies.isSuccess() && tryListAbandonedRolePolicies.get().size() > 0) {
            println("- Delete the following IAM policies");
            tryListAbandonedRolePolicies.get().forEach(policy -> println("  - {}", policy.policyName()));
            println();
        }

        if (tryRoleAliasDescription.isSuccess()) {
            println("- Delete the IAM role for TES {}", tesRoleName(thingName));
            println();
            println("- Delete the role alias {}", tesRoleAliasName(thingName));
            println();
        }

        println("- Delete the Greengrass core device {}", thingName);
        println();

        println("- Delete the thing {}", thingName);
        println();
    }

    private boolean willDeleteThingGroup(Try<List<String>> tryThingsInThingGroupList) {
        return tryThingsInThingGroupList.isSuccess() && tryThingsInThingGroupList.get().size() == 1;
    }

    private List<Throwable> getFailures(List<Try> tries) {
        return tries.filter(Try::isFailure)
                .map(Try::getCause);
    }

    private List<String> createErrorLogs(List<Try> tries, String message) {
        if (tries.filter(Try::isFailure).isEmpty()) {
            return List.empty();
        }

        List<String> messages = List.empty();

        if (!message.isEmpty()) messages = messages.append(format("- {0}", message));

        messages = messages.appendAll(getFailures(tries)
                .map(failure -> format("  - {0}\n", failure.getMessage())));

        return messages;
    }

    private void printTargetAndPolicies(Tuple2<Arn, List<Policy>> tuple) {
        println("- Detach the following policies from the target {}", tuple._1.resourceAsString());
        tuple._2.forEach(policy -> println("  - {}", policy.policyName()));
        println();
    }
}

        /*
        boolean failure = false;
        if (tryObjectList.isFailure()) {
            println("Failed to list objects in bucket {}", bucketName);
            failure = true;
        } else {
            println("Listed objects in bucket {}", bucketName);
        }

        if (tryThingGroupList.isFailure()) {
            println("Failed to list things in group {}", thingGroupName);
            failure = true;
        } else {
            println("Listed things in group {}", thingGroupName);
        }

        if (tryListAttachedPrincipals.isFailure()) {
            println("Failed to list principals attached to thing {}", thingName);
            failure = true;
        } else {
            println("Listed principals attached to thing {}", thingName);

            if (IotHelper.containsNonCertificatePrincipals(tryListAttachedPrincipals.get())) {
                println("Non-certificate principals found");
                failure = true;
            }
        }

        if (failure) {
            println("Failures detected, cannot continue");
            return;
        }
         */
