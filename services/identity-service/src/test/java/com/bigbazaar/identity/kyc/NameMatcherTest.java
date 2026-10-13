package com.bigbazaar.identity.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameMatcherTest {

    @Test
    void sameNameWrittenDifferentlyMatches() {
        assertThat(NameMatcher.score("Kai Sharma", "KAI  SHARMA")).isEqualTo(1.0);
        assertThat(NameMatcher.score("Sharma Kai", "Kai Sharma")).isEqualTo(1.0);
        assertThat(NameMatcher.score("Shri Kai Sharma", "KAI SHARMA")).isEqualTo(1.0);
        assertThat(NameMatcher.score("M/s Kai Crafts Pvt. Ltd.", "KAI CRAFTS PRIVATE LIMITED")).isEqualTo(1.0);
        assertThat(NameMatcher.score("K Sharma", "Kai Sharma")).isEqualTo(1.0);
        assertThat(NameMatcher.score("Kaï Sharma", "Kai Sharma")).isEqualTo(1.0);
    }

    @Test
    void missingMiddleNameStillPassesButMissingSurnameDoesNot() {
        assertThat(NameMatcher.matches("Kai Kumar Sharma", "Kai Sharma", 0.8)).isTrue();
        assertThat(NameMatcher.matches("Kai", "Kai Sharma", 0.8)).isFalse();
    }

    @Test
    void differentPeopleDoNotMatch() {
        assertThat(NameMatcher.matches("Someone Else", "Kai Sharma", 0.8)).isFalse();
        assertThat(NameMatcher.matches("Kai Verma", "Kai Sharma", 0.8)).isFalse();
        assertThat(NameMatcher.score(null, "Kai")).isZero();
        assertThat(NameMatcher.score("Pvt Ltd", "Kai")).isZero();
    }
}
