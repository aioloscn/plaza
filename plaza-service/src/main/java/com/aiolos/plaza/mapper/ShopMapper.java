package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 店铺表 Mapper 接口
 * </p>
 *
 * @author Aiolos
 * @since 2025-08-22
 */
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("select id, name, icon_url, address, category_id, score, per_capita_price, tags, longitude, latitude,\n" +
            "       ceil(6378137 * 2 * ASIN(SQRT(POW(SIN(PI() * (#{latitude} - latitude) / 360), 2) + \n" +
            "       COS(PI() * #{latitude} / 180) * COS(latitude * PI() / 180) * \n" +
            "       POW(SIN(PI() * (#{longitude} - longitude) / 360), 2)))) as distance\n" +
            "from shop where status = 1\n" +
            "order by (0.95 * 1 / log10(distance + 1) + 0.05 * score / 5) desc")
    List<Shop> recommend(@Param("latitude") double latitude, @Param("longitude") double longitude);

    @Select("<script>" +
            "select id, name, icon_url, address, category_id, score, per_capita_price, tags, longitude, latitude,\n" +
            "       ceil(6378137 * 2 * ASIN(SQRT(POW(SIN(PI() * (#{latitude} - latitude) / 360), 2) + \n" +
            "       COS(PI() * #{latitude} / 180) * COS(latitude * PI() / 180) * \n" +
            "       POW(SIN(PI() * (#{longitude} - longitude) / 360), 2)))) as distance\n" +
            "from shop where status = 1 " +
            "<if test='categoryId != null'> and category_id = #{categoryId} </if>" +
            "and (name like concat('%', #{keyword}, '%') or tags like concat('%', #{keyword}, '%') or address like concat('%', #{keyword}, '%'))\n" +
            "<choose>" +
            "<when test='orderBy != null and orderBy == 1'>order by (0.98 * 1 / log10(distance + 1) + 0.02 * score / 5) desc</when>" +
            "<otherwise>" +
            " order by " +
            "   case when distance &lt;= 300 then 0 else 1 end asc, " +
            "   case when distance &lt;= 300 then score end desc, " +
            "   case when distance &lt;= 300 then distance end asc, " +
            "   case when distance &gt; 300 then (0.9 * 1 / log10(distance + 1) + 0.1 * score / 5) end desc" +
            "</otherwise>" +
            "</choose>" +
            "</script>")
    List<Shop> search(@Param("latitude") double latitude, @Param("longitude") double longitude, @Param("keyword") String keyword, @Param("categoryId") Long categoryId, @Param("orderBy") Integer orderBy);
}
