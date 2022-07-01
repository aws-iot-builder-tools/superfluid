package com.awslabs.superfluid.helpers;

import com.awslabs.superfluid.exceptions.ssh.SshHomeDirectoryNotFoundException;
import com.awslabs.superfluid.exceptions.ssh.SshRecoverableException;
import com.awslabs.superfluid.exceptions.ssh.recoverable.SshRefusedException;
import com.awslabs.superfluid.exceptions.ssh.recoverable.SshTimeoutException;
import com.awslabs.superfluid.exceptions.ssh.unrecoverable.SshAuthenticationFailedException;
import com.awslabs.superfluid.exceptions.ssh.unrecoverable.SshHostCouldNotBeResolvedException;
import com.jcraft.jsch.*;
import io.vavr.API;
import io.vavr.Tuple;
import io.vavr.collection.List;
import io.vavr.collection.Traversable;
import io.vavr.control.Option;
import io.vavr.control.Try;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.utils.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.awslabs.superfluid.helpers.Shared.log;
import static io.vavr.API.*;
import static io.vavr.Predicates.instanceOf;

public class SshHelper {
    private static final String BEGIN_RSA_PRIVATE_KEY = "BEGIN RSA PRIVATE KEY";
    private static final String BEGIN_OPENSSH_PRIVATE_KEY = "BEGIN OPENSSH PRIVATE KEY";
    private static final Predicate<String> RSA_PRIVATE_KEY_PREDICATE = string -> string.contains(BEGIN_RSA_PRIVATE_KEY);
    private static final Predicate<String> OPENSSH_PRIVATE_KEY_PREDICATE = string -> string.contains(BEGIN_OPENSSH_PRIVATE_KEY);
    private static final Predicate<String> SSH_PRIVATE_KEY_PREDICATE = RSA_PRIVATE_KEY_PREDICATE.or(OPENSSH_PRIVATE_KEY_PREDICATE);
    private static final String OPENSSH_HELP = "If your keys are in OpenSSH format try converting them with the 'ssh-keygen -p -m PEM -f YOUR_KEY_FILE' command.";
    private static final String USER_HOME = "user.home";
    private static final String STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking";
    private static final String NO = "no";
    private static final String RSA = "RSA";
    private static final String MD5 = "MD5";

    public static Session getSessionWithStatus(Option<JSch> jSchOption, String hostname, String user, boolean hostKnown) {
        RetryPolicy<Session> sessionRetryPolicy = getDefaultSessionRetryPolicy()
                .onRetry(failure -> log().warn(String.join("", "Waiting for target host to become available [", failure.getLastFailure().getMessage(), "]")))
                .onRetriesExceeded(failure -> log().error(String.join("", "Target host never became available [", failure.getFailure().getMessage(), "]")));

        return Failsafe.with(sessionRetryPolicy)
                .get(() -> getSession(jSchOption, hostname, user, hostKnown));
    }

    public static Session getSessionWithoutStatus(Option<JSch> jSchOption, String hostname, String user, boolean hostKnown) {
        return Failsafe.with(getDefaultSessionRetryPolicy())
                .get(() -> getSession(jSchOption, hostname, user, hostKnown));
    }

    private static RetryPolicy<Session> getDefaultSessionRetryPolicy() {
        return new RetryPolicy<Session>()
                .handle(SshRecoverableException.class)
                .withDelay(Duration.ofSeconds(10))
                .withMaxRetries(3);
    }

    private static Session getSession(String hostname, String user, boolean hostKnown) {
        return getSession(Option.none(), hostname, user, hostKnown);
    }

    private static Session getSession(Option<JSch> jSchOption, String hostname, String user, boolean hostKnown) {
        // Use the provided JSch or create a new one with the private keys loaded
        JSch jsch = jSchOption.getOrElse(SshHelper::getJschWithPrivateKeysLoaded);

        Try<Session> sessionTry = Try.of(() -> jsch.getSession(user, hostname, 22));

        if (sessionTry.isFailure()) {
            // Rethrow the exception
            return sessionTry.get();
        }

        Session session = sessionTry.get();

        if (!hostKnown) {
            // Since we don't know the key of the host up front, we'll disable strict host key checking
            Properties config = new Properties();
            config.put(STRICT_HOST_KEY_CHECKING, NO);
            session.setConfig(config);
        }

        Try.run(() -> session.connect(10000))
                .recoverWith(JSchException.class, failure -> cleanUpExceptions(hostname, failure))
                .get();

        return session;
    }

    private static Try<Void> cleanUpExceptions(String hostname, JSchException throwable) {
        Exception exception = Match(throwable).of(
                API.Case($(t -> t.getMessage().contains("timeout")), new SshTimeoutException()),
                API.Case($(t -> t.getMessage().contains("Connection refused")), new SshRefusedException()),
                Case($(t -> t.getMessage().contains("Auth fail")), new SshAuthenticationFailedException()),
                Case($(t -> t.getCause() instanceof UnknownHostException), new SshHostCouldNotBeResolvedException(hostname)),
                Case($(), new SshRecoverableException(throwable.getMessage())));

        return Try.failure(exception);
    }

    public static List<String> runCommand(Session session, String command) throws JSchException, IOException {
        return runCommand(session, command, Option.none());
    }

    public static List<String> runCommand(Session session, String command, Option<Consumer<String>> stringConsumerOption) throws JSchException, IOException {
        Channel channel = session.openChannel("exec");
        ((ChannelExec) channel).setCommand(command);

        StringBuilder lineStringBuilder = new StringBuilder();
        List<String> output = List.empty();

        try (InputStream commandOutput = channel.getInputStream()) {
            channel.connect();
            int readByte = commandOutput.read();

            while (readByte != 0xffffffff) {
                char character = (char) readByte;
                readByte = commandOutput.read();

                if (character == '\r') {
                    // Throw away \r
                } else if (character == '\n') {
                    String line = lineStringBuilder.toString();
                    stringConsumerOption.forEach(consumer -> consumer.accept(line));
                    output = output.append(line);
                    lineStringBuilder = new StringBuilder();
                } else {
                    lineStringBuilder.append(character);
                }
            }
        } finally {
            channel.disconnect();
        }

        return output;
    }

    // This suppresses warnings introduced by mapFailure
    @SuppressWarnings("unchecked")
    public static JSch getJschWithPrivateKeysLoaded() {
        JSch jsch = new JSch();

        List<Path> filesToLoad = SshHelper.getPrivateKeyFilesForSsh()
                // If there's no SSH directory, can't load keys, return a nicer error
                .mapFailure(Case($(instanceOf(SshHomeDirectoryNotFoundException.class)), getNoSshPrivateKeysFoundException()))
                .get();

        // Load all of the keys we can find
        List<Try<Path>> results = filesToLoad.map(path -> addIdentitiesToJsch(jsch, path));

        // Log all of the failures
        results.filter(Try::isFailure)
                .map(Try::getCause)
                .forEach(SshHelper::logPrivateKeyIssueAndIgnore);

        // Were any private keys loaded?
        if (jsch.getIdentityRepository().getIdentities().isEmpty()) {
            // No, without private keys we can't connect
            throw new RuntimeException(String.join("", "No usable identities found, cannot continue. ", OPENSSH_HELP));
        }

        // Log all of the loaded keys
        results.filter(Try::isSuccess)
                .map(Try::get)
                .forEach(SshHelper::logPrivateKeyLoaded);

        return jsch;
    }

    private static void logPrivateKeyLoaded(Path path) {
        log().debug("Loaded private key: {}", path.toAbsolutePath());
    }

    // This suppresses warnings introduced by mapFailure
    @SuppressWarnings("unchecked")
    private static Try<Path> addIdentitiesToJsch(JSch jsch, Path path) {
        return Try.of(path::toAbsolutePath)
                .map(Object::toString)
                .map(filenameString -> tryAddIdentity(jsch, filenameString))
                // Just return a success with the filename if it works
                .map(result -> path)
                // Return a wrapped exception if it doesn't work
                .mapFailure(Case($(instanceOf(JSchException.class)), throwable -> wrapJschException(throwable, path)));
    }

    private static Try<Void> tryAddIdentity(JSch jsch, String filenameString) {
        return Try.run(() -> jsch.addIdentity(filenameString));
    }

    @NotNull
    private static RuntimeException getNoSshPrivateKeysFoundException() {
        return new RuntimeException(String.join("", "No SSH private keys found, cannot continue. ", OPENSSH_HELP));
    }

    private static RuntimeException wrapJschException(JSchException jSchException, Path path) {
        return new RuntimeException(String.format("Issue with private key file [%s] skipping", path), jSchException);
    }

    private static void logPrivateKeyIssueAndIgnore(Throwable exception) {
        // This log level is set to debug because we may find files in the .ssh directory that we can't use but since there's no standard
        //   extension for private keys we can't know for sure if it is a real issue or not.
        log().debug("{} - {}", exception.getMessage(), exception);
    }

    // This suppresses warnings introduced by mapFailure
    @SuppressWarnings("unchecked")
    private static Try<Path> getSshDirectory() {
        return tryGetHomePath()
                .map(Path::toAbsolutePath)
                .toTry()
                .map(path -> path.resolve(".ssh"))
                .mapFailure(Case($(instanceOf(InvalidPathException.class)), SshHomeDirectoryNotFoundException::new));
    }

    private static Try<List<Path>> getPublicKeysForSsh() {
        // Recursively get all of the files in the directory, only look at regular files, and make sure they look like private keys
        return getSshDirectory()
                .flatMap(SshHelper::getFiles)
                .map(list -> list.filter(SshHelper::isPublicKey));
    }

    private static Try<List<Path>> getPrivateKeyFilesForSsh() {
        // Recursively get all of the files in the directory, only look at regular files, and make sure they look like private keys
        return getSshDirectory()
                .flatMap(SshHelper::getFiles)
                .map(list -> list.filter(SshHelper::isPrivateKey));
    }

    private static Try<List<Path>> getFiles(Path path) {
        // Recursively get all of the files in the directory, and only look at regular files
        return Try.of(() -> Files.walk(path))
                .map(List::ofAll)
                .map(list -> list.filter(Files::isRegularFile));
    }

    private static boolean isPrivateKey(Path path) {
        return Try.of(() -> Files.readAllBytes(path))
                .map(String::new)
                // Check the data in the against our SSH key tests
                .map(SSH_PRIVATE_KEY_PREDICATE::test)
                // Only return values that are true
                .filter(v -> v)
                .isSuccess();
    }

    private static boolean isPublicKey(Path path) {
        return Try.of(() -> Files.readAllBytes(path))
                .map(String::new)
                .map(data -> data.split(" "))
                .map(List::of)
                .map(list -> list.filter(s -> s.startsWith("AAAA")))
                // There really should only be one, but just in case
                .filter(Traversable::isSingleValued)
                .isSuccess();
    }

    static Try<Path> tryGetHomePath() {
        return Option.of(System.getProperty(USER_HOME))
                .map(Paths::get)
                .toTry();
    }

    private static String getTypeAsString(ByteBuffer byteBuffer) {
        int length = byteBuffer.getInt();
        byte[] type = new byte[length];
        byteBuffer.get(type);

        return new String(type);
    }

    // From https://stackoverflow.com/a/62814416/796579 (2020-07-09)
    // License according to https://stackoverflow.com/help/licensing - CC BY-SA 4.0
    static Try<String> getFingerprintFromPublicKey(Path path) {
        // Read the input file
        return Try.of(() -> Files.newInputStream(path))
                .mapTry(IoUtils::toUtf8String)
                .flatMap(string -> getFingerprintFromPublicKey(string));
    }

    static Try<String> getFingerprintFromPublicKey(String publicKeyString) {
        // Split the input into chunks with the space character as the delimiter
        return Try.of(() -> io.vavr.collection.List.of(publicKeyString.split(" ")))
                // Unwrap the Try so we can just filter the list, if there's no data just use an empty list
                .getOrElse(List.empty())
                // Find the string that starts with "AAAA"
                .filter(part -> part.startsWith("AAAA"))
                // Convert the string to bytes
                .map(part -> part.getBytes(StandardCharsets.UTF_8))
                // Convert back to a try (and therefore take the first element) so we can do the operations below safely
                .toTry()
                // Base64 decode the bytes
                .map(bytes -> Base64.getDecoder().decode(bytes))
                // Convert the bytes to a ByteBuffer
                .map(ByteBuffer::wrap)
                // Get the type value out of the ByteBuffer
                .map(buffer -> Tuple.of(buffer, getTypeAsString(buffer)))
                .filter(tuple -> "ssh-rsa".equals(tuple._2))
                // Get the exponent (byte buffer is element 1, exponent is element 2)
                .map(tuple -> Tuple.of(tuple._1, decodeBigInt(tuple._1)))
                // Get the modulus (exponent becomes element 1, modulus is element 2)
                .map(tuple -> Tuple.of(tuple._2, decodeBigInt(tuple._1)))
                // Convert the exponent and modulus into an RSA public key spec (note: the values are swapped because modulus goes first)
                .map(tuple -> new RSAPublicKeySpec(tuple._2, tuple._1))
                // Convert the RSA public key spec into a public key (checked exception requires mapTry)
                .mapTry(spec -> KeyFactory.getInstance(RSA).generatePublic(spec))
                // Get the encoded version (which is the DER encoded version of the public key)
                .map(Key::getEncoded)
                // Get the fingerprint
                .map(encoded -> getFingerprint(MD5, encoded));
    }

    private static BigInteger decodeBigInt(ByteBuffer byteBuffer) {
        // use first 4 bytes to generate an Integer that gives the length of bytes to create BigInteger
        int length = byteBuffer.getInt();
        byte[] bytes = new byte[length];
        byteBuffer.get(bytes);
        return new BigInteger(bytes);
    }

    private static String getFingerprint(String algorithm, byte[] rawData) {
        // Get a message digester
        return Try.of(() -> MessageDigest.getInstance(algorithm))
                // Digest the DER encoded certificate data
                .map(messageDigest -> messageDigest.digest(rawData))
                // Turn it into a colon delimited, hex encoded string
                .map(SshHelper::hexEncode)
                // Throw an exception if anything fails and return the result to the caller
                .get();
    }

    private static String hexEncode(byte[] v) {
        return List.ofAll(v)
                .map(Byte::intValue)
                .map(Integer::toHexString)
                .map(SshHelper::addLeadingZero)
                .collect(Collectors.joining(":"));
    }

    private static String addLeadingZero(String input) {
        if (input.length() >= 2) {
            // Trim leading characters, if necessary. Leading characters are present with negative values.
            return input.substring(input.length() - 2);
        }

        // Values under 16 need a leading zero
        return "0" + input;
    }

    public static Try<List<KeyPairInfo>> getKeyPairs() {
        return Try.of(Ec2Client::create)
                .map(Ec2Client::describeKeyPairs)
                .map(DescribeKeyPairsResponse::keyPairs)
                .map(List::ofAll);
    }
}
