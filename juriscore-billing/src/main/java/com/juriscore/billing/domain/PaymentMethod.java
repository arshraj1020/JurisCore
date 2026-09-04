package com.juriscore.billing.domain;

/**
 * How the firm was paid — a label somebody chose, and nothing more.
 *
 * <p>Worth being blunt about, because the names look like integrations and are not:
 * JurisCore is connected to no payment gateway, no card network, no UPI handle and no
 * bank. {@code CARD} does not mean a card was charged here; it means a person recorded
 * that a client paid by card somewhere else. Nothing in this platform collects, stores
 * or transmits a payment credential, and there is no column in {@code billing.payments}
 * that could hold one.
 */
public enum PaymentMethod {
    CASH,
    BANK_TRANSFER,
    CARD,
    UPI,
    CHEQUE,
    OTHER
}
