package com.awslabs.superfluid.commands.greengrass.local;

import com.awslabs.superfluid.helpers.Shared;
import io.vavr.Tuple;
import io.vavr.collection.List;
import io.vavr.collection.Stream;
import io.vavr.control.Try;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.awslabs.superfluid.commands.greengrass.local.GreengrassLocal.*;
import static com.awslabs.superfluid.helpers.Shared.println;

@CommandLine.Command(name = "deployment-info", mixinStandardHelpOptions = true)
public class GreengrassDeploymentInfo implements Runnable {
    public static final String CURRENT_GREENGRASS_LOG = "greengrass.log";
    @CommandLine.Parameters(description = "The deployment ID to get info about", paramLabel = "deployment-id")
    private String deploymentId;
    @CommandLine.Option(names = "--terse", description = "Print terse output")
    private boolean terse;

    @Override
    public void run() {
        showUsernameWarningIfNecessary();

        Path logsPath = getGgcRootPath().resolve("logs");

        List<Path> logFilePaths = Try.of(() -> Files.walk(logsPath, 1))
                .map(io.vavr.collection.Stream::ofAll)
                .getOrElse(io.vavr.collection.Stream.empty())
                .map(path -> Tuple.of(getSimpleFilename(path), path))
                .filter(tuple -> tuple._1.startsWith("greengrass"))
                .filter(tuple -> tuple._1.endsWith("log"))
                .map(tuple -> tuple._2)
                .sorted(this::compareLogPaths)
                .toList();

        if (logFilePaths.isEmpty()) {
            println("No log files found.");
            return;
        }

        Number totalLogSize = logFilePaths.map(Path::toFile)
                .map(File::length)
                .sum();

        if (totalLogSize.longValue() > (100 * MEGABYTES)) {
            println("{} The total size of the logs is {} bytes. The results may take a long time to come back.", WARNING_PREFIX, totalLogSize);
        }

        println("Examining {} log file(s)", logFilePaths.size());

        Stream<Try<Stream<String>>> lineStreamTry = logFilePaths.toStream()
                .map(GreengrassLocal::tryReadFile);

        Stream<Try<Stream<String>>> lineStreamFailures = lineStreamTry.filter(Try::isFailure);

        if (lineStreamFailures.nonEmpty()) {
            println("Failed to read the following log file(s):");

            lineStreamFailures
                    .map(Try::getCause)
                    .forEach(throwable -> println(throwable.getMessage()));

            return;
        }

        // Get all the log streams and flatten them into a single stream of strings
        Stream<String> linesToPrint = lineStreamTry.flatMap(Try::get)
                // Find the first line that contains the deployment ID
                .dropUntil(line -> line.contains(deploymentId))
                // Find the last line that contains the deployment ID
                .reverse().dropUntil(line -> line.contains(deploymentId))
                // Go back to the normal order
                .reverse()
                // Skip empty lines
                .filter(line -> !line.isEmpty());

        if (terse) {
            linesToPrint = linesToPrint.filter(line -> !isStackTraceLine(line));
        }

        // Print the relevant log lines
        linesToPrint.forEach(Shared::println);
    }

    private boolean isStackTraceLine(String line) {
        return line.matches("\\s*at .*(.*\\.java:\\d+).*");
    }

    private int compareLogPaths(Path path1, Path path2) {
        if (path1.getFileName().toString().endsWith(CURRENT_GREENGRASS_LOG)) {
            // If path1 is the current greengrass.log, path2 should be first
            return Integer.MAX_VALUE;
        }

        if (path2.getFileName().toString().endsWith(CURRENT_GREENGRASS_LOG)) {
            // If path2 is the current greengrass.log, path1 should be first
            return Integer.MIN_VALUE;
        }

        // If neither is the current greengrass.log then do a normal comparison
        return path1.compareTo(path2);
    }
}
