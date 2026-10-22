package io.github.lightmanufacturing.master.application;

import io.github.lightmanufacturing.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Minimal sales lookup projection; never returns PartnerDto/contact fields. */
@Service @Transactional(readOnly=true)
public class SalesEntityLookupService {
  private final JdbcTemplate jdbc;
  public SalesEntityLookupService(JdbcTemplate jdbc){this.jdbc=jdbc;}
  public List<Map<String,Object>> search(String dimension,String query){
    return select(dimension,"(name like ? escape '!' or code like ? escape '!')",List.of(literal(query),literal(query)),11);
  }
  public Map<String,Object> find(String dimension,String id){
    var rows=select(dimension,"id=?",List.of(Policy.id(id)),1);
    if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
  }
  public static String literal(String query){String q=Policy.text(query,120,"查询名称");return "%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
  private List<Map<String,Object>> select(String dimension,String filter,List<?> args,int limit){
    boolean product="product".equals(dimension);
    if(!product&&!"customer".equals(dimension))throw ApiException.invalid("仅支持客户或商品维度");
    String sql="select id,code,name,"+(product?"unit":"'' unit")+",enabled from "+(product?"material":"customer")+" where "+(product?"type='FINISHED_GOOD' and ":"")+filter+" order by id limit ?";
    return jdbc.query(c->{var s=c.prepareStatement(sql);s.setQueryTimeout(5);s.setMaxRows(limit);int i=1;for(Object a:args)s.setObject(i++,a);s.setInt(i,limit);return s;},(r,n)->Map.of("id",r.getString("id"),"code",r.getString("code"),"name",r.getString("name"),"unit",r.getString("unit"),"enabled",r.getBoolean("enabled")));
  }
}
