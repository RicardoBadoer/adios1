package iri;

public class Bundle {

    static int signatureSecurityLevel(final Hash signatureHash) {

        int sum = 0;
        for (int offset = 0; offset < Bastard.HASH_SIZE; ) {

            sum += Converter.tryteValue(signatureHash.trits(), offset);
            offset += Converter.NUMBER_OF_TRITS_IN_A_TRYTE;

            if (sum == 0) {

                for (int level = Signature.LOW_SECURITY_LEVEL; level <= Signature.HIGH_SECURITY_LEVEL; level++) {

                    if (offset == (Bastard.HASH_SIZE / Signature.NUMBER_OF_SECURITY_LEVELS) * (level + 1)) {

                        return level;
                    }
                }
            }
        }

        return Signature.ILLEGAL_SECURITY_LEVEL;
    }
}
