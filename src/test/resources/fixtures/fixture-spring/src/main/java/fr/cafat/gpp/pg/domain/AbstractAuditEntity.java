package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@MappedSuperclass
public abstract class AbstractAuditEntity {

  @Column(name = "date_creation", nullable = false)
  private LocalDateTime createdAt;

  private String createdBy;

  @Version
  private Integer version;
}
