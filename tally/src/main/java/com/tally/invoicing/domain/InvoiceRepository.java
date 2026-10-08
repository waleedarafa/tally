package com.tally.invoicing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    boolean existsByAccountIdAndPeriodStart(UUID accountId, LocalDate periodStart);

    Optional<Invoice> findByPaymentReference(String paymentReference);

    List<Invoice> findByAccountIdOrderByPeriodStart(UUID accountId);

    List<Invoice> findBySubscriptionIdOrderByPeriodStart(UUID subscriptionId);

    List<Invoice> findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(Instant from, Instant to);
}
