package fr.cafat.gpp.db2.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GeoMapper {

  String findCommune(@Param("code") String code);

  @Select("SELECT LIBELLE FROM CAFGEO.GEO_PAYS WHERE CODE_PAYS = #{code}")
  String libellePays(@Param("code") String code);
}
