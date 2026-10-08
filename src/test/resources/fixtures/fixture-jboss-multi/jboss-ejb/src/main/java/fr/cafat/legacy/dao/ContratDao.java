package fr.cafat.legacy.dao;

import java.util.List;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

@Stateless
public class ContratDao {

  @PersistenceContext(unitName = "as400PU")
  private EntityManager as400;

  @PersistenceContext(unitName = "gppPU")
  private EntityManager gpp;

  @SuppressWarnings("unchecked")
  public List<Object[]> personnes(long numero) {
    return as400.createNativeQuery("SELECT NUMERO_INTERNE, NOM FROM MGENGPP.VW_PERSONNE WHERE NUMERO_INTERNE = ?1")
        .setParameter(1, numero).getResultList();
  }

  @SuppressWarnings("unchecked")
  public List<Object[]> adresses(long numero) {
    return as400.createNativeQuery("SELECT * FROM MGENGPP/VW_ADRESSE WHERE NUMERO_INTERNE = " + numero)
        .getResultList();
  }

  public int cloturer(long idContrat) {
    return gpp.createNativeQuery("UPDATE legacy.contrat SET date_effet = CURRENT_DATE WHERE id_contrat = ?1")
        .setParameter(1, idContrat).executeUpdate();
  }
}
