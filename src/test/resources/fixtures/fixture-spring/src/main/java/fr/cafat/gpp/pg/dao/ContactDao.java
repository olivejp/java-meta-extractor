package fr.cafat.gpp.pg.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactDao {

  private static final String INSERT_COURRIEL =
      "INSERT INTO sgengpp.gpp_courriel (id_moyen_contact, adresse_mail) VALUES (?, ?)";

  private static final String TABLE_ALIAS = "sgengpp.gpp_personne_alias";

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbc;
  private final DataSource dataSource;

  public ContactDao(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc, DataSource dataSource) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbc = namedJdbc;
    this.dataSource = dataSource;
  }

  public List<Map<String, Object>> telephones(long id) {
    return jdbcTemplate.queryForList("SELECT id_moyen_contact, numero FROM sgengpp.gpp_telephone WHERE id_moyen_contact = ?", id);
  }

  public void ajouterCourriel(long id, String adresse) {
    jdbcTemplate.update(INSERT_COURRIEL, id, adresse);
  }

  public List<Map<String, Object>> groupes(List<Long> ids) {
    return namedJdbc.queryForList("SELECT * FROM sgengpp.gpp_groupe WHERE id IN (:ids)",
        new MapSqlParameterSource("ids", ids));
  }

  public int purgerAlias() {
    return jdbcTemplate.update("DELETE FROM " + TABLE_ALIAS + " WHERE alias IS NULL");
  }

  /** SQL dynamique : la clause dépend du paramètre. */
  public List<Map<String, Object>> evenements(String type) {
    String sql = "SELECT * FROM sgengpp.gpp_evenement WHERE 1 = 1";
    if (type != null) {
      sql += " AND type_evt = '" + type + "'";
    }
    return jdbcTemplate.queryForList(sql);
  }

  public void viderTemporaire() throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.prepareStatement("TRUNCATE TABLE sgengpp.gpp_import_tmp").execute();
    }
  }
}
