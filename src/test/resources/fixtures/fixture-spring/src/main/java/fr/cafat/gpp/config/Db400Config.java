package fr.cafat.gpp.config;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

@Configuration
@EnableJpaRepositories(basePackages = "fr.cafat.gpp.db2.repository",
    entityManagerFactoryRef = "db400EntityManagerFactory")
@MapperScan(basePackages = "fr.cafat.gpp.db2.mapper", sqlSessionFactoryRef = "db400SqlSessionFactory")
public class Db400Config {

  @Bean
  @ConfigurationProperties(prefix = "spring.datasource.db400")
  public DataSource db400DataSource() {
    return DataSourceBuilder.create().build();
  }

  @Bean
  public LocalContainerEntityManagerFactoryBean db400EntityManagerFactory(EntityManagerFactoryBuilder builder,
      @Qualifier("db400DataSource") DataSource dataSource) {
    return builder.dataSource(dataSource).packages("fr.cafat.gpp.db2.domain").persistenceUnit("db400").build();
  }

  @Bean
  public JdbcTemplate db400JdbcTemplate(@Qualifier("db400DataSource") DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean
  public SqlSessionFactory db400SqlSessionFactory(@Qualifier("db400DataSource") DataSource dataSource)
      throws Exception {
    SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    return factory.getObject();
  }
}
