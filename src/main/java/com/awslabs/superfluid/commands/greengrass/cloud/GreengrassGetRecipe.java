package com.awslabs.superfluid.commands.greengrass.cloud;

import com.awslabs.superfluid.helpers.GsonHelper;
import io.vavr.control.Try;
import picocli.CommandLine;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentRequest;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentResponse;
import software.amazon.awssdk.services.greengrassv2.model.RecipeOutputFormat;

import static com.awslabs.superfluid.helpers.Shared.*;

@CommandLine.Command(name = "get-recipe", mixinStandardHelpOptions = true)
public class GreengrassGetRecipe implements Runnable {
    @CommandLine.Parameters(description = "The ARN of the component", paramLabel = "arn")
    private String arn;

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
    ExclusiveOutput exclusiveOutput;

    class ExclusiveOutput {
        @CommandLine.Option(names = "--json", required = true)
        boolean json;
        @CommandLine.Option(names = "--yaml", required = true)
        boolean yaml;
    }

    @Override
    public void run() {
        RecipeOutputFormat recipeOutputFormat;

        if ((exclusiveOutput == null) || exclusiveOutput.json) {
            recipeOutputFormat = RecipeOutputFormat.JSON;
        } else {
            recipeOutputFormat = RecipeOutputFormat.YAML;
        }

        GetComponentRequest getComponentRequest = GetComponentRequest.builder()
                .arn(arn)
                .recipeOutputFormat(recipeOutputFormat)
                .build();

        Try<GetComponentResponse> getComponentResponseTry = Try.of(() -> greengrassV2Client().getComponent(getComponentRequest));

        if (getComponentResponseTry.isFailure()) {
            log().warn("Could not find Greengrass component with ARN {}", arn);
            log().warn("");
            log().warn("  Exception message: {}", getComponentResponseTry.getCause().getMessage());
            System.exit(1);
        }

        GetComponentResponse getComponentResponse = getComponentResponseTry.get();

        if (RecipeOutputFormat.JSON.equals(recipeOutputFormat)) {
            String recipeJson = getComponentResponse.recipe().asUtf8String();
            String prettyPrintJson = GsonHelper.reformatJson(recipeJson);
            println("{}", prettyPrintJson);
        }
    }
}
