package io.github.vasurayudu.feeds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PaymentRecordTest {

    @Test
    void parsesValidLine() {
        PaymentRecord p = PaymentRecord.parse("PAY-0001|VEND-ACME|123456789012|1500.25|usd|2026-11-02");

        assertEquals("PAY-0001", p.recordId());
        assertEquals("VEND-ACME", p.vendorId());
        assertEquals(new BigDecimal("1500.25"), p.amount());
        assertEquals("USD", p.currency());
        assertEquals(LocalDate.of(2026, 11, 2), p.valueDate());
        assertEquals("****9012", p.maskedAccountRef());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                                                     // empty
            "PAY-1|VEND|123456789012|100.00|USD",                   // missing field
            "|VEND|123456789012|100.00|USD|2026-11-02",             // missing recordId
            "PAY-1|VEND|12AB|100.00|USD|2026-11-02",                // bad account
            "PAY-1|VEND|123456789012|-5.00|USD|2026-11-02",         // negative amount
            "PAY-1|VEND|123456789012|10.123|USD|2026-11-02",        // 3 decimals
            "PAY-1|VEND|123456789012|ten|USD|2026-11-02",           // not a number
            "PAY-1|VEND|123456789012|100.00|JPY|2026-11-02",        // unsupported currency
            "PAY-1|VEND|123456789012|100.00|USD|11/02/2026"         // wrong date format
    })
    void rejectsInvalidLines(String line) {
        assertThrows(InvalidRecordException.class, () -> PaymentRecord.parse(line));
    }
}
