package com.nextstation.backend.push;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Base64;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.BigIntegers;

/** Run with ./gradlew generateVapidKeys. Never prints a private key or overwrites existing keys. */
public final class VapidKeyGenerator {
    private VapidKeyGenerator() {}

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
        var pair = generator.generateKeyPair();
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode((ECPublicKey) pair.getPublic()));
        String privateKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(BigIntegers.asUnsignedByteArray(32, ((ECPrivateKey) pair.getPrivate()).getD()));
        Path destination = Path.of(".env.vapid");
        // Restrict permissions at creation where POSIX permissions are supported.
        if (Files.getFileStore(Path.of(".")).supportsFileAttributeView("posix")) {
            Files.createFile(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(destination);
        }
        Files.writeString(destination, "VAPID_PUBLIC_KEY=" + publicKey + "\nVAPID_PRIVATE_KEY=" + privateKey + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        System.out.println("Saved new keys to .env.vapid. Keep this file private and back it up.");
        System.out.println("VAPID_PUBLIC_KEY=" + publicKey);
    }
}
