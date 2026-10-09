package fr.cafat.meta.output;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Empreintes SHA-1 hexadécimales (minuscules) servant aux identifiants stables. */
public final class Hashes {

  private Hashes() {
  }

  /**
   * SHA-1 du texte encodé en UTF-8.
   *
   * @param text texte, obligatoire
   * @return 40 caractères hexadécimaux minuscules
   */
  public static String sha1(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * SHA-1 des octets.
   *
   * @param bytes octets, obligatoire
   * @return 40 caractères hexadécimaux minuscules
   */
  public static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
