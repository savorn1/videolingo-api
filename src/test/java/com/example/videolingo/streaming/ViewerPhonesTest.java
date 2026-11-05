package com.example.videolingo.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ViewerPhonesTest {

    @Test
    void displays_numbers_the_local_way() {
        assertThat(ViewerPhones.display("+85512345678")).isEqualTo("012 345 678");
        assertThat(ViewerPhones.display("+855961234567")).isEqualTo("096 123 4567");
        assertThat(ViewerPhones.display("+66812345678")).isEqualTo("+66812345678");
    }

    @Test
    void search_digits_drop_the_country_code_and_leading_zero() {
        assertThat(ViewerPhones.searchDigits("012 345")).isEqualTo("12345");
        assertThat(ViewerPhones.searchDigits("+855 12 34")).isEqualTo("1234");
        assertThat(ViewerPhones.searchDigits("Sokha")).isEmpty();
        assertThat(ViewerPhones.searchDigits("01")).isEmpty();
    }
}
