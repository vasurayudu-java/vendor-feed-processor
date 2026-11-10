package io.github.vasurayudu.feeds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

/**
 * One payment line from a vendor file, in this format:
 * {@code recordId|vendorId|accountRef|amount|currency|valueDate(yyyy-MM-dd)}
 */
public record PaymentRecord(
        String recordId,
        String vendorId,
        String accountRef,
        BigDecimal amount,
        String currency,
        LocalDate valueDate
) {
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "GBP", "CAD");

    /** Parses and validates one line. Throws InvalidRecordException with a clear reason if the line is bad. */
    public static PaymentRecord parse(String line) {
        if (line == null || line.isBlank()) throw new InvalidRecordException("Empty line");
        String[] f = line.trim().split("\\|", -1);
        if (f.length != 6) throw new InvalidRecordException("Expected 6 fields but got " + f.length);

        String recordId = require(f[0], "recordId");
        String vendorId = require(f[1], "vendorId");
        String accountRef = require(f[2], "accountRef");
        if (!accountRef.matches("\\d{8,17}")) throw new InvalidRecordException("accountRef must be 8-17 digits");

        BigDecimal amount;
        try {
            amount = new BigDecimal(f[3].trim());
        } catch (NumberFormatException e) {
            throw new InvalidRecordException("Invalid amount: " + f[3]);
        }
        if (amount.signum() <= 0) throw new InvalidRecordException("Amount must be positive");
        if (amount.scale() > 2) throw new InvalidRecordException("Amount has more than 2 decimals");

        String currency = f[4].trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(currency)) throw new InvalidRecordException("Unsupported currency: " + f[4]);
        Currency.getInstance(currency);

        LocalDate valueDate;
        try {
            valueDate = LocalDate.parse(f[5].trim());
        } catch (Exception e) {
            throw new InvalidRecordException("Invalid value date: " + f[5]);
        }
        return new PaymentRecord(recordId, vendorId, accountRef, amount, currency, valueDate);
    }

    /** Masks the account number for logs, because full account numbers are PII and must never be logged. */
    public String maskedAccountRef() {
        return "****" + accountRef.substring(accountRef.length() - 4);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new InvalidRecordException("Missing " + field);
        return value.trim();
    }
}
