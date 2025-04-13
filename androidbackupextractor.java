//#!/usr/bin/env jbang
//DEPS org.bouncycastle:bcprov-jdk18on:1.80
//DEPS info.picocli:picocli:4.7.4

/**
λ jbang https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/androidbackupextractor.java --help
Usage: android-backup-extractor [-hV] [-p=<password>] <mode> <file1> <file2>
Tool for packing/unpacking Android backups
      <mode>      Operation mode: pack, unpack, or pack-kk
      <file1>     First file parameter (backup or tar file based on mode)
      <file2>     Second file parameter (tar or backup file based on mode)
  -h, --help      Show this help message and exit.
  -p, --password=<password>
                  Password for encryption/decryption
  -V, --version   Print version information and exit.


  jbang https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/androidbackupextractor.java unpack com.plantparentai.app.ab com.plantparentai.app.tar
*/
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Key;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Based on https://github.com/nelenkov/android-backup-extractor
 */
@Command(name = "android-backup-extractor", mixinStandardHelpOptions = true, version = "1.0",
         description = "Tool for packing/unpacking Android backups")
class ABECommand implements Runnable {

    @Parameters(index = "0", description = "Operation mode: pack, unpack, or pack-kk")
    private String mode;

    @Parameters(index = "1", description = "First file parameter (backup or tar file based on mode)")
    private String file1;

    @Parameters(index = "2", description = "Second file parameter (tar or backup file based on mode)")
    private String file2;

    @Option(names = {"-p", "--password"}, description = "Password for encryption/decryption")
    private String password;

    @Override
    public void run() {
        // Determine mode and corresponding file arguments.
        boolean unpack = "unpack".equals(mode);
        String backupFilename = unpack ? file1 : file2;
        String tarFilename = unpack ? file2 : file1;

        // Fallback to environment variable if password is not provided.
        if (password == null || password.isEmpty()) {
            password = System.getenv("ABE_PASSWD");
        }

        if (unpack) {
            AndroidBackup.extractAsTar(backupFilename, tarFilename, password);
        } else {
            boolean isKitKat = "pack-kk".equals(mode);
            AndroidBackup.packTar(tarFilename, backupFilename, password, isKitKat);
        }
    }
}

/**
 * Main entry point.
 */
public class androidbackupextractor {
    public static void main(String[] args) {
        // Add BouncyCastle provider.
        Security.addProvider(new BouncyCastleProvider());
        int exitCode = new CommandLine(new ABECommand()).execute(args);
        System.exit(exitCode);
    }
}

class AndroidBackup {

    private static final int BACKUP_MANIFEST_VERSION = 1;
    private static final String BACKUP_FILE_HEADER_MAGIC = "ANDROID BACKUP\n";
    private static final int BACKUP_FILE_V1 = 1;
    private static final int BACKUP_FILE_V2 = 2;
    private static final int BACKUP_FILE_V3 = 3;
    private static final int BACKUP_FILE_V4 = 4;
    private static final int BACKUP_FILE_V5 = 5;

    private static final String ENCRYPTION_MECHANISM = "AES/CBC/PKCS5Padding";
    private static final int PBKDF2_HASH_ROUNDS = 10000;
    private static final int PBKDF2_KEY_SIZE = 256; // bits
    private static final int MASTER_KEY_SIZE = 256; // bits
    private static final int PBKDF2_SALT_SIZE = 512; // bits
    private static final String ENCRYPTION_ALGORITHM_NAME = "AES-256";

    private static final boolean DEBUG = false;

    private static final SecureRandom random = new SecureRandom();

    private AndroidBackup() {
    }

    public static void extractAsTar(String backupFilename, String filename, String password) {
        try {
            InputStream rawInStream = getInputStream(backupFilename);
            CipherInputStream cipherStream = null;

            if (Files.size(Paths.get(backupFilename)) == 0) {
                throw new IllegalStateException("File too small in size");
            }

            String magic = readHeaderLine(rawInStream);
            if (DEBUG) {
                System.err.println("Magic: " + magic);
            }
            String versionStr = readHeaderLine(rawInStream);
            if (DEBUG) {
                System.err.println("Version: " + versionStr);
            }
            int version = Integer.parseInt(versionStr);
            if (version < BACKUP_FILE_V1 || version > BACKUP_FILE_V5) {
                throw new IllegalArgumentException("Don't know how to process version " + versionStr);
            }

            String compressed = readHeaderLine(rawInStream);
            boolean isCompressed = Integer.parseInt(compressed) == 1;
            if (DEBUG) {
                System.err.println("Compressed: " + compressed);
            }
            String encryptionAlg = readHeaderLine(rawInStream);
            if (DEBUG) {
                System.err.println("Algorithm: " + encryptionAlg);
            }
            boolean isEncrypted = false;

            if (encryptionAlg.equals(ENCRYPTION_ALGORITHM_NAME)) {
                isEncrypted = true;

                if (Cipher.getMaxAllowedKeyLength("AES") < MASTER_KEY_SIZE) {
                    System.err.println("WARNING: Maximum allowed key-length seems smaller than needed. " +
                            "Please check that unlimited strength cryptography is available, see README.md for details");
                }

                if (password == null || "".equals(password)) {
                    Console console = System.console();
                    if (console != null) {
                        System.err.println("This backup is encrypted, please provide the password");
                        password = new String(console.readPassword("Password: "));
                    } else {
                        throw new IllegalArgumentException("Backup encrypted but password not specified");
                    }
                }

                String userSaltHex = readHeaderLine(rawInStream);
                byte[] userSalt = hexToByteArray(userSaltHex);
                if (userSalt.length != PBKDF2_SALT_SIZE / 8) {
                    throw new IllegalArgumentException("Invalid salt length: " + userSalt.length);
                }

                String ckSaltHex = readHeaderLine(rawInStream);
                byte[] ckSalt = hexToByteArray(ckSaltHex);

                int rounds = Integer.parseInt(readHeaderLine(rawInStream));
                String userIvHex = readHeaderLine(rawInStream);

                String masterKeyBlobHex = readHeaderLine(rawInStream);

                Cipher c = Cipher.getInstance(ENCRYPTION_MECHANISM);
                SecretKey userKey = buildPasswordKey(password, userSalt, rounds, false);
                byte[] IV = hexToByteArray(userIvHex);
                IvParameterSpec ivSpec = new IvParameterSpec(IV);
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(userKey.getEncoded(), "AES"), ivSpec);
                byte[] mkCipher = hexToByteArray(masterKeyBlobHex);
                byte[] mkBlob = c.doFinal(mkCipher);

                int offset = 0;
                int len = mkBlob[offset++];
                IV = Arrays.copyOfRange(mkBlob, offset, offset + len);
                if (DEBUG) {
                    System.err.println("IV: " + toHex(IV));
                }
                offset += len;
                len = mkBlob[offset++];
                byte[] mk = Arrays.copyOfRange(mkBlob, offset, offset + len);
                if (DEBUG) {
                    System.err.println("MK: " + toHex(mk));
                }
                offset += len;
                len = mkBlob[offset++];
                byte[] mkChecksum = Arrays.copyOfRange(mkBlob, offset, offset + len);
                if (DEBUG) {
                    System.err.println("MK checksum: " + toHex(mkChecksum));
                }

                boolean useUtf = version >= BACKUP_FILE_V2;
                byte[] calculatedCk = makeKeyChecksum(mk, ckSalt, rounds, useUtf);
                System.err.printf("Calculated MK checksum (use UTF-8: %s): %s\n", useUtf, toHex(calculatedCk));
                if (!Arrays.equals(calculatedCk, mkChecksum)) {
                    System.err.println("Checksum does not match.");
                    calculatedCk = makeKeyChecksum(mk, ckSalt, rounds, !useUtf);
                    System.err.printf("Calculated MK checksum (use UTF-8: %s): %s\n", useUtf, toHex(calculatedCk));
                }

                if (Arrays.equals(calculatedCk, mkChecksum)) {
                    ivSpec = new IvParameterSpec(IV);
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(mk, "AES"), ivSpec);
                    cipherStream = new CipherInputStream(rawInStream, c);
                }
            }

            if (isEncrypted && cipherStream == null) {
                throw new IllegalStateException("Invalid password or master key checksum.");
            }

            double fileSize = new File(backupFilename).length();
            double percentDone = -1;

            OutputStream out = null;
            InputStream baseStream = isEncrypted ? cipherStream : rawInStream;
            Inflater inf = null;
            InputStream in;
            if (isCompressed) {
                inf = new Inflater();
                in = new InflaterInputStream(baseStream, inf);
            } else {
                in = baseStream;
            }

            try {
                out = getOutputStream(filename);
                byte[] buff = new byte[10 * 1024];
                int read;
                long totalRead = 0;
                double currentPercent;
                long bytesRead;
                while ((read = in.read(buff)) > 0) {
                    out.write(buff, 0, read);
                    totalRead += read;
                    if (DEBUG && (totalRead % (100 * 1024) == 0)) {
                        System.err.printf("%d bytes read\n", totalRead);
                    }
                    bytesRead = inf == null ? totalRead : inf.getBytesRead();
                    currentPercent = Math.round(bytesRead / fileSize * 100);
                    if (currentPercent != percentDone) {
                        System.err.printf("%.0f%% ", currentPercent);
                        percentDone = currentPercent;
                    }
                }
                System.err.printf("\n%d bytes written to %s.\n", totalRead, filename);

            } finally {
                in.close();
                if (out != null) {
                    out.flush();
                    out.close();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void packTar(String tarFilename, String backupFilename, String password, boolean isKitKat) {
        boolean encrypting = password != null && !"".equals(password);
        boolean compressing = true;

        StringBuilder headerbuf = new StringBuilder(1024);
        headerbuf.append(BACKUP_FILE_HEADER_MAGIC);
        headerbuf.append(isKitKat ? BACKUP_FILE_V2 : BACKUP_FILE_V1);
        headerbuf.append(compressing ? "\n1\n" : "\n0\n");

        OutputStream out = null;
        try {
            InputStream in = getInputStream(tarFilename);
            OutputStream ofstream = getOutputStream(backupFilename);
            OutputStream finalOutput = ofstream;
            if (encrypting) {
                finalOutput = emitAesBackupHeader(headerbuf, finalOutput, password, isKitKat);
            } else {
                headerbuf.append("none\n");
            }

            byte[] header = headerbuf.toString().getBytes(StandardCharsets.UTF_8);
            ofstream.write(header);

            if (compressing) {
                Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
                finalOutput = new DeflaterOutputStream(finalOutput, deflater, true);
            }

            out = finalOutput;
            byte[] buff = new byte[10 * 1024];
            int read;
            int totalRead = 0;
            while ((read = in.read(buff)) > 0) {
                out.write(buff, 0, read);
                totalRead += read;
                if (DEBUG && (totalRead % (100 * 1024) == 0)) {
                    System.err.printf("%d bytes written\n", totalRead);
                }
            }
            System.err.printf("%d bytes written to %s.\n", totalRead, backupFilename);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException ignored) { }
            }
        }
    }

    private static InputStream getInputStream(String filename) throws IOException {
        return filename.equals("-") ? System.in : new FileInputStream(filename);
    }

    private static OutputStream getOutputStream(String filename) throws IOException {
        return filename.equals("-") ? System.out : new FileOutputStream(filename);
    }

    private static byte[] randomBytes(int bits) {
        byte[] array = new byte[bits / 8];
        random.nextBytes(array);
        return array;
    }

    private static OutputStream emitAesBackupHeader(StringBuilder headerbuf, OutputStream ofstream, String encryptionPassword, boolean useUtf8) throws Exception {
        byte[] newUserSalt = randomBytes(PBKDF2_SALT_SIZE);
        SecretKey userKey = buildPasswordKey(encryptionPassword, newUserSalt, PBKDF2_HASH_ROUNDS, useUtf8);

        byte[] masterPw = new byte[MASTER_KEY_SIZE / 8];
        random.nextBytes(masterPw);
        byte[] checksumSalt = randomBytes(PBKDF2_SALT_SIZE);

        Cipher c = Cipher.getInstance(ENCRYPTION_MECHANISM);
        SecretKeySpec masterKeySpec = new SecretKeySpec(masterPw, "AES");
        c.init(Cipher.ENCRYPT_MODE, masterKeySpec);
        OutputStream finalOutput = new CipherOutputStream(ofstream, c);

        headerbuf.append(ENCRYPTION_ALGORITHM_NAME);
        headerbuf.append('\n');
        headerbuf.append(toHex(newUserSalt));
        headerbuf.append('\n');
        headerbuf.append(toHex(checksumSalt));
        headerbuf.append('\n');
        headerbuf.append(PBKDF2_HASH_ROUNDS);
        headerbuf.append('\n');

        Cipher mkC = Cipher.getInstance(ENCRYPTION_MECHANISM);
        mkC.init(Cipher.ENCRYPT_MODE, userKey);
        byte[] IV = mkC.getIV();
        headerbuf.append(toHex(IV));
        headerbuf.append('\n');

        IV = c.getIV();
        byte[] mk = masterKeySpec.getEncoded();
        byte[] checksum = makeKeyChecksum(masterKeySpec.getEncoded(), checksumSalt, PBKDF2_HASH_ROUNDS, useUtf8);

        ByteArrayOutputStream blob = new ByteArrayOutputStream(IV.length + mk.length + checksum.length + 3);
        DataOutputStream mkOut = new DataOutputStream(blob);
        mkOut.writeByte(IV.length);
        mkOut.write(IV);
        mkOut.writeByte(mk.length);
        mkOut.write(mk);
        mkOut.writeByte(checksum.length);
        mkOut.write(checksum);
        mkOut.flush();
        byte[] encryptedMk = mkC.doFinal(blob.toByteArray());
        headerbuf.append(toHex(encryptedMk));
        headerbuf.append('\n');

        return finalOutput;
    }

    public static String toHex(byte[] bytes) {
        StringBuilder buff = new StringBuilder();
        for (byte b : bytes) {
            buff.append(String.format("%02X", b));
        }
        return buff.toString();
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        int c;
        StringBuilder buffer = new StringBuilder(80);
        while ((c = in.read()) >= 0) {
            if (c == '\n')
                break;
            buffer.append((char) c);
        }
        return buffer.toString();
    }

    public static byte[] hexToByteArray(String digits) {
        final int bytes = digits.length() / 2;
        if (2 * bytes != digits.length()) {
            throw new IllegalArgumentException("Hex string must have an even number of digits");
        }
        byte[] result = new byte[bytes];
        for (int i = 0; i < digits.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(digits.substring(i, i + 2), 16);
        }
        return result;
    }

    public static byte[] makeKeyChecksum(byte[] pwBytes, byte[] salt, int rounds, boolean useUtf8) {
        if (DEBUG) {
            System.err.println("key bytes: " + toHex(pwBytes));
            System.err.println("salt bytes: " + toHex(salt));
        }
        char[] mkAsChar = new char[pwBytes.length];
        for (int i = 0; i < pwBytes.length; i++) {
            mkAsChar[i] = (char) pwBytes[i];
        }
        if (DEBUG) {
            System.err.printf("MK as string: [%s]\n", new String(mkAsChar));
        }
        Key checksum = buildCharArrayKey(mkAsChar, salt, rounds, useUtf8);
        if (DEBUG) {
            System.err.println("Key format: " + checksum.getFormat());
        }
        return checksum.getEncoded();
    }

    public static SecretKey buildCharArrayKey(char[] pwArray, byte[] salt, int rounds, boolean useUtf8) {
        return androidPBKDF2(pwArray, salt, rounds, useUtf8);
    }

    public static SecretKey androidPBKDF2(char[] pwArray, byte[] salt, int rounds, boolean useUtf8) {
        PBEParametersGenerator generator = new PKCS5S2ParametersGenerator();
        byte[] pwBytes = useUtf8 ? PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(pwArray)
                : PBEParametersGenerator.PKCS5PasswordToBytes(pwArray);
        generator.init(pwBytes, salt, rounds);
        KeyParameter params = (KeyParameter) generator.generateDerivedParameters(PBKDF2_KEY_SIZE);
        return new SecretKeySpec(params.getKey(), "AES");
    }

    private static SecretKey buildPasswordKey(String pw, byte[] salt, int rounds, boolean useUtf8) {
        return buildCharArrayKey(pw.toCharArray(), salt, rounds, useUtf8);
    }
}
