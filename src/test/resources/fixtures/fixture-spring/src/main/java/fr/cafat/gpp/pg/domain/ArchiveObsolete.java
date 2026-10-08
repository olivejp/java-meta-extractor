package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Entité morte : référencée nulle part. */
@Entity
public class ArchiveObsolete {

  @Id
  private Long id;

  private String contenuArchive;
}
