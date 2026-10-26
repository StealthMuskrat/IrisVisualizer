package dev.irisvisualizer.feasibility;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Hashes relative paths and exact file bytes; ignores only the checkout's root .git directory. */
public final class PackFingerprint {
    private PackFingerprint() {}
    public static String sha256(Path root) throws IOException {
        MessageDigest digest = digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> !p.equals(root))
                    .filter(p -> !root.relativize(p).getName(0).toString().equals(".git"))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/'))).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink is unsupported by the feasibility probe: " + path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                byte[] key = root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(key.length).array());
                digest.update(key);
                digest.update(ByteBuffer.allocate(8).putLong(Files.size(path)).array());
                try (var input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
