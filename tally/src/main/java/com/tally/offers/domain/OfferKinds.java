package com.tally.offers.domain;

import java.util.List;

/** Every kind of offer, approved or not (a port; {@code adapter.persistence} implements it, F9). */
public interface OfferKinds {

    List<OfferKind> all();
}
