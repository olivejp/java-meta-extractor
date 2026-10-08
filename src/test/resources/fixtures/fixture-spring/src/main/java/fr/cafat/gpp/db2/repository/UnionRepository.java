package fr.cafat.gpp.db2.repository;

import fr.cafat.gpp.db2.domain.GeoCommune;
import fr.cafat.gpp.db2.domain.PersonneDb2;
import fr.cafat.gpp.db2.domain.Union;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnionRepository extends JpaRepository<Union, Long> {

  @Query(value = "SELECT u.* FROM MGENGPP.V_UNION u WHERE u.LIBELLE LIKE ?1", nativeQuery = true)
  List<Union> rechercher(String libelle);

  List<PersonneDb2> findPersonnes();

  List<GeoCommune> findCommunes();
}
