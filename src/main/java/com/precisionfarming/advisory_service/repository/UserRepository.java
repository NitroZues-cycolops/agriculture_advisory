package com.precisionfarming.advisory_service.repository;

import com.precisionfarming.advisory_service.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /** Uniqueness checks for PART B registration (email is stored lower-case). */
    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    /** Counts one role (FARMER / LAB / OWNER) for the owner dashboard. */
    long countByRole(String role);
}
