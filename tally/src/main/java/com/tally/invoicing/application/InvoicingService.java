package com.tally.invoicing.application;

import com.tally.invoicing.InvoiceLineView;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvoicingService implements InvoicingApi {

    private final Invoices invoices;

    InvoicingService(Invoices invoices) {
        this.invoices = invoices;
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
