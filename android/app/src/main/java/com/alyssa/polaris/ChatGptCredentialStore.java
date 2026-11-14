package com.alyssa.polaris;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Never uses the normal persistence/backup pipeline; keys are generated only on-device after login. */
final class ChatGptCredentialStore implements ChatGptSession.Storage {
    private static final String ALIAS = "polaris.chatgpt.credentials.v1";
    private static final byte[] AAD = "polaris-chatgpt-v1".getBytes(StandardCharsets.UTF_8);
    private final AtomicFile credentials;
    private final AtomicFile host;

    ChatGptCredentialStore(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "chatgpt");
        directory.mkdirs();
        credentials = new AtomicFile(new File(directory, "accounts.enc"));
        host = new AtomicFile(new File(directory, "host-id"));
    }

    private static byte[] read(AtomicFile file) throws Exception {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (out.size() + count > 2 * 1024 * 1024) throw new Exception("安全凭证文件过大。");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void write(AtomicFile file, byte[] bytes) throws Exception {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    public synchronized String hostId() throws Exception {
        if (host.getBaseFile().exists()) return new String(read(host), StandardCharsets.UTF_8);
        String id = "urn:uuid:" + UUID.randomUUID();
        write(host, id.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new Exception("安全凭证无法解密，请重新连接账号。");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    public synchronized JSONObject load() throws Exception {
        if (!credentials.getBaseFile().exists()) return new JSONObject().put("accounts", new JSONArray());
        byte[] bytes = read(credentials);
        if (bytes.length < 29 || bytes[0] != 1) throw new Exception("安全凭证格式不正确，请重新连接。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, bytes, 1, 12));
        cipher.updateAAD(AAD);
        return new JSONObject(new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8));
    }

    public synchronized void save(JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(1);
        output.write(cipher.getIV());
        output.write(encrypted);
        write(credentials, output.toByteArray());
    }

    public synchronized void reset() throws Exception {
        credentials.delete();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (keys.containsAlias(ALIAS)) keys.deleteEntry(ALIAS);
        // Keep this installation's non-secret host ID; reset never touches Polaris chats.
    }
}
