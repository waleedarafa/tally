package com.tally.flags;

import java.util.UUID;

/**
 * The admin operations of the flags module (ADR-053). Every method that changes state takes the
 * staff user's id, for the audit log line.
 */
public interface FlagsAdminApi {

    /** The report over every defined flag. */
    FlagReport report();

    /** One flag with its full change log. */
    FlagView flag(String name);

    /** Defines a new flag, off by default and not yet released. */
    FlagView define(String name, String staff);

    /** Sets the default for everyone without an assignment. */
    FlagView setDefault(String name, boolean on, String staff);

    /** Assigns the flag for one account. */
    FlagView assignAccount(String name, UUID accountId, boolean on, String staff);

    /** Clears the account assignment. */
    FlagView clearAccount(String name, UUID accountId, String staff);

    /** Assigns the flag for one country. */
    FlagView assignCountry(String name, String country, boolean on, String staff);

    /** Clears the country assignment. */
    FlagView clearCountry(String name, String country, String staff);

    /** Fully releases the flag. */
    FlagView release(String name, String staff);

    /** Removes the flag. */
    void remove(String name, String staff);
}
