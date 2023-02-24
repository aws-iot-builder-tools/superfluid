package com.awslabs.superfluid.helpers;

import com.awslabs.superfluid.data.ProcessOutput;
import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStreamWriter;

public class SystemHelper {
    private static final RuntimeException UNSUPPORTED_OS_EXCEPTION = new RuntimeException("Unsupported OS");

    public static boolean toolMissing(String toolName) {
        return !toolAvailable(toolName);
    }

    public static boolean toolAvailable(String toolName) {
        if (SystemUtils.IS_OS_WINDOWS) {
            List<String> command = List.of("where", "/q", toolName);

            return ProcessHelper.getOutputFromProcess(command)
                    .map(ProcessOutput::exitCode)
                    // Any value other than one when the tool is foudn, one when the tool is missing
                    .get() != 1;
        }

        if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC_OSX) {
            // Linux and MacOS
            List<String> command = List.of("which", toolName);

            return ProcessHelper.getOutputFromProcess(command)
                    .map(ProcessOutput::exitCode)
                    // Zero when the tool is found, non-zero when it is not found
                    .get() == 0;
        }

        throw UNSUPPORTED_OS_EXCEPTION;
    }

    public static boolean iTermAvailable() {
        // From: https://apple.stackexchange.com/a/379140
        List<String> command = List.of("mdfind", "kMDItemCFBundleIdentifier == com.googlecode.iterm2");

        return ProcessHelper.getOutputFromProcess(command)
                .map(ProcessOutput::standardOutStrings)
                .map(List::ofAll)
                .getOrElse(List::empty)
                .filter(item -> item.contains("iTerm.app"))
                .size() > 0;
    }

    public static Option<ProcessOutput> runCommandInTerminal(String rawCommand, Option<String> tempScriptNameOption) {
        List<String> command;

        Option<File> scriptFileOption = Option.none();

        if (SystemUtils.IS_OS_WINDOWS) {
            // From: https://stackoverflow.com/a/29138847/796579
            // "/c" terminates cmd after the window is closed
            // "start" opens a new window
            // "cmd" is the default shell
            // "/k" keeps the shell open after the command exits so the user can see the output
            command = List.of("cmd", "/c", "start", "cmd", "/k", rawCommand);
        } else if (SystemUtils.IS_OS_LINUX) {
            // From: https://unix.stackexchange.com/a/373384
            command = List.of("xterm", "-e", "/bin/bash", "-c", String.join(" ; ", rawCommand, "echo Done", "read"));
        } else if (SystemUtils.IS_OS_MAC_OSX) {
            File tempScriptFile = tempScriptNameOption.map(File::new)
                    .getOrElseTry(() -> File.createTempFile("temp", ".sh"));

            scriptFileOption = Option.of(tempScriptFile);

            File file = scriptFileOption.get();

            Try.of(() -> new FileWriter(file))
                    .andThenTry(writer -> writer.write("#!/usr/bin/env bash\n"))
                    .andThenTry(writer -> writer.write("clear\n"))
                    .andThenTry(writer -> writer.write(rawCommand))
                    .andThenTry(writer -> writer.write("\n"))
                    // File.deleteOnExit() sometimes deletes the script before the terminal program can read it,
                    //   so we have the script delete itself when it is done
                    .andThenTry(writer -> writer.write("rm $0\n"))
                    .andThenTry(OutputStreamWriter::close);

            tempScriptFile.setExecutable(true);

            String terminalProgram = iTermAvailable() ? "iTerm" : "Terminal";
            command = List.of("/usr/bin/open", "-a", terminalProgram, file.getAbsolutePath());
        } else {
            throw UNSUPPORTED_OS_EXCEPTION;
        }

        Option<ProcessOutput> processOutputOption = ProcessHelper.getOutputFromProcess(command);

        return processOutputOption;
    }
}
