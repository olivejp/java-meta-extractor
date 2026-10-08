package fr.cafat.gpp.pg.repository;

import fr.cafat.gpp.pg.domain.Evenement;
import fr.cafat.gpp.pg.domain.PGUnion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvenementRepository extends JpaRepository<Evenement, Long> {

  default PGUnion union() {
    return null;
  }
}
