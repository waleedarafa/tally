package com.tally.offers.domain;

import java.util.Optional;

/** The published offers (a port; {@code adapter.persistence} implements it, F9). */
public interface OfferCatalogue {

    Optional<Offer> offer(String offerId);
}
