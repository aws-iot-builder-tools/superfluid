package com.awslabs.superfluid.commands.greengrass.cloud;

import io.vavr.collection.Stream;
import picocli.CommandLine;
import software.amazon.awssdk.services.greengrassv2.model.Deployment;
import software.amazon.awssdk.services.greengrassv2.model.ListDeploymentsRequest;
import software.amazon.awssdk.services.greengrassv2.model.ListDeploymentsResponse;
import software.amazon.awssdk.services.greengrassv2.paginators.ListDeploymentsIterable;

import static com.awslabs.superfluid.helpers.Shared.greengrassV2Client;
import static com.awslabs.superfluid.helpers.Shared.println;

@CommandLine.Command(name = "show-deployment", mixinStandardHelpOptions = true)
public class GreengrassShowDeployment implements Runnable {
    @CommandLine.Parameters(description = "The name of the thing to show the current deployment for", paramLabel = "thingName")
    private String thingName;

    @Override
    public void run() {
        ListDeploymentsRequest listDeploymentsRequest = ListDeploymentsRequest.builder().build();
        ListDeploymentsIterable listDeploymentsIterable = greengrassV2Client().listDeploymentsPaginator(listDeploymentsRequest);

        println("Latest deployment: {}", Stream.ofAll(listDeploymentsIterable.stream())
                .flatMap(ListDeploymentsResponse::deployments)
                .filter(deployment -> deployment.targetArn().contains(thingName))
                .filter(Deployment::isLatestForTarget)
                .get());
    }
}
