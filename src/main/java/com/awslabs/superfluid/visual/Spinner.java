package com.awslabs.superfluid.visual;

import io.vavr.control.Option;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.fusesource.jansi.Ansi;

import java.io.PrintWriter;
import java.time.Duration;

import static com.awslabs.superfluid.helpers.Shared.print;
import static com.awslabs.superfluid.helpers.Shared.println;
import static org.fusesource.jansi.Ansi.ansi;

public class Spinner {
    public static final Spinner Standard() {
        return new Spinner().forwards().delayMs(100);
    }
    private static final String fail = "✖";
    private static final String succeed = "✔";

    private final PrintWriter printWriter = new PrintWriter(System.out);
    // Braille patterns from https://en.wikipedia.org/wiki/Braille_Patterns
    private final String[] spinners = new String[]{"⠁", "⠂", "⠄", "⠠", "⠐", "⠈"};
    private Option<Thread> threadOption = Option.none();
    private boolean forward = true;
    private int delayMs = 500;
    private boolean running = false;

    public Spinner forwards() {
        this.forward = true;
        return this;
    }

    public Spinner backwards() {
        this.forward = false;
        return this;
    }

    public Spinner delayMs(int delayMs) {
        this.delayMs = delayMs;
        return this;
    }

    public void start(String message) {
        print("  " + message);
        print(ansi().cursorToColumn(0).toString());
        start();
    }

    public void start() {
        if (threadOption.isDefined()) {
            return;
        }

        threadOption = Option.of(new Thread(() -> {
            running = true;
            int index = 0;

            hideCursor();

            while (running) {
                index = getNextIndex(index);

                printWriter.print(spinners[index]);
                printWriter.flush();

                try {
                    Thread.sleep(delayMs);
                } catch (Exception e) {
                    running = false;
                }

                printWriter.print("\b \b");
                printWriter.flush();
            }

            showCursor();

            // Clear out the thread variable to show we're really done
            threadOption = Option.none();
        }));

        threadOption.forEach(Thread::start);
    }

    private void hideCursor() {
        print("\033[?25l");
    }

    private void showCursor() {
        print("\033[?25h");
    }

    private int getNextIndex(int index) {
        if (forward) {
            // The natural order of the spinners is backwards, so we need to reverse them
            index--;

            if (index < 0) {
                index = spinners.length - 1;
            }
        } else {
            index++;
        }

        index = index % spinners.length;

        return index;
    }

    public void stop() {
        running = false;
    }

    public boolean stopped() {
        return !running && threadOption.isEmpty();
    }

    public void waitForStop() {
        stop();

        RetryPolicy<Boolean> waitForTruePolicy = new RetryPolicy<Boolean>()
                .withMaxAttempts(-1)
                // Don't retry forever, double the delay should be plenty of time
                .withMaxDuration(Duration.ofMillis(delayMs * 2L))
                // If stopped is false then it is still running and we should keep waiting
                .handleResultIf(result -> !result);

        // Wait for stopped to return true
        Failsafe.with(waitForTruePolicy).get(this::stopped);
    }

    public void success(String message) {
        waitForStop();
        print(ansi().eraseLine().toString());
        print(ansi().render("@|green %s|@ %s", succeed, message).toString());
        println();
    }

    public void fail(String message) {
        waitForStop();
        print(ansi().eraseLine().toString());
        print(ansi().render("@|red %s|@ %s", fail, message).toString());
        println();
    }
}
