package fr.cafat.gpp.pg.domain;

public enum Civilite {
  M,
  MME,
  NON_PRECISE;

  public String libelle() {
    return name().toLowerCase();
  }
}
