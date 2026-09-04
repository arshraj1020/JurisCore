package com.juriscore.billing.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The firm's own billing details: what goes at the top of its invoices, and how they are
 * numbered.
 *
 * <p>One per organization, enforced by {@code uk_billing_profiles_organization}. A firm
 * that has never opened the billing settings has no row, and
 * {@code BillingProfileService} answers with the defaults rather than a 404 — the absence
 * of a profile is not an error state, it is a firm that has not customised anything.
 *
 * <p><strong>No payment credentials live here.</strong> Not a card number, not a bank
 * password, not a gateway API key, not a tokenised instrument. JurisCore records that
 * money arrived; it never moves any, so it needs no credential to store and has no column
 * that could hold one. Wiring a gateway in later means adding a secrets vault, not
 * widening this table.
 */
@Entity
@Table(name = "billing_profiles", schema = "billing")
@Getter
@Setter
@NoArgsConstructor
public class BillingProfile extends TenantAwareEntity {

    @Column(name = "legal_name", length = 200)
    private String legalName;

    /**
     * The firm's tax registration number — a GSTIN in India. Recorded and printed, and
     * that is the whole of it: nothing validates its checksum, derives a place of supply
     * from it, or files a return with it. See {@code InvoiceCalculator} on what "tax"
     * means in Phase 5.
     */
    @Column(name = "tax_registration", length = 64)
    private String taxRegistration;

    @Column(name = "billing_email", length = 255)
    private String billingEmail;

    @Column(name = "billing_phone", length = 40)
    private String billingPhone;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "country", length = 120)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /** Used when an invoice does not name one. Never applied retroactively. */
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency = "INR";

    /** The {@code INV} in {@code INV-2026-000001}. */
    @Column(name = "invoice_prefix", nullable = false, length = 12)
    private String invoicePrefix = "INV";

    /** Boilerplate copied onto new invoices — payment terms, bank details a firm prints. */
    @Column(name = "invoice_notes", length = 2000)
    private String invoiceNotes;
}
