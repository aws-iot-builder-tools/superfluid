package com.awslabs.superfluid.helpers.files;

import io.vavr.control.Option;
import io.vavr.control.Try;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BiFunction;

import static com.awslabs.superfluid.helpers.Shared.println;

public class SafeVisitor implements FileVisitor<Path> {
    Option<BiFunction<Path, BasicFileAttributes, FileVisitResult>> preVisitDirectoryOption = Option.none();
    Option<BiFunction<Path, BasicFileAttributes, FileVisitResult>> visitFileOption = Option.none();
    Option<BiFunction<Path, IOException, FileVisitResult>> visitFileFailedOption = Option.none();
    Option<BiFunction<Path, IOException, FileVisitResult>> postVisitDirectoryOption = Option.none();

    public SafeVisitor() {
    }

    public Try<Path> walk(Path path) {
        return Try.of(() -> Files.walkFileTree(path, this));
    }

    public SafeVisitor preVisitDirectory(BiFunction<Path, BasicFileAttributes, FileVisitResult> preVisitDirectory) {
        this.preVisitDirectoryOption = Option.of(preVisitDirectory);
        return this;
    }

    public SafeVisitor visitFile(BiFunction<Path, BasicFileAttributes, FileVisitResult> visitFile) {
        this.visitFileOption = Option.of(visitFile);
        return this;
    }

    public SafeVisitor visitFileFailed(BiFunction<Path, IOException, FileVisitResult> visitFileFailed) {
        this.visitFileFailedOption = Option.of(visitFileFailed);
        return this;
    }

    public SafeVisitor postVisitDirectory(BiFunction<Path, IOException, FileVisitResult> postVisitDirectory) {
        this.postVisitDirectoryOption = Option.of(postVisitDirectory);
        return this;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (dir == null) {
            println("dir is null");
        }
        return preVisitDirectoryOption.map(f -> f.apply(dir, attrs)).getOrElse(FileVisitResult.CONTINUE);
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        return visitFileOption.map(f -> f.apply(file, attrs)).getOrElse(FileVisitResult.CONTINUE);
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        return visitFileFailedOption.map(f -> f.apply(file, exc)).getOrElse(FileVisitResult.CONTINUE);
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
        return postVisitDirectoryOption.map(f -> f.apply(dir, exc)).getOrElse(FileVisitResult.CONTINUE);
    }
}
