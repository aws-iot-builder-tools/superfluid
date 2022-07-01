package com.awslabs.superfluid.commands.ec2;

import com.awslabs.superfluid.helpers.Shared;
import com.awslabs.superfluid.helpers.ConsoleStringTable;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Stream;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import picocli.CommandLine;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;

import static com.awslabs.superfluid.helpers.Shared.*;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true)
public class Ec2List implements Runnable {
    @Override
    public void run() {
        Stream<Instance> instances = Ec2.getNotTerminatedInstances();

        Stream<String> imageIds = instances.map(Instance::imageId)
                .distinct();

        HashMap<String, String> imageIdToDescription = HashMap.ofEntries(associateImageIdsWithDescriptions(imageIds.toList()));

        List<List<Tuple2<String, String>>> instanceTuples = instances.map(instance -> instanceToTuples(instance, imageIdToDescription.get(instance.imageId())))
                .toList();

        if (instanceTuples.isEmpty()) {
            println("No instances that were launched by this tool were found in the {} region", Shared.region());
            return;
        }

        ConsoleStringTable consoleStringTable = new ConsoleStringTable();
        buildTable(consoleStringTable, instanceTuples);
        println(consoleStringTable.getTableAsString(Either.right(" | ")));
    }

    private void buildTable(ConsoleStringTable consoleStringTable, List<List<Tuple2<String, String>>> instanceTuples) {
        // Extract the headers that are in the tuples
        List<String> header = instanceTuples.get().map(Tuple2::_1);

        // Create a list of blank strings that can be used under the header as a spacer
        List<String> spacer = instanceTuples.get().map(tuple -> "");

        // Remove the headers from each tuple so we get just the values
        List<List<String>> data = instanceTuples.map(tuple -> tuple.map(Tuple2::_2));

        // Make a list of the header, spacer, and data
        List<List<String>> combinedData = data.prepend(spacer).prepend(header);

        // Add column numbers to each column (map iterates over the rows, zipWithIndex on each row adds the column numbers)
        combinedData.map(row -> row.zipWithIndex((columnData, columnNumber) -> Tuple.of(columnNumber, columnData)))
                // Add row numbers to each row (zipWithIndex gives us the row numbers)
                .zipWithIndex((rowData, rowNumber) -> rowData.map(tuple -> Tuple.of(rowNumber, tuple._1(), tuple._2())))
                // Flatten the data structure into a list of tuples since each tuple contains its row and column number
                .flatMap(List::ofAll)
                // Add the data to the table
                .forEach(tuple -> consoleStringTable.addString(tuple._1(), tuple._2(), tuple._3()));
    }

    private List<Tuple2<String, String>> associateImageIdsWithDescriptions(List<String> imageIds) {
        if (imageIds.isEmpty()) {
            return List.empty();
        }

        DescribeImagesRequest describeImagesRequest = DescribeImagesRequest.builder()
                .imageIds(imageIds.asJava())
                .build();

        Try<DescribeImagesResponse> describeImagesResponseTry = Try.of(() -> ec2Client().describeImages(describeImagesRequest));

        if (describeImagesResponseTry.isFailure()) {
            log().warn("Failed to retrieve EC2 AMI descriptions. This may be a temporary failure or the current user may not have permission to access the DescribeImages API.");
            log().warn("  DescribeImages API reference - https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeImages.html");
            log().warn("");
            log().warn("  Exception message: {}", describeImagesResponseTry.getCause().getMessage());

            return List.empty();
        }

        return Stream.of(describeImagesResponseTry.get())
                .flatMap(DescribeImagesResponse::images)
                .map(image -> Tuple.of(image.imageId(), image.description()))
                .toList();
    }

    private List<Tuple2<String, String>> instanceToTuples(Instance instance, Option<String> imageDescriptionOption) {
        return List.of(
                Tuple.of("Instance ID", instance.instanceId()),
                Tuple.of("State", instance.state().name().name()),
                Tuple.of("Public IP Address", instance.publicIpAddress()),
                Tuple.of("Private IP Address", instance.privateIpAddress()),
                Tuple.of("Instance Type", instance.instanceTypeAsString()),
                Tuple.of("Architecture", instance.architectureAsString()),
                Tuple.of("Image ID", instance.imageId()),
                Tuple.of("Image Description", imageDescriptionOption.getOrElse("No description"))
        )
                // Make sure any NULL values in the second field get remapped to "N/A"
                .map(tuple -> tuple.map2(value -> Option.of(value).getOrElse("N/A")));
    }
}
