package com.bigbazaar.identity.seller;

import com.bigbazaar.identity.mfa.SecretCipher;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Applicant inputs that are personal data. Stored encrypted and bound to the seller id. */
record KycInputs(String bankAccountNumber, String panDate) {

    String seal(SecretCipher cipher, JsonMapper json, UUID sellerId) {
        return cipher.encrypt(json.writeValueAsBytes(this), associatedData(sellerId));
    }

    static KycInputs open(String sealed, SecretCipher cipher, JsonMapper json, UUID sellerId) {
        if (sealed == null) {
            return new KycInputs(null, null);
        }
        return json.readValue(cipher.decrypt(sealed, associatedData(sellerId)), KycInputs.class);
    }

    private static String associatedData(UUID sellerId) {
        return "seller-kyc:" + sellerId;
    }

    static String last4(String accountNumber) {
        return accountNumber == null ? null : accountNumber.substring(accountNumber.length() - 4);
    }

}
