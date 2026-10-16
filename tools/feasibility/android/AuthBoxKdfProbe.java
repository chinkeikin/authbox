import java.util.Arrays;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

public final class AuthBoxKdfProbe {
    private static byte[] fromHex(String value) {
        if (value.length() % 2 != 0 || !value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("Invalid public fixture");
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] repeated(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static String derive(byte[] password, Argon2Parameters parameters) {
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] output = new byte[32];
        generator.generateBytes(password, output);
        StringBuilder hex = new StringBuilder();
        for (byte value : output) {
            hex.append(String.format("%02x", value & 255));
        }
        return hex.toString();
    }

    private static Argon2Parameters.Builder profile(byte[] salt) {
        return new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt).withMemoryAsKB(65536).withIterations(3).withParallelism(1);
    }

    public static void main(String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("Expected public fixtures");
        byte[] password = fromHex(args[0]);
        byte[] salt = fromHex(args[1]);
        byte[] unicodePassword = fromHex(args[2]);
        Argon2Parameters rfc = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(repeated(16, (byte) 2)).withSecret(repeated(8, (byte) 3))
            .withAdditional(repeated(12, (byte) 4)).withMemoryAsKB(32)
            .withIterations(3).withParallelism(4).build();
        System.out.println("{\"rfc\":\"" + derive(repeated(32, (byte) 1), rfc) + "\",\"samples\":[");
        derive(password, profile(salt).build());
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            String hash = derive(password, profile(salt).build());
            double milliseconds = (System.nanoTime() - start) / 1000000.0;
            System.out.print((i == 0 ? "" : ",") + "{\"hash\":\"" + hash + "\",\"milliseconds\":" + milliseconds + "}");
        }
        byte[] changedSalt = salt.clone();
        changedSalt[0] ^= 1;
        System.out.println("],\"unicode\":\"" + derive(unicodePassword, profile(salt).build())
            + "\",\"changedSalt\":\"" + derive(password, profile(changedSalt).build()) + "\"}");
    }
}
