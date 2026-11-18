package dev.amatv.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public final class MusicKitDeveloperToken {
    private MusicKitDeveloperToken() {}

    public static Instant write(Path keyPath, Path output, String teamId, String keyId)
            throws IOException, GeneralSecurityException {
        if (!teamId.matches("[A-Za-z0-9]+") || !keyId.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Set APPLE_TEAM_ID and APPLE_MUSIC_KEY_ID to the Apple identifiers.");
        }
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination) && Files.isSameFile(keyPath, destination)) {
            throw new IllegalArgumentException("Write the developer token to a file separate from the private key.");
        }
        String keyText = Files.readString(keyPath).replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        var key = KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyText)));
        long now = Instant.now().getEpochSecond();
        long expires = now + Duration.ofDays(180).toSeconds();
        String header = "{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"" + teamId + "\",\"iat\":" + (now - 60) + ",\"exp\":" + expires + "}";
        String message = encode(header.getBytes(StandardCharsets.UTF_8)) + "." + encode(claims.getBytes(StandardCharsets.UTF_8));
        var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update(message.getBytes(StandardCharsets.US_ASCII));
        byte[] signature = signer.sign();
        if (signature.length != 64) throw new GeneralSecurityException("MusicKit requires an Apple P-256 private key.");

        Path parent = destination.getParent();
        Files.createDirectories(parent);
        var temporary = Files.getFileStore(parent).supportsFileAttributeView("posix")
                ? Files.createTempFile(parent, ".musickit-", ".jwt", PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(parent, ".musickit-", ".jwt");
        try {
            Files.writeString(temporary, message + "." + encode(signature) + "\n");
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Instant.ofEpochSecond(expires);
    }

    private static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
