package com.tally.invoicing.application;

import com.tally.invoicing.InvoiceLineView;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import com.tally.payment.PaymentApi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvoicingService implements InvoicingApi {

    private static final Logger log = LoggerFactory.getLogger(InvoicingService.class);

    private final Invoices invoices;
    private final PaymentApi payments;

    InvoicingService(Invoices invoices, PaymentApi payments) {
        this.invoices = invoices;
        this.payments = payments;
    }

    /** No transaction: Payment asks the provider (rule 6). The invoice is read, never changed, here. */
    @Override
    public void requestPaymentAgain(UUID invoiceId, int retry) {
        Optional<Invoice> invoice = invoices.find(invoiceId);
        if (invoice.isEmpty() || invoice.get().status() != InvoiceStatus.PAYMENT_FAILED) {
            log.info("Retry {} of invoice {}: its payment has not failed ({}); nothing to collect", retry, invoiceId,
                    invoice.map(Invoice::status).orElse(null));
            return;
        }
        payments.collectAgain(invoice.get().paymentReference(), retry);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> invoicesFor(UUID accountId) {
        return invoices.ofAccount(accountId).stream().map(InvoicingService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> invoicesForSubscription(UUID subscriptionId) {
        return invoices.ofSubscription(subscriptionId).stream().map(InvoicingService::view)
                .toList();
    }

    static InvoiceView view(Invoice i) {
        return new InvoiceView(i.id(), i.accountId(), i.subscriptionId(), i.status(), i.amount(), i.periodStart(),
                i.periodEnd(), i.paymentReference(), i.issuedAt(), i.billedFor(),
                i.lines().stream().map(l -> new InvoiceLineView(l.description(), l.amount())).toList(),
                i.offerVersion(), i.quoteReason());
    }
}
