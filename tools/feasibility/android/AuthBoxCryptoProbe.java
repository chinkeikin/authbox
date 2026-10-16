import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AuthBoxCryptoProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !(args[0].equals("encrypt") || args[0].equals("decrypt"))) {
            throw new IllegalArgumentException("Expected encrypt/decrypt, key, nonce, aad, input");
        }
        byte[] key = decode(args[1]);
        byte[] nonce = decode(args[2]);
        if (key.length != 32 || nonce.length != 12) {
            throw new IllegalArgumentException("Expected AES-256 key and 96-bit nonce");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        int mode = args[0].equals("encrypt") ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(decode(args[3]));
        try {
            System.out.println(encode(cipher.doFinal(decode(args[4]))));
        } catch (AEADBadTagException error) {
            System.err.println("AEAD_AUTHENTICATION_FAILED");
            System.exit(3);
        }
    }

    private static byte[] decode(String value) {
        if (value.length() % 2 != 0) throw new IllegalArgumentException("Invalid hex");
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex");
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String encode(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            result[index * 2] = alphabet[(bytes[index] >>> 4) & 15];
            result[index * 2 + 1] = alphabet[bytes[index] & 15];
        }
        return new String(result);
    }
}
