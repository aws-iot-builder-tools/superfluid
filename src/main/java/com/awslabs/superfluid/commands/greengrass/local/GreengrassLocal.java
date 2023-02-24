package com.awslabs.superfluid.commands.greengrass.local;

import com.awslabs.superfluid.helpers.Shared;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.Stream;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.awslabs.superfluid.helpers.Shared.println;

@CommandLine.Command(name = "ggl", mixinStandardHelpOptions = true,
        subcommands = {GreengrassLatestDeployment.class, GreengrassDeploymentInfo.class})
public class GreengrassLocal {
    public static final String WARNING_PREFIX = "WARNING! WARNING!";
    public static final long MEGABYTES = 1024 * 1024;
    public static final long GIGABYTES = MEGABYTES * 1024;

    // Shared with sub-commands via CommandLine.ScopeType.INHERIT
    @CommandLine.Option(names = "-v", scope = CommandLine.ScopeType.INHERIT)
    public void setVerbose(boolean[] verbose) {
        Shared.setVerbose(verbose);
    }

    private static boolean showedUsernameWarning = false;

    @NotNull
    protected static Path getGgcRootPath() {
        return getGgcRootPath(false);
    }

    @NotNull
    protected static Path getGgcRootPath(boolean quiet) {
        Option<Path> ggcRootPathOption = Option.of(System.getenv("GGC_ROOT_PATH"))
                .map(Path::of);

        Path ggcRootPath;

        if (ggcRootPathOption.isEmpty()) {
            ggcRootPath = Path.of(".");
            if (!quiet) println("{} GGC_ROOT_PATH was not set. ", WARNING_PREFIX);
        } else {
            ggcRootPath = ggcRootPathOption.get();
            if (!quiet) println("GGC_ROOT_PATH is set. ");
        }

        if (!quiet) println("Looking in {}", ggcRootPath.toAbsolutePath());

        return ggcRootPath;
    }

    protected static void showUsernameWarningIfNecessary() {
        Option<String> usernameOption = Option.of(System.getProperty("user.name"));

        if (showedUsernameWarning) {
            return;
        }

        showedUsernameWarning = true;

        if (usernameOption.isEmpty()) {
            println("{} Username was empty. Greengrass deployment information may not be available.", WARNING_PREFIX);
        }

        if (usernameOption.isDefined() && !usernameOption.get().equals("root")) {
            println("{} User is not root. Greengrass deployment information may not be available.", WARNING_PREFIX);
        }
    }

    protected static String getSimpleFilename(Path path) {
        Path temp = path.getFileName();
        return temp.getName(temp.getNameCount() - 1).toString();
    }

    protected static Try<Stream<String>> tryReadFile(Path path) {
        Tuple2<Path, Try<Stream<String>>> readAttempt = Tuple.of(path, Try.of(() -> Stream.ofAll(Files.lines(path))));

        if (readAttempt._2().isFailure()) {
            Throwable throwable = readAttempt._2().getCause();
            return Try.failure(new RuntimeException("Failed to read file: " + readAttempt._1().toAbsolutePath(), throwable));
        }

        return Try.success(readAttempt._2().get());
    }
}
