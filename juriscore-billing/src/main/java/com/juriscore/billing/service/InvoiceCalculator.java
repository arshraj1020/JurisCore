package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.InvoiceLineItemRequest;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceLineItem;
import com.juriscore.billing.domain.Money;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Turns requested lines into stored lines, and the stored lines into the invoice's totals.
 *
 * <p><strong>Nothing a client sends about money is believed except quantity, unit price,
 * tax rate and discount.</strong> There is no {@code amount} field on
 * {@code InvoiceLineItemRequest} and no {@code subtotal} or {@code totalAmount} on
 * {@code CreateInvoiceRequest}, so a caller cannot disagree with the arithmetic — not
 * because the server checks their figures, but because it never asks for them.
 *
 * <p>The rounding rule lives in {@link Money} and is applied exactly twice per line. Every
 * figure above a line is a sum of amounts already at scale 2, which is exact, so
 * {@code total = subtotal + tax − discount} is an identity — which is what lets
 * {@code ck_invoices_total} assert it in the database rather than trust this class.
 *
 * <h2>What "tax" means here</h2>
 *
 * <p>One rate per line, applied to that line. That is all. It is not a GST engine: there
 * is no CGST/SGST/IGST split, no place-of-supply derivation, no reverse charge, no
 * HSN/SAC classification and no return filing. A firm using this is recording the tax it
 * has already worked out, and the product should not pretend otherwise.
 */
@Component
public class InvoiceCalculator {

    /** More lines than any real invoice, and few enough that the arithmetic stays bounded. */
    private static final int MAX_LINE_ITEMS = 200;

    /**
     * Replaces an invoice's lines and recomputes its totals.
     *
     * <p>Both halves together, always: a line list and a set of totals that were computed
     * from a different line list is the failure mode this class exists to make
     * unreachable.
     */
    public void applyLines(Invoice invoice, List<InvoiceLineItemRequest> requests,
                           BigDecimal requestedDiscount) {
        validate(requests);

        invoice.clearLineItems();
        BigDecimal subtotal = Money.ZERO;
        BigDecimal tax = Money.ZERO;

        for (int i = 0; i < requests.size(); i++) {
            InvoiceLineItemRequest request = requests.get(i);

            BigDecimal quantity = Money.quantity(request.quantity());
            BigDecimal unitPrice = Money.amount(request.unitPrice());
            BigDecimal rate = Money.rate(request.taxRate());

            BigDecimal amount = Money.lineAmount(quantity, unitPrice);
            BigDecimal lineTax = Money.taxOn(amount, rate);

            InvoiceLineItem item = new InvoiceLineItem();
            item.setDescription(request.description().trim());
            item.setQuantity(quantity);
            item.setUnitPrice(unitPrice);
            item.setAmount(amount);
            item.setTaxRate(rate);
            item.setTaxAmount(lineTax);
            item.setSortOrder(i);
            invoice.addLineItem(item);

            subtotal = subtotal.add(amount);
            tax = tax.add(lineTax);
        }

        BigDecimal discount = Money.amount(requestedDiscount);
        if (Money.isNegative(discount)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A discount cannot be negative");
        }
        BigDecimal gross = subtotal.add(tax);
        if (discount.compareTo(gross) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A discount of " + discount + " is more than the invoice's " + gross);
        }

        invoice.setSubtotal(Money.amount(subtotal));
        invoice.setTaxAmount(Money.amount(tax));
        invoice.setDiscountAmount(discount);
        invoice.setTotalAmount(Money.amount(gross.subtract(discount)));
    }

    /**
     * A stored line, back in the shape a request states it in.
     *
     * <p>Used when only the discount changes on a draft: re-pricing the existing lines is
     * the only way to check the new discount against the same gross the database is about
     * to check it against, and re-deriving it from the stored lines keeps one arithmetic
     * path rather than two that can drift.
     */
    public static InvoiceLineItemRequest asRequest(InvoiceLineItem item) {
        return new InvoiceLineItemRequest(item.getDescription(), item.getQuantity(),
                item.getUnitPrice(), item.getTaxRate());
    }

    private void validate(List<InvoiceLineItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An invoice needs at least one line item");
        }
        if (requests.size() > MAX_LINE_ITEMS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An invoice cannot carry more than " + MAX_LINE_ITEMS + " lines");
        }
        for (InvoiceLineItemRequest request : requests) {
            if (request.description() == null || request.description().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Every line needs a description");
            }
            if (!Money.isPositive(request.quantity())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "A line quantity must be greater than zero");
            }
            if (request.unitPrice() == null || Money.isNegative(request.unitPrice())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "A line's unit price cannot be negative");
            }
            BigDecimal rate = request.taxRate();
            if (rate != null && (Money.isNegative(rate) || rate.compareTo(new BigDecimal("100")) > 0)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "A tax rate is a percentage between 0 and 100");
            }
        }
    }
}
