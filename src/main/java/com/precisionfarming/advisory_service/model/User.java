package com.precisionfarming.advisory_service.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;

    private boolean enabled = true;

    // --- PART B: account-level contact identity (see schema_new.sql section 10) ---
    // email/phone live here (not only on farmer_profiles) so uniqueness can be
    // checked in one place for every role. Both are nullable for rows created
    // before PART B. No unique constraint on phone: a lab may share a landline.
    @Column(length = 120)
    private String email;

    @Column(length = 15)
    private String phone;

    @Column(name = "phone_verified")
    private boolean phoneVerified;

    @Column(name = "email_verified")
    private boolean emailVerified;
}
