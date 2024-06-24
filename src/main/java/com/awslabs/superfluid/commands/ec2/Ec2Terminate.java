package com.awslabs.superfluid.commands.ec2;

import io.vavr.collection.List;
import io.vavr.collection.Stream;
import io.vavr.control.Option;
import io.vavr.control.Try;
import picocli.CommandLine;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateChange;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesResponse;

import static com.awslabs.superfluid.helpers.Shared.*;

@CommandLine.Command(name = "terminate", mixinStandardHelpOptions = true)
public class Ec2Terminate implements Runnable {
    private static final int MINIMUM_LENGTH = 6;
    @CommandLine.Parameters(description = "Instance ID, partial ID, or public IP address to terminate", paramLabel = "id")
    private String id;

    @Override
    public void run() {
        if ((id == null) || id.isEmpty()) {
            println("No instance ID provided.");
            return;
        }

        // Make sure they use at least six characters, otherwise a user can shoot themselves in the foot
        if (id.length() < MINIMUM_LENGTH) {
            println("Instance ID must be at least {} characters long to be safe", MINIMUM_LENGTH);
            return;
        }

        Stream<Instance> instances = Ec2.getNotTerminatedInstances();

        Stream<Instance> idMatches = instances.filter(instance -> instance.instanceId().contains(id));

        Option<Try<TerminateInstancesResponse>> terminatedByIdMatchOption = idMatches
                .singleOption()
                .map(this::terminate);

        if (terminatedByIdMatchOption.isDefined()) {
            // Termination attempted
            logTermination(terminatedByIdMatchOption.get());

            return;
        }

        if (idMatches.size() > 1) {
            println("Multiple instances matched the partial ID provided:");
            idMatches.forEach(instance -> println(" - {}", instance.instanceId()));
            return;
        }

        Option<Try<TerminateInstancesResponse>> terminatedByIpAddressMatchOption = instances.filter(instance -> instance.publicIpAddress().equals(id))
                .singleOption()
                .map(this::terminate);

        if (terminatedByIpAddressMatchOption.isDefined()) {
            // Termination attempted
            logTermination(terminatedByIpAddressMatchOption.get());

            return;
        }

        println("No instances matched the ID provided.");
    }

    private void logTermination(Try<TerminateInstancesResponse> terminateInstancesResponseTry) {
        if (terminateInstancesResponseTry.isFailure()) {
            log().error("Unable to terminate instance {} [{}]", id, terminateInstancesResponseTry.getCause());
            return;
        }

        TerminateInstancesResponse terminateInstancesResponse = terminateInstancesResponseTry.get();
        getListOfTerminatedInstances(terminateInstancesResponse)
                .forEach(instanceId -> println("Terminated instance {}", instanceId));
    }

    private List<String> getListOfTerminatedInstances(TerminateInstancesResponse terminateInstancesResponse) {
        return Stream.of(terminateInstancesResponse.terminatingInstances())
                .flatMap(Stream::ofAll)
                .map(InstanceStateChange::instanceId)
                .toList();
    }

    private Try<TerminateInstancesResponse> terminate(Instance instance) {
        TerminateInstancesRequest terminateInstancesRequest = TerminateInstancesRequest.builder()
                .instanceIds(instance.instanceId())
                .build();

        return Try.of(() -> ec2Client().terminateInstances(terminateInstancesRequest));
    }
}
