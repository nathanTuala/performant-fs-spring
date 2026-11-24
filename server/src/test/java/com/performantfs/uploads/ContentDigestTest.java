package com.performantfs.uploads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.performantfs.http.ApiException;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ContentDigestTest {

    @Test
    void readsSha256AsHex() {
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) 0xab);
        assertThat(ContentDigest.sha256Hex("sha-256=:" + Base64.getEncoder().encodeToString(digest) + ":")).isEqualTo("ab".repeat(32));
    }

    @Test
    void absentMeansNoCheck() {
        assertThat(ContentDigest.sha256Hex(null)).isNull();
    }

    @Test
    void rejectsOtherAlgorithmsAndWrongLengths() {
        assertThatThrownBy(() -> ContentDigest.sha256Hex("md5=:abc:")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentDigest.sha256Hex("sha-256=:AAAA:")).isInstanceOf(ApiException.class);
    }
}
