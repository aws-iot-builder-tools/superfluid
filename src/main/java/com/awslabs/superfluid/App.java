package com.awslabs.superfluid;

import com.awslabs.superfluid.commands.Parent;
import io.vavr.control.Option;
import io.vavr.control.Try;
import picocli.CommandLine;
import picocli.jansi.graalvm.AnsiConsole;

import java.io.File;
import java.util.Arrays;

public class App {
    public static final String TOOL_NAME = "superfluid";
    public static final String SHORT_TOOL_NAME = "sf";

    public static void main(String[] args) {
//        GreengrassCleanup.tempRun();
//        System.exit(1);
        // If the environment variable `_` exists AND it is not `java` then we need to prepend it to the args
        String[] finalArgs = Option.of(System.getenv("_"))
                // If the variable exists, wrap it in a File object
                .map(File::new)
                // Use the file object to just extract the name
                .map(File::getName)
                // If the name is not the Java executable
                .filter(App::isNotJavaExecutable)
                .map(value -> prepend(args, value))
                .orElse(Option.of(args))
                .map(App::removeToolName)
                .get();

        // NOTE: AnsiConsole::windowsInstall is to enable colors on Windows
        int returnValue = Try.withResources(AnsiConsole::windowsInstall)
                // Run our command, if possible and get the return value to use as the exit code
                .of(ansiConsole -> new CommandLine(new Parent()).execute(finalArgs))
                .get();

        System.exit(returnValue);
    }

    private static String[] removeToolName(String[] values) {
        if (values.length > 0 && (TOOL_NAME.equals(values[0]) || SHORT_TOOL_NAME.equals(values[0]))) {
            return removeLeading(values, 1);
        }

        return values;
    }

    private static <T> T[] removeLeading(T[] elements, Integer value) {
        if (value == 0) {
            return elements;
        }

        return Arrays.copyOfRange(elements, value, elements.length);
    }

    public static <T> T[] prepend(T[] elements, T element) {
        T[] newArray = Arrays.copyOf(elements, elements.length + 1);
        newArray[0] = element;
        System.arraycopy(elements, 0, newArray, 1, elements.length);

        return newArray;
    }

    public static boolean isNotJavaExecutable(String name) {
        return !(name.equals("java") || name.equals("java.exe") || name.contains("gradle"));
    }
}
