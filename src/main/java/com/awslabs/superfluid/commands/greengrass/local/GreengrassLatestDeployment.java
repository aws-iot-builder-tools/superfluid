package com.awslabs.superfluid.commands.greengrass.local;

import com.awslabs.superfluid.helpers.files.SafeVisitor;
import io.vavr.control.Option;
import io.vavr.control.Try;
import picocli.CommandLine;

import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static com.awslabs.superfluid.commands.greengrass.local.GreengrassLocal.*;
import static com.awslabs.superfluid.helpers.Shared.println;

@CommandLine.Command(name = "latest-deployment", mixinStandardHelpOptions = true)
public class GreengrassLatestDeployment implements Runnable {
    private Option<Path> previousSuccessOption = Option.none();
    private Option<Path> currentDeploymentOption = Option.none();

    @Override
    public void run() {
        showUsernameWarningIfNecessary();

        SafeVisitor safeVisitor = new SafeVisitor();
        safeVisitor.visitFile(this::checkDeployment);
        safeVisitor.preVisitDirectory(this::checkDeployment);

        Path ggcRootPath = getGgcRootPath();

        Try<Path> pathTry = safeVisitor.walk(ggcRootPath);

        if (pathTry.isFailure()) {
            pathTry.getCause().printStackTrace();
            return;
        }

        if (previousSuccessOption.isEmpty()) {
            println("No previous success found. Greengrass may not have any valid deployments.");
        } else {
            println("Previous successful deployment found at {}", previousSuccessOption.get());
        }

        if (currentDeploymentOption.isEmpty()) {
            println("No current deployment found. Greengrass may not have any valid deployments.");
        } else {
            println("Current deployment found at {}", currentDeploymentOption.get());
        }

        if (previousSuccessOption.isEmpty() || currentDeploymentOption.isEmpty()) {
            return;
        }

        String previousSuccess = getSimpleFilename(Try.of(() -> previousSuccessOption.get().toRealPath()).get());
        String currentDeployment = getSimpleFilename(currentDeploymentOption.get());

        if (previousSuccess.equals(currentDeployment)) {
            println("The current deployment is {} and it was successful", currentDeployment);
            return;
        }

        println("The current deployment is {}", currentDeployment);
        println("The last successful deployment was {}", previousSuccess);
    }

    private FileVisitResult checkDeployment(Path path, BasicFileAttributes basicFileAttributes) {
        Option<String> parentNameOption = Option.of(path)
                .map(Path::getParent)
                // See http://blog.vavr.io/the-agonizing-death-of-an-astronaut/ for an explanation of this flatMap
                .flatMap(s -> Option.of(s).map(Path::getFileName))
                .map(Path::toString);

        if (parentNameOption.isEmpty()) {
            return FileVisitResult.CONTINUE;
        }

        String parentName = parentNameOption.get();

        if (!parentName.equals("deployments")) {
            return FileVisitResult.CONTINUE;
        }

        String tempFilename = getSimpleFilename(path);

        if (tempFilename.equals("previous-success")) {
            previousSuccessOption = Option.of(path);
        } else {
            currentDeploymentOption = Option.of(path);
        }

        if (previousSuccessOption.isDefined() && currentDeploymentOption.isDefined()) {
            return FileVisitResult.TERMINATE;
        }

        return FileVisitResult.CONTINUE;
    }
}
