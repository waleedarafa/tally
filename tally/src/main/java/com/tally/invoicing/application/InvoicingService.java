package com.tally.invoicing.application;

import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvoicingService implements InvoicingApi {

    private final InvoiceRepository invoices;

    InvoicingService(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> invoicesFor(UUID accountId) {
        return invoices.findByAccountIdOrderByPeriodStart(accountId).stream().map(InvoicingService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> invoicesForSubscription(UUID subscriptionId) {
        return invoices.findBySubscriptionIdOrderByPeriodStart(subscriptionId).stream().map(InvoicingService::view)
                .toList();
    }

    static InvoiceView view(Invoice i) {
        return new InvoiceView(i.id(), i.accountId(), i.subscriptionId(), i.status(), i.amount(), i.periodStart(),
                i.periodEnd(), i.paymentReference(), i.issuedAt(), i.billedFor());
    }
}
