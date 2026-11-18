package com.tally.account.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountUserRepository extends JpaRepository<AccountUser, UUID> {

    Optional<AccountUser> findByUserId(String userId);
}
