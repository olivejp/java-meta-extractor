package fr.cafat.gpp.pg.repository;

import fr.cafat.gpp.pg.domain.PersonnePhysique;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonneRepository extends JpaRepository<PersonnePhysique, Long> {

  @Query(value = "SELECT p.* FROM sgengpp.gpp_personne_physique p "
      + "JOIN sgengpp.gpp_moyen_contact m ON m.fk_personne_physique = p.numero_interne "
      + "WHERE m.id = :id", nativeQuery = true)
  List<PersonnePhysique> findByMoyenContact(@Param("id") Long id);

  @Modifying
  @Query(value = """
      UPDATE sgengpp.gpp_personne_physique
         SET nom = :nom
       WHERE numero_interne = :id
      """, nativeQuery = true)
  int renommer(@Param("id") Long id, @Param("nom") String nom);

  @Query("select p from PersonnePhysique p where p.nom = :nom")
  List<PersonnePhysique> findByNomJpql(@Param("nom") String nom);
}
