package com.anying.helpdesk.modelconfig.crypto;

public interface SecretEncryptor {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
