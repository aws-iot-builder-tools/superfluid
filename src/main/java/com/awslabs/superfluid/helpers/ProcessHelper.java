package com.awslabs.superfluid.helpers;

import com.awslabs.superfluid.data.ProcessOutput;
import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.apache.commons.lang3.SystemUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import static com.awslabs.superfluid.helpers.Shared.log;

public class ProcessHelper {
    public static ProcessBuilder getProcessBuilder(List<String> programAndArguments) {
        List<String> output = List.empty();

        if (SystemUtils.IS_OS_WINDOWS) {
            output = List.of("cmd.exe", "/C");
        }

        output = output.appendAll(programAndArguments);

        return new ProcessBuilder(output.toJavaList());
    }

    public static Option<ProcessOutput> getOutputFromProcess(List<String> commandAndArguments) {
        return Try.of(() -> getProcessBuilder(commandAndArguments))
                .mapTry(ProcessHelper::innerGetOutputFromProcess)
                .recover(Exception.class, ProcessHelper::logExceptionMessageAndReturnEmpty)
                .get();
    }

    public static Option<ProcessOutput> getOutputFromProcess(ProcessBuilder processBuilder) {
        return Try.of(() -> innerGetOutputFromProcess(processBuilder))
                .recover(Exception.class, ProcessHelper::logExceptionMessageAndReturnEmpty)
                .get();
    }

    private static Option<ProcessOutput> logExceptionMessageAndReturnEmpty(Exception throwable) {
        log().error(throwable.getMessage());

        return Option.none();
    }

    private static Option<ProcessOutput> innerGetOutputFromProcess(ProcessBuilder processBuilder) throws IOException, InterruptedException {
        Process process = processBuilder.start();

        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        // Wait for the process to exit
        process.waitFor();

        ProcessOutput processOutput = ProcessOutput.builder()
                .exitCode(process.exitValue())
                .standardErrorStrings(stderr.lines().collect(List.collector()))
                .standardOutStrings(stdout.lines().collect(List.collector()))
                .build();

        return Option.of(processOutput);
    }
}
