package com.awslabs.superfluid.helpers;

import com.awslabs.superfluid.visual.Spinner;
import io.vavr.Function0;
import io.vavr.Function1;
import io.vavr.Tuple;
import io.vavr.Tuple3;
import io.vavr.collection.List;
import io.vavr.control.Try;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.Collection;
import java.util.stream.Stream;

import static java.text.MessageFormat.format;

public class AwsSdkHelper {
    private static final Spinner SPINNER = Spinner.Standard();
    public static final Tuple3<String, String, String> LIST = Tuple.of("Listing", "Listed", "list");
    public static final Tuple3<String, String, String> DESCRIBE = Tuple.of("Describing", "Described", "describe");
    public static final Tuple3<String, String, String> DELETE = Tuple.of("Deleting", "Deleted", "delete");
    public static final Tuple3<String, String, String> DETACH = Tuple.of("Detaching", "Detached", "detach");
    public static final Tuple3<String, String, String> DEACTIVATE = Tuple.of("Deactivating", "Deactivated", "deactivate");
    public static final Tuple3<String, String, String> CONVERT = Tuple.of("Converting", "Converted", "convert");

    public static <T extends SdkResponse, U> Try<Stream<U>> resultStream(Function0<SdkIterable<T>> paginatedSdkCall,
                                                                         Function1<T, java.util.List<U>> getListFunction) {
        return Try.of(() -> paginatedSdkCall.get()
                .stream()
                .map(getListFunction)
                .flatMap(Collection::stream));
    }

    public static <T> Try<List<T>> resultList(Function0<Try<Stream<T>>> paginatedSdkCall) {
        return paginatedSdkCall.get()
                .map(io.vavr.collection.List::ofAll);
    }

    public static <T> Try<List<T>> resultListWithSpinner(Function0<Try<Stream<T>>> paginatedSdkCall,
                                                         String type, Tuple3<String, String, String> words) {
        return resultListWithSpinner(paginatedSdkCall, type, words._1, words._2, words._3);
    }

    public static <T> Try<List<T>> resultListWithSpinner(Function0<Try<Stream<T>>> paginatedSdkCall,
                                                         String type, String inProgressWord, String successWord, String failedWord) {
        SPINNER.start(String.join(" ", inProgressWord, "the", type));

        return resultList(paginatedSdkCall)
                .onFailure(e -> SPINNER.fail(format("Failed to {0} the {1} [{2}]", failedWord, type, e.getMessage())))
                .onSuccess(list -> SPINNER.success(format("{0} {1} {2}", successWord, list.size(), type)));
    }

    public static <T extends SdkResponse, U> Try<U> result(Function0<T> sdkCall, Function1<T, U> getFunction) {
        return Try.of(sdkCall::get)
                .map(getFunction);
    }

    public static <T extends SdkResponse> Try<T> resultWithSpinner(Function0<T> sdkCall, String type, Tuple3<String, String, String> words) {
        // No translation on the output. This is useful for API calls that delete resources but don't return anything.
        return resultWithSpinner(sdkCall, result -> result, type, words._1, words._2, words._3);
    }

    public static <T extends SdkResponse, U> Try<U> resultWithSpinner(Function0<T> sdkCall, Function1<T, U> getFunction, String type, Tuple3<String, String, String> words) {
        return resultWithSpinner(sdkCall, getFunction, type, words._1, words._2, words._3);
    }

    public static <T extends SdkResponse, U> Try<U> resultWithSpinner(Function0<T> sdkCall, Function1<T, U> getFunction, String type, String inProgressWord, String successWord, String failedWord) {
        SPINNER.start(String.join(" ", inProgressWord, "the", type));

        return result(sdkCall, getFunction)
                .onFailure(e -> SPINNER.fail(format("Failed to {0} the {1} [{2}]", failedWord, type, e.getMessage())))
                .onSuccess(list -> SPINNER.success(format("{0} {1}", successWord, type)));
    }
}
