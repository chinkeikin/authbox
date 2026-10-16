import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.util.encoders.Base32;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AuthBoxProtocolProbe {
    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private static byte[] random(int count) { byte[] value = new byte[count]; RANDOM.nextBytes(value); return value; }
    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException(name);
    }

    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<String>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder output = new StringBuilder("{");
            for (String key : keys) {
                if (output.length() > 1) output.append(',');
                output.append(canonical(key)).append(':').append(canonical(object.get(key)));
            }
            return output.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder output = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) output.append(',');
                output.append(canonical(array.get(index)));
            }
            return output.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value).replace("\\/", "/");
        if (value == JSONObject.NULL) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported fixture JSON");
    }

    private static String digest(Object value) throws Exception {
        return b64(MessageDigest.getInstance("SHA-256").digest(utf8(canonical(value))));
    }

    private static byte[] aad(JSONObject value, boolean wrap) throws Exception {
        JSONArray array = new JSONArray().put("AuthBox").put(1).put(wrap ? "vmk-wrap" : "entity").put(value.getString("vaultId"));
        if (wrap) array.put(value.getLong("keyGeneration")).put(value.getString("purpose")).put(value.getJSONObject("kdf"));
        else array.put(value.getString("id")).put(value.getString("type")).put(value.getLong("revision")).put(value.getLong("keyGeneration"));
        return utf8(canonical(array));
    }

    private static byte[] crypt(byte[] key, JSONObject value, boolean encrypt, boolean wrap, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, decode(value.getString("nonce"))));
        cipher.updateAAD(aad(value, wrap));
        return cipher.doFinal(encrypt ? input : decode(value.getString("ciphertext")));
    }

    private static byte[] derive(JSONObject wrap, String password, String recovery) throws Exception {
        JSONObject kdf = wrap.getJSONObject("kdf");
        byte[] output = new byte[32];
        if (wrap.getString("purpose").equals("password")) {
            check(kdf.getString("name").equals("argon2id") && kdf.getInt("version") == 19
                && kdf.getInt("memoryKiB") == 65536 && kdf.getInt("iterations") == 3
                && kdf.getInt("parallelism") == 1 && kdf.getInt("hashBytes") == 32, "Fixed vault profile");
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19)
                .withMemoryAsKB(65536).withIterations(3).withParallelism(1).withSalt(decode(kdf.getString("salt"))).build());
            generator.generateBytes(utf8(password), output);
        } else {
            check(recovery.matches("ABR1-[A-Z2-7]{52}-[A-Za-z0-9_-]{8}"), "Recovery format");
            byte[] raw = Base32.decode(recovery.substring(5, 57) + "====");
            check(raw.length == 32 && recovery.substring(58).equals(b64(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 8)), "Recovery checksum");
            check(kdf.getString("name").equals("HKDF-SHA256") && kdf.getString("info").equals("AuthBox/v1/vmk-recovery"), "Recovery domain");
            HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
            generator.init(new HKDFParameters(raw, decode(kdf.getString("salt")), utf8("AuthBox/v1/vmk-recovery")));
            generator.generateBytes(output, 0, 32);
            Arrays.fill(raw, (byte) 0);
        }
        return output;
    }

    private static JSONObject body(JSONObject backup) throws Exception {
        JSONObject output = new JSONObject(backup.toString());
        output.remove("manifest");
        output.remove("digest");
        return output;
    }

    private static byte[] restore(JSONObject backup, String purpose, String password, String recovery) throws Exception {
        JSONObject withoutDigest = new JSONObject(backup.toString());
        withoutDigest.remove("digest");
        check(digest(withoutDigest).equals(backup.getString("digest")), "Outer digest");
        JSONObject wrap = backup.getJSONObject("wraps").getJSONObject(purpose);
        byte[] wrappingKey = derive(wrap, password, recovery);
        byte[] vmk = crypt(wrappingKey, wrap, false, true, null);
        Arrays.fill(wrappingKey, (byte) 0);
        JSONObject manifest = new JSONObject(new String(crypt(vmk, backup.getJSONObject("manifest"), false, false, null), StandardCharsets.UTF_8));
        check(manifest.getString("digest").equals(digest(body(backup))), "Authenticated manifest");
        JSONArray records = backup.getJSONArray("entities");
        check(records.length() == manifest.getInt("entityCount"), "Complete entity list");
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            JSONObject plaintext = new JSONObject(new String(crypt(vmk, record, false, false, null), StandardCharsets.UTF_8));
            if (record.getString("type").equals("account")) check(plaintext.getString("secret").equals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"), "Restored public TOTP seed");
        }
        return vmk;
    }

    private static JSONObject rewrap(byte[] vmk, JSONObject old, String password, String recovery) throws Exception {
        JSONObject result = new JSONObject(old.toString());
        result.put("nonce", b64(random(12)));
        result.getJSONObject("kdf").put("salt", b64(random(result.getString("purpose").equals("password") ? 16 : 32)));
        byte[] key = derive(result, password, recovery);
        result.put("ciphertext", b64(crypt(key, result, true, true, vmk)));
        Arrays.fill(key, (byte) 0);
        return result;
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1 && args[0].matches("/data/local/tmp/authbox-protocol-[0-9a-f-]+/input.json"), "Isolated fixture path");
        JSONObject input = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        JSONObject backup = new JSONObject(input.getString("backup"));
        String password = input.getString("password");
        String recovery = input.getString("recoveryKey");
        byte[] vmk = restore(backup, "password", password, recovery);
        check(Arrays.equals(vmk, restore(backup, "recovery", password, recovery)), "Independent unwrap methods");
        JSONObject original = backup.getJSONArray("entities").getJSONObject(0);
        JSONObject tampered = new JSONObject(original.toString()).put("revision", 2);
        boolean rejected = false;
        try { crypt(vmk, tampered, false, false, null); } catch (AEADBadTagException expected) { rejected = true; }
        check(rejected, "AAD tampering rejected");
        JSONObject newWraps = new JSONObject();
        for (String purpose : new String[] { "password", "recovery" }) newWraps.put(purpose, rewrap(vmk, backup.getJSONObject("wraps").getJSONObject(purpose), password, recovery));
        backup.put("wraps", newWraps);
        JSONArray records = backup.getJSONArray("entities");
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            byte[] plaintext = crypt(vmk, record, false, false, null);
            record.put("nonce", b64(random(12)));
            record.put("ciphertext", b64(crypt(vmk, record, true, false, plaintext)));
        }
        JSONObject manifest = backup.getJSONObject("manifest");
        JSONObject manifestPayload = new JSONObject().put("digest", digest(body(backup))).put("entityCount", records.length());
        manifest.put("nonce", b64(random(12)));
        manifest.put("ciphertext", b64(crypt(vmk, manifest, true, false, utf8(canonical(manifestPayload)))));
        backup.remove("digest");
        backup.put("digest", digest(backup));
        Arrays.fill(vmk, (byte) 0);
        System.out.println(new JSONObject().put("backup", canonical(backup)).put("checks", new JSONArray()
            .put("Node backup password unwrap").put("Node backup recovery unwrap").put("Authenticated manifest and all entities")
            .put("AAD tampering rejected").put("Android generated both wraps and encrypted backup")));
    }
}
