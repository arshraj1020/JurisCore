package com.juriscore.casework.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A party the firm acts for.
 *
 * <p>Not an {@code identity.users} row: a client is a party to a matter, and most of
 * them never sign in. The two are related only through cases.
 *
 * <p>Deletion is soft. A matter opened years ago still has to name the client it was
 * opened for, and the timeline entries written at the time have to keep making sense,
 * so {@link #deletedAt} hides the row from lists and from new cases without breaking
 * anything already pointing at it.
 */
@Entity
@Table(name = "clients", schema = "casework")
@Getter
@Setter
@NoArgsConstructor
public class Client extends TenantAwareEntity {

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 32)
    private ClientType clientType;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "country", length = 120)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }
}
