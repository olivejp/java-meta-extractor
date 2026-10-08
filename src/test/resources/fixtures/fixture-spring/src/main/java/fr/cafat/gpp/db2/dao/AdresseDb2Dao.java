package fr.cafat.gpp.db2.dao;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdresseDb2Dao {

  private final JdbcTemplate db400;

  public AdresseDb2Dao(@Qualifier("db400JdbcTemplate") JdbcTemplate db400) {
    this.db400 = db400;
  }

  public List<Map<String, Object>> adresses(long numero) {
    return db400.queryForList("SELECT * FROM MGENGPP/VW_ADRESSE WHERE NUMERO_INTERNE = ?", numero);
  }
}
