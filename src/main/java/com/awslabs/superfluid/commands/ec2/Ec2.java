package com.awslabs.superfluid.commands.ec2;

import com.awslabs.superfluid.App;
import com.awslabs.superfluid.helpers.Shared;
import io.vavr.collection.Stream;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;

import static com.awslabs.superfluid.helpers.Shared.ec2Client;
import static software.amazon.awssdk.services.ec2.model.InstanceStateName.*;

@CommandLine.Command(name = "ec2", mixinStandardHelpOptions = true,
        subcommands = {Ec2Start.class, Ec2List.class, Ec2Terminate.class})
public class Ec2 {
    public static final String INSTANCE_STATE_NAME = "instance-state-name";
    private static final String RUNNING_VALUE = RUNNING.toString();
    private static final String STOPPED_VALUE = STOPPED.toString();
    private static final String STOPPING_VALUE = STOPPING.toString();
    private static final String SHUTTING_DOWN_VALUE = SHUTTING_DOWN.toString();
    private static final Filter RUNNING_FILTER = Filter.builder().name(INSTANCE_STATE_NAME).values(RUNNING_VALUE).build();
    private static final Filter STOPPED_FILTER = Filter.builder().name(INSTANCE_STATE_NAME).values(STOPPED_VALUE).build();
    private static final Filter STOPPING_FILTER = Filter.builder().name(INSTANCE_STATE_NAME).values(STOPPING_VALUE).build();
    private static final Filter SHUTTING_DOWN_FILTER = Filter.builder().name(INSTANCE_STATE_NAME).values(SHUTTING_DOWN_VALUE).build();

    // Shared with sub-commands via CommandLine.ScopeType.INHERIT
    @CommandLine.Option(names = "-v", scope = CommandLine.ScopeType.INHERIT)
    public void setVerbose(boolean[] verbose) {
        Shared.setVerbose(verbose);
    }

    private static Stream<Instance> getRunningInstances() {
        return getInstancesLaunchedByTool(RUNNING_FILTER);
    }

    private static Stream<Instance> getStoppedInstances() {
        return getInstancesLaunchedByTool(STOPPED_FILTER);
    }

    private static Stream<Instance> getStoppingInstances() {
        return getInstancesLaunchedByTool(STOPPING_FILTER);
    }

    private static Stream<Instance> getShuttingDownInstances() {
        return getInstancesLaunchedByTool(SHUTTING_DOWN_FILTER);
    }

    public static Stream<Instance> getNotTerminatedInstances() {
        Filter stoppedFilter = Filter.builder().name(INSTANCE_STATE_NAME)
                .values(RUNNING_VALUE, STOPPED_VALUE, STOPPING_VALUE, SHUTTING_DOWN_VALUE).build();
        return getInstancesLaunchedByTool(stoppedFilter);
    }

    private static Stream<Instance> getInstancesLaunchedByTool(Filter filter) {
        Filter launchedByToolFilter = getTagFilter(App.TOOL_NAME, App.TOOL_NAME);

        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder()
                .filters(filter, launchedByToolFilter)
                .build();

        DescribeInstancesIterable describeInstancesIterable = ec2Client().describeInstancesPaginator(describeInstancesRequest);

        return Stream.ofAll(describeInstancesIterable.stream())
                .flatMap(DescribeInstancesResponse::reservations)
                .flatMap(Reservation::instances);
    }

    private static Filter getTagFilter(String name, String value) {
        return Filter.builder()
                .name(getTagName(name))
                .values(value)
                .build();
    }

    @NotNull
    private static String getTagName(String tagName) {
        return String.join(":", "tag", tagName);
    }
}
